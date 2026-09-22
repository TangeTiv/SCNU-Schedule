package com.xingheyuzhuan.shiguangschedule.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 华南师范大学教务系统后台数据抓取器。
 *
 * 严格 1:1 翻译自 Python 验证代码 `scnu_grade_fetcher.py` 中的 Fetcher 类。
 * 通过 OkHttp + 内存 CookieJar 实现 SSO 登录 → Session 保持 → API 调用的完整流程。
 *
 * ## 职责边界（v1.7.0 起）
 *
 * 本类**只负责抓取**，登录已迁出到
 * [com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuSsoLogin]，
 * 由 [com.xingheyuzhuan.shiguangschedule.data.auth.ScnuAuthManager] 统一编排。
 *
 * 之所以能这样切：本类与登录实现共用同一个 `@Named("scnu")` OkHttpClient 与
 * CookieJar，**会话天然共享** —— 别处登录成功后，本类的方法直接就能用。
 *
 * 用法：
 * ```kotlin
 * val result = authManager.ensureSession()      // 或显式 authManager.login(...)
 * if (result is SessionResult.Active) {
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
        private const val JWXT_BASE = "https://jwxt.scnu.edu.cn"
    }

    // ═══════════════════════════════════════════════════════════════
    // 公共 API（仅抓取；登录见 ScnuSsoLogin / ScnuAuthManager）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取成绩列表，对应 Python `fetch_grades()`。
     * 必须在会话已建立后调用（见 `ScnuAuthManager.ensureSession()`）。
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
     * 必须在会话已建立后调用（见 `ScnuAuthManager.ensureSession()`）。
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
     * 必须在会话已建立后调用（见 `ScnuAuthManager.ensureSession()`）。
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
    // 内部 HTTP 工具
    // ═══════════════════════════════════════════════════════════════
}