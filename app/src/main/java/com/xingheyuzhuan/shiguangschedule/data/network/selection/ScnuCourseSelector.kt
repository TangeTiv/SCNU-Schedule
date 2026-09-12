package com.xingheyuzhuan.shiguangschedule.data.network.selection

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named

/** 选课模块日志标签，用于定位教务接口契约不符（如 flag=0） */
private const val TAG = "ScnuCourseSelector"

/**
 * 选课业务异常（网络失败、会话失效、参数缺失）。
 *
 * **只有"结果未知/无法继续"的情况才抛本异常**；教务返回的业务失败
 * （名额已满等）通过 [SelectionOutcome] 返回，两者语义严格区分。
 */
class CourseSelectionException(message: String) : Exception(message)

/**
 * 华南师范大学**自主选课**客户端。
 *
 * 严格 1:1 翻译自 `scnu_course_selector.py` 的 `ScnuCourseSelector` 类。
 *
 * ## 接口清单（全部位于教务的 xsxk 选课域）
 *
 * 注意：本行原本写作反引号包裹的 `xsxk` 通配路径，但其中
 * 斜杠加星号的组合会被 Kotlin 当作**嵌套块注释**的开始，导致整个文件
 * 被吞进注释而报 "Unclosed comment"。故此处刻意不写出该星号。
 *
 * | 接口 | 用途 |
 * |---|---|
 * | `zzxkyzb_cxZzxkYzbPartDisplay.html` | 课程(教学班)列表 + 客户端窗口分页 |
 * | `zzxkyzbjk_cxJxbWithKchZzxkYzb.html` | 课程下教学班详情（含 `do_jxb_id`） |
 * | `zzxkyzbjk_xkBcZyZzxkYzb.html` | 选课提交 |
 * | `zzxkyzb_tuikBcZzxkYzb.html` | 退选 |
 * | `zzxkyzb_cxZzxkYzbChoosedDisplay.html` | 已选课程（**权威来源**） |
 * | `zzxkyzb_xkZyDisplayZzxkYzbZjxb.html` | 子课程（教学班组成） |
 * | `kbcx/xskbcx_cxXsgrkb.html` | 课表（仅用于展示每周安排） |
 *
 * ## 有状态设计
 *
 * 本类持有 [context]（选课页 hidden 上下文）与 [categories]（类别 Tab），
 * 二者都由 [refreshContext] 从页面抓取。**这是刻意与无状态的
 * `ScnuScraper` 分开的原因** —— 选课接口的全部精准度依赖这份动态上下文。
 *
 * 因此本类**以非单例方式注入**（每个 ViewModel 一个实例），避免多个使用方
 * 互相覆盖上下文；而底层 `@Named("scnu")` 的 OkHttpClient / CookieJar 仍是
 * 单例，所以登录会话在模块间共享。
 *
 * > 注：本类通过 `@Inject` 构造函数 + Hilt 的**隐式绑定**获得依赖，
 * > `NetworkModule` 中**不需要**（也刻意没有）对应的 `@Provides`。
 * > 隐式绑定默认是 unscoped 的，正好保证"每次注入都是新实例"。
 * > 若将来有人给它加上 `@Singleton`，会立即引入跨页面上下文污染。
 *
 * ## 阻塞式 I/O 约束
 *
 * 所有公开方法均在 [Dispatchers.IO] 上执行，**绝不可在主线程调用**。
 */
