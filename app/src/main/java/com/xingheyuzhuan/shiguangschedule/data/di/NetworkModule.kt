package com.xingheyuzhuan.shiguangschedule.data.di

import com.xingheyuzhuan.shiguangschedule.data.network.RetryInterceptor
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuAcademicScraper
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuCookieJar
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuTrustAllManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * 为 SCNU 教务系统网络抓取模块提供依赖注入。
 * 所有 OkHttpClient / CookieJar / Json 实例均使用 @Named("scnu") 限定符，
 * 与项目中其他网络模块隔离开，避免 SSL 信任配置被误用。
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object NetworkModule {

    @Provides
    @Singleton
    @Named("scnu")
    fun provideScnuCookieJar(): CookieJar = ScnuCookieJar()

    @Provides
    @Singleton
    @Named("scnu")
    fun provideScnuHttpClient(
        @Named("scnu") cookieJar: CookieJar
    ): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(ScnuTrustAllManager)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .sslSocketFactory(sslContext.socketFactory, ScnuTrustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
                        .header("Accept-Language", "zh-CN,zh;q=0.9")
                        .build()
                )
            }
            // 瞬时故障退避重试。只作用于幂等请求与显式标注 RETRY_HEADER 的只读查询，
            // 因此对已发布的课表/成绩/考试同步链路只是变健壮，行为语义不变。
            .addInterceptor(RetryInterceptor())
            .build()
    }

    @Provides
    @Singleton
    @Named("scnu")
    fun provideScnuJson(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun provideScnuScraper(
        @Named("scnu") httpClient: OkHttpClient,
        @Named("scnu") json: Json
    ): ScnuScraper = ScnuScraper(httpClient, json)

    /**
     * 学业情况抓取器。
     *
     * 与 [ScnuScraper] 共用同一个 `@Named("scnu")` client 与 CookieJar，
     * 因此 [ScnuScraper.login] 建立的会话对它直接生效。
     */
    @Provides
    @Singleton
    fun provideScnuAcademicScraper(
        @Named("scnu") httpClient: OkHttpClient,
        @Named("scnu") json: Json
    ): ScnuAcademicScraper = ScnuAcademicScraper(httpClient, json)

    // ═══════════════════════════════════════════════════════════════════════
    // AI（P-AI）—— ⚠️ 与上面的 scnu 链路**完全隔离**，这不是洁癖，是安全边界
    //
    // 上面那个 `@Named("scnu")` 的 client 为了兼容校园自签证书，做了两件
    // 在公网场景下**必须避免**的事：
    //
    //     .sslSocketFactory(sslContext.socketFactory, ScnuTrustAllManager) // 信任所有证书
    //     .hostnameVerifier { _, _ -> true }                               // 不校验主机名
    //
    // 用它去调 LLM API 等于关掉证书校验 —— 用户的 **API Key 会在可被中间人
    // 劫持的信道上传输**。因此 AI 链路**自建** client，且：
    //
    // 1. 用 Ktor + OkHttp 引擎（Ktor 已在依赖中，`ApiDateImporter` 有先例）；
    // 2. **不设置任何** sslSocketFactory / hostnameVerifier —— 用平台默认的
    //    证书链与主机名校验；
    // 3. **不安装 Logging 插件** —— 它的默认 logger 会打印请求头，
    //    而 Authorization 头里就是用户的 API Key（硬约束：日志绝不打印 Key）；
    // 4. **不装 CookieJar** —— 与教务会话无任何关系，也不该有关系。
    //
    // 超时与 scnu 的 30 秒不同：流式（SSE）响应可能持续更久，
    // 读超时必须放宽，否则长回答会被中途掐断。
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * AI 链路专用的 Ktor client。
     *
     * - `socketTimeoutMillis` 是**两次数据之间**的间隔上限（60 秒没有任何字节才算超时），
     *   因此不会误杀一个正在逐字输出的长回答；
     * - `requestTimeoutMillis` 是整次请求的总上限（5 分钟），防极端情况下挂死；
     * - `expectSuccess = false`：非 2xx 不抛异常，交给 `OpenAiCompatibleProvider`
     *   按状态码映射成本地化原因（401 要提示改 Key、402 要提示充值，语义不同）。
     */
    @Provides
    @Singleton
    @Named("ai")
    fun provideAiHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false

        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 300_000
        }
    }

    /**
     * AI 链路专用的 Json。
     *
     * 这两个开关**必须成对设置**，否则流式会静默失效：
     *
     * | 开关 | 值 | 为什么 |
     * |---|---|---|
     * | `explicitNulls` | `false` | 可选字段（tools / thinking / temperature）为 null 时必须**整个字段消失**，而不是发 `"tools": null` —— 严格实现的 OpenAI 兼容服务端会对 null 报 400 |
     * | `encodeDefaults` | `true` | ⚠️ **关键**：`stream` 的默认值恰好是 `true`，若按默认的 `encodeDefaults = false`，`stream = true` 会被**整个省略**，而 OpenAI 协议里服务端对缺省 `stream` 的解读是 **false** —— 结果是我们以为在流式，实际拿到一个非流式的整包响应，界面表现为"一直转圈然后一次性蹦出全文" |
     */
    @Provides
    @Singleton
    @Named("ai")
    fun provideAiJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }
}
