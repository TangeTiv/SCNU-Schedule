package com.xingheyuzhuan.shiguangschedule.data.network.selection

import com.xingheyuzhuan.shiguangschedule.data.network.ScnuCookieJar
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 登录失败异常。携带从页面提取的教务原文错误信息。
 */
open class ScnuLoginException(message: String) : Exception(message)

/**
 * **凭据被拒**（学号或密码错误）。
 *
 * 与父类 [ScnuLoginException] 的区别是语义更窄：只表示"教务明确拒绝了这组凭据"，
 * 不包含网络故障、HTTP 5xx、授权未落地等情况。
 *
 * ## 为什么必须单独分出来
 *
 * 失败锁定（连续 N 次失败进入冷却）**只能**统计这一种失败。
 * 若把网络抖动也算进去，用户在信号差的地方点几次就会被锁死，
 * 而且锁的还是他自己的 SSO 账号 —— 那是纯粹的伤害。
 */
class ScnuInvalidCredentialException(message: String) : ScnuLoginException(message)

/**
 * SCNU 统一身份认证（SSO）登录 —— **全项目唯一的登录实现**。
 *
 * 严格 1:1 翻译自 `scnu_course_selector.py` 的 `login()`，实现**双授权路径**：
 *
 * ```
 * 1. GET  /AccountService/user/login.html      → 正则抠 <form action>
 * 2. POST 凭据（no-redirect）                  → 302 即成功
 * 3. GET  /openapi/auth.html?client_id=...     → 主路径：OAuth code 流程
 *       └ 若响应含 gotoApp → 再从 JS 里抠 var url 并跳转
 * 4. GET  /openapi/fastlogin.html?app_id=96    → 兜底路径：app_id 授权
 * 5. 校验 CookieJar 中 jwxt.scnu.edu.cn 是否已有会话 Cookie
 * ```
 *
 * ## 与 `ScnuScraper.login` 的关系（v1.7.0 起已合并）
 *
 * v1.6.0 时两套登录实现并存：`ScnuScraper.login()` 只走路径 4，
 * 本类额外实现路径 3。当时的顾虑是"不想给已发布的同步链路引入回归风险"。
 *
 * v1.7.0 完成合并（消化掉原 TODO）：
 * - `ScnuScraper.login()` **已删除**，本类成为唯一入口；
 * - `ScnuScraper` 只保留爬取职责（`fetchCourses` / `fetchGrades` / `fetchExams`）；
 * - 合并时**补上了原实现里的最终校验**（路径 5）——
 *   原 `ScnuScraper.login()` 会检查 fastlogin 后是否落到 `jwxt` 域名，
 *   而本类原来直接 `return true`，合并时若不补回，同步链路会失去这层保护。
 *
 * ## 会话共享
 *
 * 使用与 [ScnuScraper] 相同的 `@Named("scnu")` [OkHttpClient] 与 CookieJar，
 * 因此本类登录成功后，[ScnuScraper] 的会话也随之建立（反之亦然）。
 * 这正是"选课模块退出时需清理 CookieJar"这一约束的根源。
 */
