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
}
