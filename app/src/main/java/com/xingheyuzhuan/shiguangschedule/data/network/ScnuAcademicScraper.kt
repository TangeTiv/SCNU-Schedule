package com.xingheyuzhuan.shiguangschedule.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Year
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════
// 「学业情况」抓取结果模型
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 培养计划同步结果。
 *
 * @param nodes 完整计划点树（树序）
 * @param coursesByNode 计划点 id → 该点下的课程明细
 * @param nodeNameById 计划点 id → 名称，用于把失败项翻译成人能看懂的名字
 * @param failedNodes 抓取失败的 (计划点 id, 原因)，逐项容错不中断整体
 */
data class AcademicSyncResult(
    val nodes: List<PlanNodeDto>,
    val coursesByNode: Map<String, List<AcademicCourseDto>>,
    val nodeNameById: Map<String, String>,
    val failedNodes: List<Pair<String, String>>
) {
    /** 抓到的课程总条数（未去重）。 */
    val rawCourseCount: Int get() = coursesByNode.values.sumOf { it.size }
}

/**
 * 第二类课（非正式学时）同步结果。
 *
 * @param courses 全部记录
 * @param failedTerms 请求失败的 (学年, 学期码, 原因)
 * @param scannedTerms 实际扫描过的学期，供日志与「扫描范围」说明使用
 */
data class NonFormalSyncResult(
    val courses: List<NonFormalCourseDto>,
    val failedTerms: List<Triple<String, String, String>>,
    val scannedTerms: List<Pair<String, String>>
)

/**
 * 华南师范大学教务系统「学业情况」数据抓取器。
 *
 * 覆盖两个**互相独立**的教务模块：
 *
 * | 数据 | 模块号 | 接口 |
 * |---|---|---|
 * | 培养计划点树 | `N105515` | `GET /xsxy/xsxyqk_cxXsxyqkIndex.html` |
 * | 计划点课程明细 | `N105515` | `POST /xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html` |
 * | 非正式学时 | `N305012` | `POST /cjcx/cjcx_cxFzskXscj.html?doType=query` |
 *
 * ## 会话来源
 *
 * 本类**不自己登录**，复用 [ScnuScraper.login] 建立的会话 —— 两者注入的是
 * 同一个 `@Named("scnu")` [OkHttpClient] 与 `ScnuCookieJar`。
 * 因此调用方必须先 `scraper.login(account, password)`，再调用本类的方法。
 *
 * ## 逐项容错（关键设计）
 *
 * 培养计划要遍历 33 个节点，非正式学时最多要扫 6~8 个学期。任何一项失败都
 * **不能让已抓到的数据丢失**：每个节点/学期独立 `runCatching`，
 * 失败项收集进结果对象，最后整体落库并如实反馈失败数量。
 *
 * ## 只读性
 *
 * 本类所有接口都是**只读查询**，不会修改任何教务数据。
 * 请求通过 [RetryInterceptor.RETRY_HEADER] 显式标注为可重试。
 */