@Singleton
class ScnuSsoLogin @Inject constructor(
    @Named("scnu") private val httpClient: OkHttpClient
) {

    companion object {
        private const val SSO_BASE = "https://sso.scnu.edu.cn"
        private const val SSO_SERVICE = "$SSO_BASE/AccountService"
        private const val JWXT_BASE = "https://jwxt.scnu.edu.cn"

        /** 教务系统域名，用于最终会话校验（阶段 E） */
        private const val JWXT_HOST = "jwxt.scnu.edu.cn"

        /**
         * 教务系统在 SSO 侧的 OAuth 应用标识。
         *
         * 与 fastlogin 的 `app_id=96` 是**两套不同的授权标识**，不可互换。
         * 来源：脚本 `CLIENT_ID`。
         */
        private const val CLIENT_ID = "9347e8e342e93da94c8ecf27a9de2599"

        /** fastlogin 兜底路径的 app_id */
        private const val APP_ID = "96"

        /** 提取 `<form action="...">`，对应脚本 `r'<form[^>]*action=["\']([^"\']*)["\']'` */
        private val FORM_ACTION_RE = Regex(
            """<form[^>]*action\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        /** 提取页面错误文案，对应脚本 `r'(?:alert|error|msgtext)[^>]*>([^<]+)'` */
        private val ERROR_MSG_RE = Regex(
            """(?:alert|error|msgtext)[^>]*>\s*([^<]+?)\s*<""",
            RegexOption.IGNORE_CASE
        )

        /** 从 auth.html 的 JS 中提取跳转地址，对应脚本 `r'var\s+url\s*=\s*["\']([^"\']+)["\']'` */
        private val GOTO_URL_RE = Regex(
            """var\s+url\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * 执行 SSO 登录并建立教务系统会话。
     *
     * @param account 学号
     * @param password 密码（仅用于本次请求，**不做任何持久化**）
     * @return 始终返回 `true`；失败时抛 [ScnuLoginException]
     * @throws ScnuLoginException 凭据错误、网络异常或授权未进入教务系统
     */
    suspend fun login(account: String, password: String): Boolean = withContext(Dispatchers.IO) {
        // ══ 阶段 A: GET 登录页，抠出表单 action ══
        val loginPageUrl = "$SSO_SERVICE/user/login.html"
        val html = get(loginPageUrl)

        // 默认 action = 登录页自身（对应脚本的兜底逻辑）
        var action = loginPageUrl
        FORM_ACTION_RE.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { extracted ->
            action = if (extracted.startsWith("http")) {
                extracted
            } else {
                // 相对路径 → 绝对 URL，对应脚本 urllib.parse.urljoin
                URI(loginPageUrl).resolve(extracted).toString()
            }
        }

        // ══ 阶段 B: POST 凭据，禁止跟随重定向以捕获 302 ══
        // 派生临时 client：共享 CookieJar 与连接池，仅关闭重定向自动跟随
        val noRedirectClient = httpClient.newBuilder().followRedirects(false).build()
        val formBody = FormBody.Builder()
            .add("account", account)
            .add("password", password)
            .add("rancode", "")
            .build()
        val postRequest = Request.Builder()
            .url(action)
            .header("Referer", action)
            .header("Origin", SSO_BASE)
            .post(formBody)
            .build()

        noRedirectClient.newCall(postRequest).execute().use { response ->
            // 302 = 登录成功（SSO 用重定向表达成功）
            if (response.code != 302) {
                val body = response.body?.string().orEmpty()
                val errorMsg = ERROR_MSG_RE.find(body)?.groupValues?.getOrNull(1)?.trim()

                // ⚠️ 只有「服务端确实处理了这次登录并拒绝」才归类为**凭据被拒**。
                // 判据是 HTTP 200 + 页面里能抠到错误文案 —— SSO 拒绝凭据时就是
                // 返回 200 并渲染错误提示。
                //
                // 5xx / 429 / 403 属于基础设施故障，必须归为普通 ScnuLoginException：
                // 否则一次服务端抖动就会被上层记成「用户连续输错」，
                // 还可能把用户已保存的密码当成"已失效"删掉。
                if (response.code == 200 && errorMsg != null) {
                    throw ScnuInvalidCredentialException("登录失败: $errorMsg")
                }
                throw ScnuLoginException("登录失败: ${errorMsg ?: "HTTP ${response.code}"}")
            }
        }

        // ══ 阶段 C: 主路径 —— auth.html OAuth code 授权 ══
        runCatching {
            val redirectUrl = URLEncoder.encode("$JWXT_BASE/sso/oauthLogin", "UTF-8")
            val authUrl = "$SSO_SERVICE/openapi/auth.html" +
                    "?client_id=$CLIENT_ID" +
                    "&response_type=code" +
                    "&redirect_url=$redirectUrl"
            val authBody = get(authUrl, referer = action)

            // 页面含 gotoApp 说明需要再跳一层（教务用 JS 拼跳转地址）
            if (authBody.contains("gotoApp")) {
                val target = GOTO_URL_RE.find(authBody)?.groupValues?.getOrNull(1)
                    ?: "$JWXT_BASE/sso/oauthLogin"
                get(target, referer = authUrl)
            }
        }

        // ══ 阶段 D: 兜底路径 —— fastlogin（app_id）══
        // 即使阶段 C 已建立会话，此处重复调用也无副作用；
        // 反之若阶段 C 失效，本路径可独立完成授权。
        runCatching {
            val redirectUrl = URLEncoder.encode("$JWXT_BASE/sso/oauthLogin", "UTF-8")
            val fastloginUrl =
                "$SSO_SERVICE/openapi/fastlogin.html?app_id=$APP_ID&redirect_url=$redirectUrl"
            // 手动处理 302：先取 Location，再显式请求一次
            val fastClient = httpClient.newBuilder().followRedirects(false).build()
            fastClient.newCall(Request.Builder().url(fastloginUrl).build()).execute().use { response ->
                if (response.code == 302) {
                    val location = response.header("Location")
                    if (!location.isNullOrBlank()) {
                        val absolute = URI("$SSO_BASE/").resolve(location).toString()
                        runCatching { get(absolute) }
                    }
                }
            }
        }

        // ══ 阶段 E: 校验 —— 确认教务会话真的落地 ══
        // 302 只证明"凭据正确"，不证明"教务系统已认可该会话"。
        // 教务域名上出现未过期的 Cookie，才是会话建立的直接证据。
        // 这一步是从原 ScnuScraper.login() 的 URL 校验（"jwxt" in finalUrl）迁移过来的，
        // 合并时不能丢 —— 否则同步链路会在会话未建立的情况下继续发请求，
        // 最终表现为一堆难排查的"JSON 解析失败"。
        val jar = httpClient.cookieJar as? ScnuCookieJar
        if (jar != null && !jar.hasValidCookiesFor(JWXT_HOST)) {
            throw ScnuLoginException("登录未进入教务系统，请稍后重试")
        }

        true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部 HTTP 工具
    // ═══════════════════════════════════════════════════════════════════════

    /** GET 请求并返回响应体文本，对应脚本 `_request()` + `_text()` */
    private fun get(url: String, referer: String? = null): String {
        val builder = Request.Builder().url(url)
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw ScnuLoginException("GET $url 失败: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }
}