class ScnuCourseSelector @Inject constructor(
    @Named("scnu") private val httpClient: OkHttpClient,
    @Named("scnu") private val json: Json
) {

    companion object {
        private const val JWXT = "https://jwxt.scnu.edu.cn"

        /** 选课首页（同时是大多数接口的 Referer） */
        private const val INDEX_URL =
            "$JWXT/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=N253512&layout=default"

        /** 所有选课接口共用的 gnmkdm 功能模块号 */
        private const val GNMKDM = "N253512"

        // ── 接口路径 ──
        private const val P_LIST = "/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html"
        private const val P_CLASSES = "/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html"
        private const val P_SELECT = "/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html"
        private const val P_DROP = "/xsxk/zzxkyzb_tuikBcZzxkYzb.html"
        private const val P_KB = "/kbcx/xskbcx_cxXsgrkb.html"
        private const val P_ENROLLED = "/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html"
        private const val P_SUB = "/xsxk/zzxkyzb_xkZyDisplayZzxkYzbZjxb.html"

        /**
         * 服务器每批推送条数（客户端 kcrow 窗口大小）。
         *
         * 教务并非标准服务端分页：请求窗口 `ks..js` 后，服务器可能推送超出该窗口的
         * 行，客户端必须用 `kcrow` 切出真正属于本批的行。故本值同时是
         * "请求窗口宽度"与"批次内行号上界"。
         */
        const val PAGE_SIZE = 10

        /** 批次间隔限速（毫秒）。高并发期间避免因请求过密被教务限流。 */
        private const val BATCH_THROTTLE_MS = 200L

        // ── 参数白名单（来自脚本，勿随意增删）──

        /** 课程列表 / 教学班详情共用的"学生与规则"上下文 */
        private val CTX_KEYS = listOf(
            "rwlx", "xklc", "xkly", "bklx_id", "sfkkjyxdxnxq", "kzkcgs", "xqh_id", "jg_id",
            "njdm_id_1", "zyh_id_1", "gnjkxdnj", "zyh_id", "zyfx_id", "njdm_id", "bh_id",
            "bjgkczxbbjwcx", "xbm", "xslbdm", "mzm", "xz", "ccdm", "xsbj", "sfkknj", "sfkkzy",
            "kzybkxy", "sfznkx", "zdkxms", "sfkxq", "bhbcyxkjxb", "sfkcfx", "kkbk", "kkbkdj",
            "bklbkcj", "sfkgbcx", "sfrxtgkcxd", "tykczgxdcs", "xkxnm", "xkxqm"
        )

        /** 课程列表额外参数 */
        private val LIST_KEYS = CTX_KEYS + listOf(
            "kklxdm", "bbhzxjxb", "zxgbxkkg", "xkkz_id", "rlkz", "xkzgbj",
            "kspage", "jspage", "jxbzb"
        )

        /** 教学班详情参数（脚本中的 `jxbzxskg_placeholder` 占位已在源里剔除） */
        private val CLASS_KEYS = listOf(
            "rwlx", "xkly", "bklx_id", "sfkkjyxdxnxq", "kzkcgs", "xqh_id", "jg_id", "zyh_id",
            "zyfx_id", "txbsfrl", "njdm_id", "bh_id", "xbm", "xslbdm", "mzm", "xz", "ccdm",
            "xsbj", "sfkknj", "gnjkxdnj", "sfkkzy", "kzybkxy", "sfznkx", "zdkxms", "sfkxq",
            "bhbcyxkjxb", "sfkcfx", "bbhzxjxb", "kkbk", "kkbkdj", "bklbkcj", "xkxnm", "xkxqm",
            "xkxskcgskg", "rlkz", "cdrlkz", "cxcykclxxskg", "rlzlkz", "kklxdm", "kch_id",
            "jxbzcxskg", "zxgbxkkg", "xklc", "xkkz_id", "cxbj", "fxbj"
        )

        /**
         * 页面上不存在（由 JS 注入）但接口必需、且必须与浏览器一致的字段。
         *
         * **缺失它们服务器不会报错**，而是静默忽略专业/年级筛选，把全校课程都返回
         * —— 这是"查到别的专业的课"的根因。来源：脚本 [DEFAULT_RULE_FLAGS]。
         */
        private val DEFAULT_RULE_FLAGS = mapOf(
            "rwlx" to "1",          // 任务类型：1=主修
            "bklx_id" to "0", "sfkkjyxdxnxq" to "0", "kzkcgs" to "0", "xkly" to "0",
            "gnjkxdnj" to "0", "bjgkczxbbjwcx" to "0", "sfkknj" to "0", "sfkkzy" to "0",
            "kzybkxy" to "0", "sfznkx" to "0", "zdkxms" to "0", "sfkxq" to "0",
            "bhbcyxkjxb" to "0", "sfkcfx" to "0", "kkbk" to "0", "kkbkdj" to "0",
            "bklbkcj" to "0", "sfkgbcx" to "0", "sfrxtgkcxd" to "0", "tykczgxdcs" to "0",
            "bbhzxjxb" to "0", "zxgbxkkg" to "0", "rlkz" to "0", "xkzgbj" to "0"
        )

        /** 选课业务码文案，对应脚本 `_FLAG_MSG` */
        private val FLAG_MSG = mapOf(
            "6" to "该教学班已选中",
            "-1" to "该教学班已无余量，不可选",
            "0" to "非法访问（会话失效或参数校验失败）"
        )

        /** 退选业务码文案，对应脚本 `_DROP_MSG` */
        private val DROP_MSG = mapOf(
            "2" to "退课失败！服务器繁忙！",
            "3" to "退课失败！出现未知异常！",
            "4" to "警告：你正在非法访问！",
            "5" to "校验不通过，请刷新后重试！"
        )

        // ── 正则 ──

        /** 轮次文案，如 "（第5轮）" → 提取 "第5轮" */
        private val ROUND_NAME_RE = Regex("""（(第\s*\d+\s*轮)）""")

        /** 学年学期文案，如 "2024-2025学年 1 学期" */
        private val YEAR_TERM_RE = Regex("""(\d{4}-\d{4})\s*学年\s*(\d)\s*学期""")

        /** 类别 Tab 的 queryCourse JS 调用 */
        private val QUERY_COURSE_RE = Regex(
            """queryCourse\s*\(\s*this\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*,\s*'([^']*)'\s*\)"""
        )

        /** 纯数字提取（用于从"第5轮"得到 "5"） */
        private val DIGITS_RE = Regex("""\d+""")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 会话状态
    // ═══════════════════════════════════════════════════════════════════════

    /** 选课页 hidden 上下文；未登录时为 [SelectionContext.EMPTY] */
    var context: SelectionContext = SelectionContext(emptyMap())
        private set

    /** 可选课程类别（页面 Tab） */
    var categories: List<CourseCategory> = emptyList()
        private set

    private var isLoggedIn = false

    val loggedIn: Boolean get() = isLoggedIn

    // ═══════════════════════════════════════════════════════════════════════
    // 上下文抓取
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 拉取选课首页并缓存全部 hidden 参数与选课类别。
     *
     * 对应脚本 `_refresh_context()`。**这是本模块唯一的"会话有效性验证"** ——
     * 若页面未返回（无任何 hidden 字段），说明会话已失效。
     *
     * @throws CourseSelectionException 选课页未返回，会话可能无效
     */
    suspend fun refreshContext(): SelectionContext = withContext(Dispatchers.IO) {
        val html = get(INDEX_URL)
        val hidden = parseHiddenInputs(html)
        if (hidden.isEmpty()) {
            isLoggedIn = false
            throw CourseSelectionException("选课页未返回，会话可能无效")
        }

        // 学年学期名与轮次名由页面 JS 渲染，需从 HTML 单独提取
        val enriched = hidden.toMutableMap()
        enriched["jg_id"] = enriched["jg_id_1"] ?: enriched["jg_id"] ?: ""

        ROUND_NAME_RE.find(html)?.groupValues?.getOrNull(1)?.trim()?.let {
            enriched["_xklcmc"] = it
        }
        YEAR_TERM_RE.find(html)?.let { m ->
            enriched.putIfAbsent("_xnmc", m.groupValues[1])
            enriched.putIfAbsent("_xqmc", m.groupValues[2])
        }

        context = SelectionContext(enriched)
        categories = parseCategories(html)
        isLoggedIn = true
        context
    }

    /**
     * 从页面 HTML 解析选课类别 Tab。
     *
     * 对应脚本 `re.finditer(queryCourse(...))` 循环 + `_tab_name()`。
     */
    private fun parseCategories(html: String): List<CourseCategory> =
        QUERY_COURSE_RE.findAll(html).map { m ->
            val typeCode = m.groupValues[1]
            val controlId = m.groupValues[2]
            CourseCategory(
                typeCode = typeCode,
                controlId = controlId,
                gradeId = m.groupValues[3],
                majorId = m.groupValues[4],
                displayName = parseCategoryName(html, typeCode, controlId)
            )
        }.toList()

    /**
     * 解析类别 Tab 显示名。
     *
     * 对应脚本 `_tab_name()`：从 `id="tab_kklx_{kklxdm}_{xkkz_id}..."` 元素提取文本，
     * 提取失败时退化为类别代号。
     */
    private fun parseCategoryName(html: String, typeCode: String, controlId: String): String {
        val re = Regex(
            """id=["']tab_kklx_${Regex.escape(typeCode)}_${Regex.escape(controlId)}[^"']*["'][^>]*>([^<]+)"""
        )
        return re.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            ?: typeCode
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 轮次信息
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 当前选课轮次信息。
     *
     * 注意（对应脚本 `round_info()` 的说明）：轮次名称与选课起止时间由页面 JS 渲染，
     * 服务端 HTML 不含该文本，因此这些字段可能为空；**判断能否选课请用
     * [SelectionRoundInfo.isInSelectionWindow]**。
     */
    suspend fun roundInfo(): SelectionRoundInfo = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()
        val c = context
        SelectionRoundInfo(
            yearName = c.yearName,
            termName = c.termName,
            roundId = c.first("xkkz_id", "firstXkkzId"),
            isInSelectionWindow = c.isInSelectionWindow,
            serverTime = c.serverTime,
            startTime = c.startTime,
            endTime = c.endTime,
            maxCredits = c.maxCredits,
            selectedCredits = c.selectedCredits,
            selectedCount = c.selectedCount
        )
    }

    /**
     * 选课轮次序号。
     *
     * 对应脚本 `_round_no()`：页面只有"第5轮"这样的文案，而接口需要纯数字，
     * 故从文案中提取数字；提取失败时回退到上下文里的 `xklc`。
     */
    private fun roundNumber(): String {
        val raw = context.roundName
        DIGITS_RE.find(raw)?.value?.let { return it }
        return context.get("xklc")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 课程查询
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 拉取一批课程（一个客户端窗口）。
     *
     * 复刻脚本 `list_courses()` 循环体：
     * 1. 窗口 `ks = (batch-1) * PER_PAGE + 1`，`js = batch * PER_PAGE`
     * 2. 请求后用 `kcrow`（[SelectableCourse.rankInBatch]）切出属于本窗口的行
     * 3. 服务器可能推送空窗口 → 由调用方依据 [CourseBatch.isEnd] 判定结束
     *
     * @param category 目标类别
     * @param batch 批次号，1 起
     * @param perPage 窗口宽度，默认 [PAGE_SIZE]
     * @return 本批课程；[CourseBatch.isEnd] 表示已到末尾，无需再拉
     */
    suspend fun listCoursesBatch(
        category: CourseCategory,
        batch: Int,
        perPage: Int = PAGE_SIZE
    ): CourseBatch = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()

        val p = batch.coerceAtLeast(1)
        val ks = (p - 1) * perPage + 1
        val js = p * perPage

        val params = buildListParams(category, ks, js)
        val (status, body) = postForm("$JWXT$P_LIST?gnmkdm=$GNMKDM", params, INDEX_URL)

        if (status == 911) {
            throw CourseSelectionException("会话失效或参数校验失败(911)，请重新登录")
        }

        val parsed = runCatching { json.decodeFromString<CourseListResponse>(body) }.getOrNull()
            ?: throw CourseSelectionException("课程列表请求失败: HTTP $status ${body.take(200)}")

        // kcrow 窗口切分：服务器推送的 tmpList 可能超出请求窗口
        val rows = parsed.tmpList.filter { row ->
            val rowNo = row.rankInBatch.toIntOrNull() ?: return@filter false
            rowNo in ks..js
        }

        // 本批未满 → 已到末尾（对应脚本 `if len(rows) < per_page: break`）
        CourseBatch(
            courses = rows,
            batch = p,
            isEnd = rows.size < perPage
        )
    }

    /**
     * 构造课程列表请求参数。
     *
     * 先按白名单从上下文取值，再对空值套用 [DEFAULT_RULE_FLAGS]
     * （否则服务器会静默忽略专业筛选），最后叠加类别与分页参数。
     */
    private fun buildListParams(category: CourseCategory, ks: Int, js: Int): Map<String, String> {
        val params = linkedMapOf<String, String>()
        val c = context
        for (key in LIST_KEYS) {
            var value = c.get(key)
            if (value.isEmpty()) value = DEFAULT_RULE_FLAGS[key] ?: value
            params[key] = value
        }
        params["kklxdm"] = category.typeCode
        params["xkkz_id"] = category.controlId
        params["njdm_id"] = category.gradeId
        params["zyh_id"] = category.majorId
        params["kspage"] = ks.toString()
        params["jspage"] = js.toString()
        if (params["xklc"].isNullOrEmpty()) params["xklc"] = roundNumber()
        return params
    }

    /**
     * 关键字本地过滤。
     *
     * 复刻脚本 `_match()`：仅匹配**已拉取到本地**的数据
     * （`kch` / `kcmc` / `jxbmc` 三个字段）。
     *
     * 注意：这是本地过滤而非服务端搜索，因此调用方需保证相关批次已加载。
     *
     * @param courses 待过滤列表
     * @param keyword 关键字，大小写不敏感；空白时原样返回
     */
    fun filterByKeyword(courses: List<SelectableCourse>, keyword: String): List<SelectableCourse> {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return courses
        return courses.filter { c ->
            val haystack = "${c.courseCode} ${c.courseName} ${c.className}".lowercase()
            haystack.contains(kw)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 教学班详情
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 获取某课程下全部教学班的详细信息（含选课必需的 `do_jxb_id`）。
     *
     * 对应脚本 `classes_of()`。
     *
     * @param courseId 课程内部 ID（`kch_id`）
     * @param category 类别；为 null 时取第一个类别
     * @throws CourseSelectionException 请求失败或教务拒绝访问（返回 "0"）
     */
    suspend fun classesOf(
        courseId: String,
        category: CourseCategory? = null
    ): List<CourseClass> = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()
        val tab = category ?: categories.firstOrNull()
            ?: throw CourseSelectionException("未解析到任何选课类别，请确认是否在选课时间内")

        val params = linkedMapOf<String, String>()
        val c = context
        for (key in CLASS_KEYS) {
            var value = c.get(key)
            if (value.isEmpty()) value = DEFAULT_RULE_FLAGS[key] ?: value
            params[key] = value
        }
        params["kch_id"] = courseId
        params["kklxdm"] = tab.typeCode
        params["xkkz_id"] = tab.controlId
        params["njdm_id"] = tab.gradeId
        params["zyh_id"] = tab.majorId
        if (params["xklc"].isNullOrEmpty()) params["xklc"] = roundNumber().ifBlank { "1" }

        val (status, body) = postForm("$JWXT$P_CLASSES?gnmkdm=$GNMKDM", params, INDEX_URL)
        val trimmed = body.trim()

        if (trimmed == "\"0\"" || trimmed == "0") {
            Log.w(TAG, "classesOf 被拒绝(返回0) kch_id=$courseId kklxdm=${tab.typeCode}")
            throw CourseSelectionException("教学班详情被拒绝（非法访问）")
        }

        // ── 诊断：打印详情接口真实返回的字段名与容量相关值 ──
        // 容量字段名在不同教务版本间存在差异（jxbrl / jxbrs / yxzrs …），
        // 只有看到真实键名才能确定映射是否正确，避免靠猜。
        runCatching {
            val first = json.parseToJsonElement(trimmed)
                .let { it as? JsonArray }
                ?.firstOrNull()
                ?.let { it as? JsonObject }
            if (first != null) {
                val keys = first.keys.joinToString(",")
                fun v(k: String) = first[k]?.toString()?.trim('"').orEmpty()
                Log.d(
                    TAG,
                    "classesOf kch_id=$courseId 首条字段名=[$keys] " +
                            "jxbrl=${v("jxbrl")} jxbrs=${v("jxbrs")} yxzrs=${v("yxzrs")} " +
                            "jxbmc=${v("jxbmc")} do_jxb_id=${v("do_jxb_id")} jxb_id=${v("jxb_id")}"
                )
            } else {
                Log.w(TAG, "classesOf 响应非 JSON 数组: HTTP $status body=${body.take(300)}")
            }
        }

        runCatching { json.decodeFromString<List<CourseClass>>(trimmed) }.getOrElse {
            throw CourseSelectionException("教学班详情请求失败: HTTP $status ${body.take(200)}")
        }
    }

    /**
     * 获取某教学班的子课程列表。
     *
     * 对应脚本 `sub_courses()`。当教学班由多个子课程组成
     * （[SelectableCourse.hasSubCourses] / `jxbzls > 1`）时，
     * 必须让用户勾选子课程，再把多个 `do_jxb_id` 逗号拼接提交。
     *
     * @param course 需含 [SelectableCourse.classId]（作为 `jxb_id`）与 [SelectableCourse.subCourseCount]
     * @return 子课程列表；接口返回非数组时抛异常
     */
    suspend fun subCourses(
        course: SelectableCourse,
        category: CourseCategory? = null
    ): List<SubCourse> = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()

        // 脚本用 do_jxb_id 查询子课程；列表接口的 jxb_id 即该值
        val doJxbId = course.classId
        if (doJxbId.isBlank()) {
            throw CourseSelectionException("需要 do_jxb_id 才能查询子课程")
        }
        val subCount = course.subCourseCount.toIntOrNull() ?: 1
        val tab = category
            ?: categories.firstOrNull { it.typeCode == course.typeCode }
            ?: categories.firstOrNull()
            ?: throw CourseSelectionException("未解析到任何选课类别")

        val c = context
        val params = linkedMapOf(
            "xkxnm" to c.academicYear,
            "xkxqm" to c.academicTerm,
            "xkly" to "0",
            "jxb_id" to doJxbId,
            "jxbzls" to subCount.toString(),
            "cdrlkz" to c.get("cdrlkz", "0"),
            "rlkz" to c.get("rlkz", "0"),
            "rlzlkz" to c.get("rlzlkz", "1"),
            "rwlx" to c.get("rwlx", "1"),
            "syqz" to c.get("qzz", "100"),
            "zyfx_id" to c.get("zyfx_id"),
            "bh_id" to c.get("bh_id"),
            "zyh_id" to tab.majorId,
            "txbsfrl" to c.get("txbsfrl", "1"),
            "njdm_id" to tab.gradeId,
            "sfkknj" to "0", "gnjkxdnj" to "0", "sfkkzy" to "0", "zh" to "",
            "sfznkx" to "0",
            "kklxdm" to tab.typeCode,
            "xh_id" to c.get("xh_id"),
            "bklx_id" to "0",
            "xklc" to roundNumber().ifBlank { "5" },
            "kkbk" to "0", "kkbkdj" to "0", "bklbkcj" to "0", "bhbcyxkjxb" to "0",
            "fxbj" to course.isMinor.ifBlank { "0" },
            "cxbj" to course.isRetake.ifBlank { "0" },
            "kzybkxy" to "0", "zcongbj" to "0",
            "csrftoken" to c.get("csrftoken")
        )

        val (status, body) = postForm("$JWXT$P_SUB?gnmkdm=$GNMKDM", params, INDEX_URL)
        runCatching { json.decodeFromString<List<SubCourse>>(body.trim()) }.getOrElse {
            throw CourseSelectionException("子课程请求失败: HTTP $status ${body.take(200)}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 选课 / 退选
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 选课提交。
     *
     * 对应脚本 `select()`。**这是唯一的"写"操作之一，调用前必须已完成
     * `my_enrolled()` 预检查**（见调用方 [com.xingheyuzhuan.shiguangschedule.ui.campus.CourseSelectionViewModel]），
     * 以避免重复提交。
     *
     * @param course 目标教学班（需含 [SelectableCourse.courseId] 与 [SelectableCourse.classId]）
     * @param category 类别；为 null 时按课程自带 `kklxdm` 匹配
     * @param doJxbId 实际提交 ID。来自 [classesOf] 的 [CourseClass.doJxbId]；
     *                缺省时回退用 [SelectableCourse.classId]
     * @param pickedSubCourses 勾选的子课程；非空时其 `do_jxb_id` 会逗号拼接为 `jxb_ids`
     * @return 教务返回的业务结果；**网络/会话级失败抛异常**
     */
    suspend fun select(
        course: SelectableCourse,
        category: CourseCategory? = null,
        doJxbId: String? = null,
        pickedSubCourses: List<SubCourse> = emptyList()
    ): SelectionOutcome = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()

        val courseId = course.courseId.ifBlank { course.courseCode }
        val submitId = doJxbId?.takeIf { it.isNotBlank() } ?: course.classId
        if (submitId.isBlank()) {
            throw CourseSelectionException("缺少 do_jxb_id，无法选课")
        }

        // 多子课程教学班：jxb_ids = 各子课程 do_jxb_id 逗号拼接
        val jxbIds = if (pickedSubCourses.isNotEmpty()) {
            val ids = pickedSubCourses.mapNotNull { it.doJxbId.takeIf { s -> s.isNotBlank() } }
            if (ids.isEmpty()) throw CourseSelectionException("子课程列表为空，无法选课")
            ids.joinToString(",")
        } else {
            submitId
        }

        val tab = category
            ?: categories.firstOrNull { it.typeCode == course.typeCode }
            ?: categories.firstOrNull()
            ?: throw CourseSelectionException("未解析到任何选课类别")

        val c = context
        // 期望的课程名：对应脚本 `select()` 的 `course.get('kcmc','')`
        val expectedCourseName = c.get("kcmc").ifBlank { course.courseName }

        val params = linkedMapOf(
            "rwlx" to c.get("rwlx"),
            "rlkz" to c.get("rlkz"),
            "cdrlkz" to c.get("cdrlkz"),
            "rlzlkz" to c.get("rlzlkz"),
            "xkxnm" to c.academicYear,
            "xkxqm" to c.academicTerm,
            "xklc" to roundNumber().ifBlank { "1" },
            "njdm_id" to tab.gradeId,
            "zyh_id" to tab.majorId,
            // 用列表接口归一化后的课程名（即脚本的 course['kcmc']）。
            // 早期实现从 ctx["kcmc"] 取，但 ctx 是**页面级**键值集合，
            // 可能被页面上其他同名字段污染，与浏览器提交值不一致。
            "kcmc" to course.courseName.ifBlank { expectedCourseName },
            "kch_id" to courseId,
            "jxb_ids" to jxbIds,
            "sxbj" to if (listOf("rlkz", "cdrlkz", "rlzlkz").any { c.get(it) == "1" }) "1" else "0",
            "xxkbj" to course.hasPrerequisite.ifBlank { "0" },
            "cxbj" to course.isRetake.ifBlank { "0" },
            "fxbj" to course.isMinor.ifBlank { "0" },
            "xkkz_id" to tab.controlId,
            "kklxdm" to tab.typeCode,
            "qz" to c.get("qzz", "100"),
            "jcxx_id" to ""
        )

        // 诊断日志：选课是与教务的强契约交互，参数细微不一致就会失败。
        // 记录**全部提交参数**与教务原始应答，便于定位 "非法访问(flag=0)" 的确切原因。
        Log.d(
            TAG,
            "select 提交参数=${params.entries.joinToString("&") { "${it.key}=${it.value}" }}"
        )
        Log.d(TAG, "select 轮次抽取: pageRoundName='${c.roundName}' → xklc='${params["xklc"]}'")

        val (status, body) = postForm("$JWXT$P_SELECT?gnmkdm=$GNMKDM", params, INDEX_URL)
        val obj = runCatching { json.decodeFromString<SelectResponse>(body.trim()) }.getOrNull()
            ?: run {
                // 解析失败通常意味着教务返回了 HTML（未登录页 / 错误页），
                // 打印前 300 字符便于判断到底是"会话失效"还是"参数被拒后返回了错误页"
                Log.w(TAG, "select 响应无法解析: HTTP $status body=${body.take(300)}")
                throw CourseSelectionException("选课请求失败: HTTP $status ${body.take(200)}")
            }

        val flag = obj.flag
        val msg = obj.msg
        Log.d(TAG, "select 教务应答: HTTP $status flag=$flag msg=$msg raw=${body.take(300)}")

        when {
            // flag=1 / 3 视为成功（脚本 `ok = flag in ('1', '3')`）
            flag == "1" || flag == "3" -> SelectionOutcome.Success(flag, msg)
            flag == "6" -> SelectionOutcome.AlreadyEnrolled("该教学班已选中（重复选课）")
            flag == "-1" -> SelectionOutcome.ClassFull(FLAG_MSG["-1"].orEmpty())
            // flag=0：脚本的原文是"非法访问（**会话失效或参数校验失败**）"，
            // 二者含义完全不同。绝不能一律当成会话失效 —— 那会让用户陷入
            // "选课失败 → 提示重登 → 重登 → 再选 → 又失败" 的死循环。
            // 真正的会话失效由 HTTP 911 / 上下文抓取失败来判定。
            flag == "0" -> SelectionOutcome.ParameterRejected(
                msg.ifBlank { "教务拒绝本次提交（参数校验未通过）" }
            )
            else -> SelectionOutcome.Failure(flag, msg.ifBlank { FLAG_MSG[flag] ?: "选课未成功(flag=$flag)" })
        }
    }

    /**
     * 退选提交。
     *
     * 对应脚本 `drop()`。**破坏性操作**：教务端退掉的名额可能无法立即重新选上。
     *
     * 接口返回可能是 JSON 标量（`"1"` / `2` / `true`）或纯文本，故此处
     * 不做反序列化，而是**先解析 JSON 再回退原文**，复刻脚本的四类型分支。
     *
     * @param course 需含 `kch_id` 与 `jxb_id`/`do_jxb_id`
     * @return `code == "1"` 为成功；否则按 [DROP_MSG] 给出文案
     */
    suspend fun drop(course: EnrolledCourse): SelectionOutcome = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()

        val courseId = course.courseId.ifBlank { course.courseCode }
        val classId = course.doJxbId.ifBlank { course.classId }
        if (courseId.isBlank() || classId.isBlank()) {
            throw CourseSelectionException("退选需要 kch_id 与 jxb_id/do_jxb_id")
        }

        val c = context
        val params = linkedMapOf(
            "kch_id" to courseId,
            // 注意：退选的 jxb_ids 用单个 jxb_id，不做逗号拼接
            "jxb_ids" to classId,
            "xkxnm" to c.academicYear,
            "xkxqm" to c.academicTerm,
            "txbsfrl" to c.get("txbsfrl", "1")
        )

        val (status, body) = postForm("$JWXT$P_DROP?gnmkdm=$GNMKDM", params, INDEX_URL)
        val code = parseDropCode(body)
        Log.d(TAG, "drop kch_id=$courseId jxb_ids=$classId → HTTP $status code=$code")
        if (code == "1") {
            SelectionOutcome.Success(code, "退选成功")
        } else if (code == "4") {
            // '4' = "警告：你正在非法访问！"，与选课的 flag=0 同源（参数校验失败），
            // 同样不能当作会话失效
            SelectionOutcome.ParameterRejected(DROP_MSG[code] ?: "退选被教务拒绝")
        } else {
            SelectionOutcome.Failure(code, DROP_MSG[code] ?: "退选失败(code=$code)")
        }
    }

    /**
     * 解析退选接口的返回值。
     *
     * 复刻脚本 `:639-651` 的多类型容错：JSON 布尔 / 数字 / 字符串，
     * 以及完全非 JSON 的纯文本。
     */
    private fun parseDropCode(body: String): String {
        val trimmed = body.trim()
        // 尝试按 JSON 标量解析
        runCatching {
            val element = json.parseToJsonElement(trimmed)
            when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> {
                    if (element.isString) return element.content.trim()
                    // 布尔 true/false 与数字统一转成 "1"/"0" 风格
                    val raw = element.content
                    return when (raw) {
                        "true" -> "1"
                        "false" -> "0"
                        else -> raw.substringBefore('.').trim()
                    }
                }
                else -> Unit
            }
        }
        // 回退：纯文本，去掉可能的 JSON 引号
        return trimmed.trim('"')
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 已选课程 / 课表
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 已选课程（**权威来源**）。
     *
     * 对应脚本 `my_enrolled()`。本接口覆盖全部已选课程且自带
     * `jxb_id`/`do_jxb_id`，可直接用于退选。
     *
     * 亦用于"选课结果未知"时的权威状态确认（见 ViewModel 的超时处理）。
     */
    suspend fun myEnrolled(): List<EnrolledCourse> = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()

        val c = context
        val params = linkedMapOf(
            "jg_id" to c.first("jg_id_1", "jg_id"),
            "zyh_id" to c.get("zyh_id"),
            "njdm_id" to c.get("njdm_id"),
            "zyfx_id" to c.get("zyfx_id"),
            "bh_id" to c.get("bh_id"),
            "xz" to c.get("xz"),
            "ccdm" to c.get("ccdm"),
            "xqh_id" to c.get("xqh_id", "0"),
            "xkxnm" to c.academicYear,
            "xkxqm" to c.academicTerm,
            "xkly" to "0"
        )

        val (status, body) = postForm("$JWXT$P_ENROLLED?gnmkdm=$GNMKDM", params, INDEX_URL)
        runCatching { json.decodeFromString<List<EnrolledCourse>>(body.trim()) }.getOrElse {
            throw CourseSelectionException("已选课程请求失败: HTTP $status ${body.take(200)}")
        }
    }

    /**
     * 课表（含上课时间/教室）。
     *
     * 对应脚本 `my_schedule()`。**不可用于判断已选** ——
     * 课表只返回有上课时间的课程，无排课课程（课程设计、实训等）不会出现。
     *
     * 学年学期取自上下文（`xkxnm`/`xkxqm`），而非本地日期推算。
     */
    suspend fun mySchedule(
        academicYear: String? = null,
        academicTerm: String? = null
    ): List<SelectionScheduleItem> = withContext(Dispatchers.IO) {
        if (context.raw.isEmpty()) refreshContext()
        val c = context

        val params = linkedMapOf(
            // 注意：课表接口用 xnm/xqm，而选课接口用 xkxnm/xkxqm
            "xnm" to (academicYear?.takeIf { it.isNotBlank() } ?: c.academicYear),
            "xqm" to (academicTerm?.takeIf { it.isNotBlank() } ?: c.academicTerm),
            "kzlx" to "ck", "xsdm" to "", "kclbdm" to "", "kclxdm" to ""
        )

        val (status, body) = postForm("$JWXT$P_KB", params, INDEX_URL)
        // 使用本模块专用 DTO 而非共享的 CourseItem —— 后者只映射了 6 个字段，
        // 缺少 kch/jxb_id/jxbmc 等选课模块需要的字段，且被同步模块依赖，不宜改动。
        runCatching { json.decodeFromString<SelectionScheduleResponse>(body.trim()).items }
            .getOrElse {
                throw CourseSelectionException("课表请求失败: HTTP $status ${body.take(200)}")
            }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部 HTTP 工具
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 提交表单并返回 `(状态码, 响应体)`。
     *
     * 与 [ScnuScraper] 的差异：此处**不因非 2xx 抛异常**，而是把状态码交给调用方
     * —— 因为教务用 `911` 表达"会话失效/参数校验失败"，需要与网络错误区分对待。
     */
    private fun postForm(
        url: String,
        params: Map<String, String>,
        referer: String
    ): Pair<Int, String> {
        val formBody = FormBody.Builder()
        params.forEach { (k, v) -> formBody.add(k, v) }
        val request = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .header("Origin", JWXT)
            .post(formBody.build())
            .build()

        httpClient.newCall(request).execute().use { response ->
            return response.code to response.body?.string().orEmpty()
        }
    }

    /** GET 请求并返回响应体文本 */
    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CourseSelectionException("GET 失败: HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }
}

/**
 * 一批课程数据。
 *
 * @param courses 本批课程（已按 `kcrow` 窗口切分）
 * @param batch 批次号
 * @param isEnd 是否已到末尾（本批未满 [ScnuCourseSelector.PAGE_SIZE] 即结束）
 */
data class CourseBatch(
    val courses: List<SelectableCourse>,
    val batch: Int,
    val isEnd: Boolean
)

/**
 * 课程列表接口的响应外层。
 *
 * 教务返回 `{"tmpList": [...]}`，即使无数据也不抛异常。
 */
@kotlinx.serialization.Serializable
private data class CourseListResponse(
    @kotlinx.serialization.SerialName("tmpList")
    val tmpList: List<SelectableCourse> = emptyList()
)

/**
 * 课表接口的响应外层（`{"kbList": [...]}`）。
 *
 * 专用 DTO：不复用 `data.network.CourseResponse`，因为它内部的 `CourseItem`
 * 只映射了 6 个字段（`kcmc`/`xm`/`xqj`/`jcs`/`zcd`/`cdmc`），缺少选课模块
 * 展示需要的 `kch`/`jxb_id`/`jxbmc`/`xqmc`，且被已发布的同步模块依赖。
 */
@kotlinx.serialization.Serializable
private data class SelectionScheduleResponse(
    @kotlinx.serialization.SerialName("kbList")
    val items: List<SelectionScheduleItem> = emptyList()
)

/**
 * 选课接口的响应。
 *
 * 教务返回 `{"flag": "1", "msg": "..."}`；字段缺失时默认为空串。
 */
@kotlinx.serialization.Serializable
private data class SelectResponse(
    @kotlinx.serialization.SerialName("flag")
    val flag: String = "",
    @kotlinx.serialization.SerialName("msg")
    val msg: String = ""
)

// ═══════════════════════════════════════════════════════════════════════════
// hidden input 解析
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 收集页面全部 `<input type="hidden">`，返回 `name/id → value` 映射。
 *
 * 对应脚本 `_hidden_map()`：属性顺序无关，支持双引号/单引号/无引号三种写法。
 */
private val INPUT_TAG_RE = Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE)

private fun parseHiddenInputs(html: String): Map<String, String> {
    val out = linkedMapOf<String, String>()
    for (match in INPUT_TAG_RE.findAll(html)) {
        val tag = match.value
        if (attrOf(tag, "type")?.lowercase() != "hidden") continue
        val key = attrOf(tag, "name") ?: attrOf(tag, "id") ?: continue
        if (key.isNotBlank()) out[key] = attrOf(tag, "value") ?: ""
    }
    return out
}

/**
 * 从单个标签字符串中提取属性值。
 *
 * 支持 `attr="v"` / `attr='v'` / `attr=v` 三种形式，对应脚本 `attr()` 辅助函数。
 */
private fun attrOf(tag: String, name: String): String? {
    val re = Regex("""$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)
    val m = re.find(tag) ?: return null
    return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
}