@Singleton
class ScnuAcademicScraper @Inject constructor(
    @Named("scnu") private val httpClient: OkHttpClient,
    @Named("scnu") private val json: Json
) {

    companion object {
        private const val TAG = "ScnuAcademic"

        private const val JWXT_BASE = "https://jwxt.scnu.edu.cn"

        /** 学业情况模块号（培养计划 + 学分完成度） */
        const val GNMKDM_ACADEMIC = "N105515"

        /** 第二类课成绩模块号 */
        const val GNMKDM_NON_FORMAL = "N305012"

        private const val PAGE_ACADEMIC =
            "$JWXT_BASE/xsxy/xsxyqk_cxXsxyqkIndex.html?gnmkdm=$GNMKDM_ACADEMIC&layout=default"

        private const val PATH_POINT_COURSES = "/xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html"

        /**
         * 计划**外**课程接口。
         *
         * 与 [PATH_POINT_COURSES] 用**同一组 3 个参数**、同一个 `xfyqjd_id`，
         * 但返回「计划外」的课程。两个接口都必须查，否则会漏课。
         *
         * ⚠️ 必须**逐点传真实 id**，不能传空 `xfyqjd_id`：
         * 实测传空只返回 2 条，逐点传真实 id 能返回 52 条。
         * 最典型的是「通识选修」——`Kcxx` 返回 `[]`，课程全在 `FKcxx` 里。
         */
        private const val PATH_POINT_COURSES_EXTRA = "/xsxy/xsxyqk_cxJxzxjhxfyqFKcxx.html"

        private const val PATH_NON_FORMAL = "/cjcx/cjcx_cxFzskXscj.html"

        /** 学期编码（实测反直觉）：3 = 第一学期，12 = 第二学期。 */
        val TERM_CODES = listOf("3" to "1", "12" to "2")

        /** 非正式学时每页条数（教务默认 15，最大可到 5000）。 */
        private const val NON_FORMAL_PAGE_SIZE = 100

        /** 单学期最多翻多少页，防止教务 totalResult 异常导致死循环。 */
        private const val NON_FORMAL_MAX_PAGES = 50

        /** 首页 HTML 的合理长度下限；低于此值说明拿到的是登录页或错误页。 */
        private const val MIN_INDEX_HTML_LENGTH = 10_000

        /**
         * 从学号推导入学年。
         *
         * 学号形如 `202421315041`，前 4 位即入学年。这是华南师大学号的固定规则，
         * 比硬编码学年区间可靠得多。
         *
         * @return 入学年；学号格式不符时回退到「当前年 - 4」
         */
        fun inferEnrollmentYear(studentId: String): Int {
            val currentYear = Year.now().value
            val prefix = studentId.trim().take(4)
            val parsed = prefix.toIntOrNull()
            return if (parsed != null && parsed in 1990..currentYear + 1) {
                parsed
            } else {
                currentYear - 4
            }
        }

        /**
         * 计算需要扫描的学年区间。
         *
         * 起点 = 学号推导出的入学年；终点 = 当前自然年 + 1，
         * 多扫一年是为了覆盖「下一学年已提前录入」的数据。
         *
         * @return 学年列表（升序），元素即接口的 `xnm` 参数（学年起始年）
         */
        fun academicYearsToScan(studentId: String): List<Int> {
            val start = inferEnrollmentYear(studentId)
            val end = Year.now().value + 1
            if (end < start) return listOf(start)
            return (start..end).toList()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 培养计划
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 抓取完整的培养计划与课程明细。
     *
     * 流程：
     * 1. 拉取首页 HTML，用 [AcademicPlanTreeParser] 还原 33 节点树
     * 2. 遍历每个节点，拉取其课程明细
     *
     * @param studentId 学号（课程明细接口需要）
     * @param onProgress 进度回调 (已完成, 总数, 当前节点名)，运行在 [Dispatchers.IO]
     * @throws SessionExpiredException 首页拿不到有效内容，说明会话已失效
     */
    suspend fun fetchAcademicPlan(
        studentId: String,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit = { _, _, _ -> }
    ): AcademicSyncResult = withContext(Dispatchers.IO) {

        // ── 第 1 步：首页 → 计划点树 ──
        val html = getText(PAGE_ACADEMIC)
        if (html.length < MIN_INDEX_HTML_LENGTH || isLoginPage(html)) {
            throw SessionExpiredException(
                "教务会话已失效（学业情况首页返回 ${html.length} 字符），请重新同步"
            )
        }

        val nodes = AcademicPlanTreeParser.parse(html)
        if (nodes.isEmpty()) {
            throw SessionExpiredException(
                "未能从学业情况首页解析出培养计划，教务页面结构可能已变更"
            )
        }

        val nodeNameById = nodes.associate { it.id to it.name }

        // ── 第 2 步：逐节点拉课程（逐项容错）──
        val coursesByNode = LinkedHashMap<String, List<AcademicCourseDto>>(nodes.size)
        val failed = mutableListOf<Pair<String, String>>()

        nodes.forEachIndexed { index, node ->
            onProgress(index, nodes.size, node.name)
            runCatching {
                fetchPointCourses(node.id, studentId)
            }.onSuccess { list ->
                coursesByNode[node.id] = list
                // 诊断日志：确认每个计划点到底抓到几门课。
                // 排查「界面显示暂无课程明细」时，先看这里有没有非零数字：
                // - 全部为 0 → 抓取/会话问题（接口返回空数组）
                // - 有非零数字但界面仍为空 → 落库或节点 id 映射问题
                Log.i(TAG, "计划点「${node.name}」(${node.id}) → ${list.size} 门")
            }.onFailure { e ->
                // 单个节点失败只记录，不中断整轮同步
                val reason = e.message ?: e::class.java.simpleName
                failed += node.id to reason
                coursesByNode[node.id] = emptyList()
                Log.w(TAG, "计划点「${node.name}」抓取失败: $reason")
            }
        }
        onProgress(nodes.size, nodes.size, "")

        Log.i(
            TAG,
            "培养计划抓取完成：节点 ${nodes.size} 个，课程共 ${coursesByNode.values.sumOf { it.size }} 门，" +
                    "失败 ${failed.size} 项"
        )

        AcademicSyncResult(
            nodes = nodes,
            coursesByNode = coursesByNode,
            nodeNameById = nodeNameById,
            failedNodes = failed
        )
    }

    /**
     * 抓取某个培养计划点下的课程明细，**计划内 + 计划外两个接口都查**。
     *
     * 两个接口用**同一组 3 个参数**、同一个 `xfyqjd_id`，必须都查否则会漏课：
     *
     * | 接口 | 含义 | 实测 |
     * |---|---|---|
     * | `Kcxx` | 培养方案内的课 | 大多数叶子节点有数据 |
     * | `FKcxx` | 方案外的课 | **部分节点的课只在这里！** |
     *
     * 最典型的是「通识选修」：`Kcxx` 返回 `[]`，而课程（博弈策略思维、
     * 创新创业之创意技术）**只在 `FKcxx` 里**。该节点要求 6 学分 / 已获 4 学分，
     * 只查 Kcxx 就会看到「有学分但没课」，被误判成数据缺失。
     *
     * ⚠️ `FKcxx` 必须**逐点传真实 id**：实测传空 `xfyqjd_id` 只返回 2 条，
     * 逐点传真实 id 能返回 52 条。
     *
     * 合并规则：按 `课程号 + 成绩学年 + 学期 + 成绩` 去重，
     * 只出现在 `FKcxx` 的记录标记为计划外。
     *
     * ## 两次请求相互独立容错
     *
     * `FKcxx` 失败**不会**影响已拿到的 `Kcxx` 结果 —— 先收下计划内的，
     * 再尝试追加计划外的。两个都空才算这个节点真的没课。
     *
     * @param nodeId 计划点 id（`xfyqjd_id`）
     * @param studentId 学号（`xh_id`）
     */
    suspend fun fetchPointCourses(
        nodeId: String,
        studentId: String
    ): List<AcademicCourseDto> = withContext(Dispatchers.IO) {
        // ── 计划内课程（失败直接抛出，由调用方计为一个失败项）──
        val inPlan = fetchCoursesFrom(PATH_POINT_COURSES, nodeId, studentId)

        // ── 计划外课程（失败只记日志，不影响计划内结果）──
        val extra = runCatching {
            fetchCoursesFrom(PATH_POINT_COURSES_EXTRA, nodeId, studentId)
        }.onFailure { e ->
            Log.w(TAG, "计划外课程接口失败（已忽略）: ${e.message}")
        }.getOrDefault(emptyList())

        AcademicCourseMerger.merge(inPlan, extra)
    }

    /**
     * 向指定接口拉某个计划点的课程，逐条解码容错。
     *
     * ## 逐元素容错（重要）
     *
     * 教务对「看起来像数字」的字段会返回不稳定类型（见 [AcademicCourseDto] 的说明）。
     * 若整段数组一次性解码，**一条记录的一个字段类型不对，整个计划点的课程就全丢**
     * —— 实测 `JD` 返回裸数字 `3.4` 时，9 个计划点、91 门课全部丢失，
     * 界面上表现为「暂无课程明细」。
     *
     * 因此这里**逐条解码**：坏记录只丢它自己，其余照常返回。
     * 这条防线与 [LenientStringSerializer] 互补 —— 前者处理已知的类型差异，
     * 后者兜住未知的新差异。
     */
    private fun fetchCoursesFrom(
        path: String,
        nodeId: String,
        studentId: String
    ): List<AcademicCourseDto> {
        val url = "$JWXT_BASE$path?gnmkdm=$GNMKDM_ACADEMIC"
        val body = FormBody.Builder()
            // 三个参数实测如此；fromXh_id 固定空串
            .add("fromXh_id", "")
            .add("xfyqjd_id", nodeId)
            .add("xh_id", studentId)
            .build()

        val text = postText(url, body, referer = PAGE_ACADEMIC)
        if (text.isBlank()) return emptyList()

        val trimmed = text.trim()
        // 教务在参数异常时会回 HTML 而不是 JSON
        if (!trimmed.startsWith("[")) {
            throw IllegalStateException(
                "课程明细返回的不是 JSON 数组: ${trimmed.take(120)}"
            )
        }

        val array = runCatching {
            json.parseToJsonElement(trimmed).jsonArray
        }.getOrElse { e ->
            throw IllegalStateException("课程明细 JSON 解析失败: ${e.message}", e)
        }

        val out = ArrayList<AcademicCourseDto>(array.size)
        array.forEachIndexed { index, element ->
            runCatching {
                json.decodeFromJsonElement<AcademicCourseDto>(element)
            }.onSuccess { dto ->
                out += dto
            }.onFailure { e ->
                // 单条坏记录只丢自己，不连累同计划点的其它课程
                Log.w(
                    TAG,
                    "课程明细第 ${index + 1} 条解析失败（已跳过）: ${e.message}"
                )
            }
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第二类课（非正式学时）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 扫描全部学年学期，抓取第二类课（非正式学时）记录。
     *
     * 学年区间由 [academicYearsToScan] 依据学号推导，避免硬编码。
     * 每个学期独立容错：某学期失败时其余学期数据照常返回。
     *
     * 查无数据的学期教务返回 `items: []` 且 `totalResult: 0`，**不是错误**。
     *
     * @param studentId 学号
     * @param onProgress 进度回调 (已完成, 总数, 当前描述)，运行在 [Dispatchers.IO]
     */
    suspend fun fetchNonFormalCourses(
        studentId: String,
        onProgress: (done: Int, total: Int, currentLabel: String) -> Unit = { _, _, _ -> }
    ): NonFormalSyncResult = withContext(Dispatchers.IO) {

        val years = academicYearsToScan(studentId)
        // 展开成 (学年, 学期码, 学期名) 列表
        val terms = years.flatMap { year ->
            TERM_CODES.map { (code, name) -> Triple(year.toString(), code, name) }
        }

        val collected = mutableListOf<NonFormalCourseDto>()
        val failed = mutableListOf<Triple<String, String, String>>()
        val scanned = mutableListOf<Pair<String, String>>()

        terms.forEachIndexed { index, (year, code, name) ->
            val label = "$year-${year.toInt() + 1} 学年第${name}学期"
            onProgress(index, terms.size, label)

            runCatching {
                queryNonFormalByTerm(year, code)
            }.onSuccess { list ->
                collected += list
                scanned += year to code
            }.onFailure { e ->
                val reason = e.message ?: e::class.java.simpleName
                failed += Triple(year, code, reason)
                Log.w(TAG, "$label 抓取失败: $reason")
            }
        }
        onProgress(terms.size, terms.size, "")

        NonFormalSyncResult(
            courses = collected,
            failedTerms = failed,
            scannedTerms = scanned
        )
    }

    /**
     * 查询单个学年学期的第二类课，自动翻页。
     *
     * @param year 学年起始年，如 `"2024"` 表示 2024-2025 学年
     * @param termCode 学期编码（`"3"` 第一学期 / `"12"` 第二学期）
     */
    private suspend fun queryNonFormalByTerm(
        year: String,
        termCode: String
    ): List<NonFormalCourseDto> {
        val url = "$JWXT_BASE$PATH_NON_FORMAL?doType=query&gnmkdm=$GNMKDM_NON_FORMAL"
        val referer = "$JWXT_BASE$PATH_NON_FORMAL?gnmkdm=$GNMKDM_NON_FORMAL&layout=default"

        val out = mutableListOf<NonFormalCourseDto>()
        var page = 1
        var total = -1

        while (page <= NON_FORMAL_MAX_PAGES) {
            val body = FormBody.Builder()
                .add("xnm", year)
                .add("xqm", termCode)
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", NON_FORMAL_PAGE_SIZE.toString())
                .add("queryModel.currentPage", page.toString())
                .add("queryModel.sortName", " ")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()

            val text = postText(url, body, referer = referer)
            if (text.isBlank()) break

            val response = runCatching {
                json.decodeFromString<NonFormalCourseResponse>(text.trim())
            }.getOrElse { e ->
                throw IllegalStateException("第二类课 JSON 解析失败: ${e.message}", e)
            }

            if (response.items.isEmpty()) break

            out += response.items
            if (total < 0) total = response.totalResult

            // 已收齐 或 本页不足一页 → 结束
            if (total in 1..out.size) break
            if (response.items.size < NON_FORMAL_PAGE_SIZE) break
            page++
        }

        return out
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP 工具
    // ─────────────────────────────────────────────────────────────────────

    /** GET 文本，带教务要求的 Referer 与只读重试标记。 */
    private fun getText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header(RetryInterceptor.RETRY_HEADER, "1")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GET $url 失败: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    /**
     * POST 表单并返回文本。
     *
     * 固定带上 `X-Requested-With: XMLHttpRequest` —— 正方教务靠它判断 AJAX 请求，
     * 缺失时会返回登录页 HTML 而不是数据。`Origin` 也一并带上（实测要求）。
     */
    private fun postText(
        url: String,
        body: FormBody,
        referer: String
    ): String {
        val request = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .header("Origin", JWXT_BASE)
            .header(RetryInterceptor.RETRY_HEADER, "1")
            .post(body)
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("POST $url 失败: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    /** 粗略判断响应是否为登录页（会话失效时教务会 200 返回登录页）。 */
    private fun isLoginPage(html: String): Boolean {
        val head = html.take(4000)
        return head.contains("login_slogin") ||
                head.contains("统一身份认证") ||
                head.contains("user/login.html")
    }
}

/**
 * 教务会话已失效。
 *
 * 独立成类型是为了让上层能把「需要重新登录」与「网络故障」区分开，
 * 从而给出不同的用户提示。
 */
class SessionExpiredException(message: String) : Exception(message)
