package com.xingheyuzhuan.shiguangschedule.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 华南师范大学教务系统后台数据抓取器。
 *
 * 严格 1:1 翻译自 Python 验证代码 `scnu_grade_fetcher.py` 中的 Fetcher 类。
 * 通过 OkHttp + 内存 CookieJar 实现 SSO 登录 → Session 保持 → API 调用的完整流程。
 *
 * 用法：
 * ```kotlin
 * val success = scraper.login(account, password)
 * if (success) {
 *     val grades = scraper.fetchGrades()
 *     val exams = scraper.fetchExams()
 * }
 * ```
 */
@Singleton
class ScnuScraper @Inject constructor(
    @Named("scnu") private val httpClient: OkHttpClient,
    @Named("scnu") private val json: Json
) {

    companion object {
        // ── URL 常量 ──
        private const val SSO_BASE = "https://sso.scnu.edu.cn"
        private const val SSO_SERVICE = "$SSO_BASE/AccountService"
        private const val JWXT_BASE = "https://jwxt.scnu.edu.cn"

        // ── 正则（匹配 Python 逻辑）──

        /** 提取 <form action="...">，对应 Python: r'<form[^>]*action=["\']([^"\']*)["\']' */
        private val FORM_ACTION_RE = Regex(
            """<form[^>]*action\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        )

        /** 提取页面错误信息，对应 Python: r'(?:alert|error|msgtext)[^>]*>([^<]+)' */
        private val ERROR_MSG_RE = Regex(
            """(?:alert|error|msgtext)[^>]*>\s*([^<]+?)\s*<""",
            RegexOption.IGNORE_CASE
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共 API
    // ═══════════════════════════════════════════════════════════════

    /**
     * SSO 登录，对应 Python `_login(sid, pw)`。
     *
     * - 阶段 A: GET 登录页 → 提取表单 action
     * - 阶段 B: POST 凭据，使用临时 followRedirects(false) 客户端检测 302
     * - 阶段 C: GET fastlogin → 建立 JWXT 会话
     *
     * @param account 学号
     * @param password 密码
     * @return true 登录成功
     * @throws Exception 携带从页面提取的错误信息（如"账号或密码错误"）或网络错误描述
     */
    suspend fun login(account: String, password: String): Boolean = withContext(Dispatchers.IO) {
        // ── 阶段 A: GET 登录页，提取 <form action> ──
        val loginPageUrl = "$SSO_SERVICE/user/login.html"
        val html = get(loginPageUrl)
        // 默认 action = 登录页自身（匹配 Python 兜底逻辑）
        var action = loginPageUrl
        FORM_ACTION_RE.find(html)?.groupValues?.getOrNull(1)?.let { extracted ->
            action = if (extracted.startsWith("http")) {
                extracted
            } else {
                // 相对路径 → 解析为绝对 URL（匹配 Python urljoin）
                URI(loginPageUrl).resolve(extracted).toString()
            }
        }

        // ── 阶段 B: POST 凭据，禁止跟随重定向以检测 302 ──
        // 使用 httpClient.newBuilder() 派生临时客户端，完全共享 CookieJar 和连接池
        val noRedirectClient = httpClient.newBuilder().followRedirects(false).build()
        try {
            val formBody = FormBody.Builder()
                .add("account", account)
                .add("password", password)
                .add("rancode", "")
                .build()
            val postRequest = Request.Builder()
                .url(action)
                .header("Referer", action)
                .header("Origin", "https://sso.scnu.edu.cn")
                .post(formBody)
                .build()
            val response = noRedirectClient.newCall(postRequest).execute()
            response.use {
                if (it.code != 302) {
                    val body = it.body?.string().orEmpty()
                    val errorMsg = ERROR_MSG_RE.find(body)?.groupValues?.getOrNull(1)?.trim()
                    throw Exception(
                        "登录失败: ${errorMsg ?: "HTTP ${it.code}"}"
                    )
                }
            }
        } finally {
            // 临时客户端共享连接池，无需额外释放资源
        }

        // ── 阶段 C: fastlogin 授权 → 建立 JWXT 会话 ──
        val redirectUrl = URLEncoder.encode("$JWXT_BASE/sso/oauthLogin", "UTF-8")
        val fastloginUrl =
            "$SSO_SERVICE/openapi/fastlogin.html?app_id=96&redirect_url=$redirectUrl"
        val fastloginResponse = getResponse(fastloginUrl)
        val finalUrl = fastloginResponse.request.url.toString()
        if ("jwxt" !in finalUrl) {
            throw Exception("fastlogin 未进入教务系统，当前 URL: $finalUrl")
        }

        true
    }

    /**
     * 获取成绩列表，对应 Python `fetch_grades()`。
     * 必须在 [login] 成功后调用（Session Cookie 已建立）。
     *
     * @param xnm 学年（空字符串表示全部）
     * @param xqm 学期（空字符串表示全部）
     * @return 成绩列表（可能为空）
     * @throws Exception 网络错误或 JSON 解析失败
     */
    suspend fun fetchGrades(xnm: String = "", xqm: String = ""): List<GradeItem> =
        withContext(Dispatchers.IO) {
            val apiUrl = "$JWXT_BASE/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"
            val formBody = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "500")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()
            val request = Request.Builder()
                .url(apiUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header(
                    "Referer",
                    "$JWXT_BASE/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"
                )
                .post(formBody)
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("成绩 API 请求失败: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            json.decodeFromString<GradeResponse>(body).items
        }

    /**
     * 获取考试安排列表，对应 Python `fetch_exams()`。
     * 必须在 [login] 成功后调用。
     *
     * @param xnm 学年（空字符串表示全部）
     * @param xqm 学期（空字符串表示全部）
     * @return 考试安排列表（可能为空）
     * @throws Exception 网络错误或 JSON 解析失败
     */
    suspend fun fetchExams(xnm: String = "", xqm: String = ""): List<ExamItem> =
        withContext(Dispatchers.IO) {
            val apiUrl = "$JWXT_BASE/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N305005"
            val formBody = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "100")
                .add("queryModel.currentPage", "1")
                .build()
            val request = Request.Builder()
                .url(apiUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$JWXT_BASE/")
                .post(formBody)
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("考试 API 请求失败: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            json.decodeFromString<ExamResponse>(body).items
        }

    /**
     * 获取课程表列表，对应 Python `fetch_schedule()`。
     * 必须在 [login] 成功后调用。
     *
     * @param xnm 学年（空字符串表示当前学年）
     * @param xqm 学期（空字符串表示当前学期）
     * @return 课程列表（可能为空）
     * @throws Exception 网络错误或 JSON 解析失败
     */
    suspend fun fetchCourses(xnm: String = "", xqm: String = ""): List<CourseItem> =
        withContext(Dispatchers.IO) {
            val apiUrl = "$JWXT_BASE/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
            val formBody = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("kzlx", "ck")
                .add("xsdm", "")
                .add("kclbdm", "")
                .add("kclxdm", "")
                .build()
            val request = Request.Builder()
                .url(apiUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "$JWXT_BASE/")
                .post(formBody)
                .build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("课程表 API 请求失败: HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
            // 防线 3: kbList 空安全 — CourseResponse.items 默认为 emptyList()，
            // 即使后端返回空 JSON 也不会抛 NPE
            if (body.isBlank()) return@withContext emptyList()
            try {
                json.decodeFromString<CourseResponse>(body).items
            } catch (e: Exception) {
                throw Exception("课程表 JSON 解析失败: ${e.message}")
            }
        }

    // ═══════════════════════════════════════════════════════════════
    // 内部 HTTP 工具（匹配 Python _get / _post 行为）
    // ═══════════════════════════════════════════════════════════════

    /** GET 请求返回响应体字符串，对应 Python `_get()` */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("GET $url 失败: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    /** GET 请求返回完整 Response（用于检查最终 URL），对应 Python 中需要访问 r.url 的场景 */
    private fun getResponse(url: String): okhttp3.Response {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute()
    }
}
