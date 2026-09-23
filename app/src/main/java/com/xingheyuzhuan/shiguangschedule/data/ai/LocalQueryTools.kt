package com.xingheyuzhuan.shiguangschedule.data.ai

import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地工具集：**5 个教务查询工具**的声明 + 实现 + 参数校验。
 *
 * ## 工具清单（方案文档 §9.2）
 *
 * | 工具 | 参数 | 典型提问 |
 * |---|---|---|
 * | [TOOL_COURSES] | `start_date`, `end_date` | "明天有什么课""下周课表" |
 * | [TOOL_EXAMS] | `only_upcoming` | "下周期中在哪考""还有几门没考" |
 * | [TOOL_GRADES] | `course_name`（可选） | "高数考了多少分" |
 * | [TOOL_CREDITS] | 无 | "还差多少学分""绩点多少" |
 * | [TOOL_PLAN] | `category`（可选） | "还差哪几类学分" |
 *
 * ## 为什么 description 用中文硬编码、不进 `strings.xml`
 *
 * `description` 是**给模型看的 prompt 内容**，不是给用户看的 UI 文案。
 * 把它放进 4 份 `strings.xml` 会导致同一个功能在不同系统语言下
 * 给模型不同的指令，行为随之漂移 —— 这是不可接受的：工具选择准确率
 * 直接取决于描述文案（§9.2 的经验）。因此它是代码常量。
 *
 * 用户真正会看到的两类文案（工具执行提示、失败提示）仍然走 `strings.xml`。
 *
 * ## 参数校验（§9.2 坑 4）
 *
 * 所有参数都来自模型输出，**必须逐项校验**，绝不能"模型传什么就执行什么"：
 * - 日期：严格 `yyyy-MM-dd`，年份限定在 2000–2100，区间不超过 [MAX_RANGE_DAYS] 天
 * - 课程名 / 类别名：长度上限 [MAX_TEXT_LENGTH]，去掉控制字符
 */
@Singleton
class LocalQueryTools @Inject constructor(
    private val aggregator: LocalDataAggregator
) {

    companion object {
        const val TOOL_COURSES = "get_courses_by_date_range"
        const val TOOL_EXAMS = "get_exams"
        const val TOOL_GRADES = "get_grades"
        const val TOOL_CREDITS = "get_credit_summary"
        const val TOOL_PLAN = "get_plan_progress"

        /** 单次日期区间的上限。防止模型传一个"全学期"甚至"三年"的范围把结果撑爆。 */
        private const val MAX_RANGE_DAYS = 70L

        /** 文本参数长度上限（课程名、类别名）。 */
        private const val MAX_TEXT_LENGTH = 30

        /** 单个工具返回给模型的明细行数上限，防止 payload 过长。 */
        private const val MAX_ROWS = 120

        private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** 星期几的中文标签，1=周一 … 7=周日。 */
        private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }

    /**
     * 工具结果的 Json。
     *
     * 与请求体用同一套开关：`encodeDefaults = true`（0 和空列表也要显式出现，
     * 让模型看到"就是 0 条"而不是字段缺失）、`explicitNulls = false`（不塞 null）。
     */
    private val payloadJson: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 声明（ToolSpec）
    // ═══════════════════════════════════════════════════════════════════════

    private val coursesSpec = ToolSpec(
        name = TOOL_COURSES,
        description = """
            查询指定日期范围内的课程安排（含上课时间、地点、教师、节次）。

            当用户问「明天有什么课」「下周课表」「这周三下午有课吗」「今天几节课」
            「周四上午上什么」时调用。用户说"今天/明天/后天/这周/下周/周几"时，
            请先按当前日期换算成 yyyy-MM-dd 再调用，不要自己推测课程内容。
            范围最多 70 天，一次只查一个连续区间。
        """.trimIndent(),
        group = ToolGroup.ACADEMIC,
        parametersSchema = objectSchema(
            properties = mapOf(
                "start_date" to stringProperty("起始日期，格式 yyyy-MM-dd（含当天）"),
                "end_date" to stringProperty("结束日期，格式 yyyy-MM-dd（含当天）。查单天时与 start_date 相同")
            ),
            required = listOf("start_date", "end_date")
        ),
        loadingHintRes = R.string.ai_tool_loading_courses
    )

    private val examsSpec = ToolSpec(
        name = TOOL_EXAMS,
        description = """
            查询考试安排（考试名称、科目、时间、考场、校区）。

            当用户问「下周期中在哪考」「还有几门没考」「考试安排」「XX 什么时候考」
            「考场在哪」时调用。默认只返回还没考完的考试。
            若用户明确问历史考试（"上学期的考试"），把 only_upcoming 设为 false。
        """.trimIndent(),
        group = ToolGroup.ACADEMIC,
        parametersSchema = objectSchema(
            properties = mapOf(
                "only_upcoming" to booleanProperty("true=只要还没考完的（默认）；false=返回全部考试记录")
            )
        ),
        loadingHintRes = R.string.ai_tool_loading_exams
    )

    private val gradesSpec = ToolSpec(
        name = TOOL_GRADES,
        description = """
            查询成绩明细（每门课的分数、绩点、学分、课程性质、任课教师、学年学期）。

            当用户问「高数考了多少分」「我某门课的成绩」「成绩单」「XX 课绩点多少」时调用。
            course_name 传课程名的关键词（如「高数」「数据结构」）即可，支持模糊匹配；
            用户没指定课程、想看全部成绩时不要传 course_name。
        """.trimIndent(),
        group = ToolGroup.ACADEMIC,
        parametersSchema = objectSchema(
            properties = mapOf(
                "course_name" to stringProperty("课程名关键词，可选。不传则返回全部成绩")
            )
        ),
        loadingHintRes = R.string.ai_tool_loading_grades
    )

    private val creditsSpec = ToolSpec(
        name = TOOL_CREDITS,
        description = """
            查询学分、绩点与非正式学时的**汇总统计**（已由本地算好，直接引用数字即可）。

            当用户问「还差多少学分毕业」「绩点多少」「平均绩点」「挂了几门」
            「非正式学时够了吗」「学分修了多少」时调用。
            返回内容包含：加权平均绩点、培养计划各类别的"要求/已修/差额"、
            非正式学时合计、以及本地判定的不及格课程名单。
        """.trimIndent(),
        group = ToolGroup.ACADEMIC,
        parametersSchema = emptyObjectSchema(),
        loadingHintRes = R.string.ai_tool_loading_credits
    )

    private val planSpec = ToolSpec(
        name = TOOL_PLAN,
        description = """
            查询培养计划的分类完成度（每一类"要求多少学分 / 已修多少学分 / 还差多少"）。

            当用户问「还差哪几类学分」「培养计划完成情况」「通识教育修完了吗」
            「专业选修还差多少」时调用。category 传类别名关键词（如「通识」「专业选修」）；
            不传则返回全部类别。
        """.trimIndent(),
        group = ToolGroup.ACADEMIC,
        parametersSchema = objectSchema(
            properties = mapOf(
                "category" to stringProperty("培养计划类别名关键词，可选。不传则返回全部类别")
            )
        ),
        loadingHintRes = R.string.ai_tool_loading_plan
    )

    /** 全部已实现的工具声明。 */
    val allSpecs: List<ToolSpec> = listOf(
        coursesSpec,
        examsSpec,
        gradesSpec,
        creditsSpec,
        planSpec
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 实现（ToolHandler）
    //
    // 与声明分离：将来给二手集市 / 美食街加工具时，只需在这里多一个 handler、
    // 在上面多一个 spec，AgentLoop 一行都不用改。
    // ═══════════════════════════════════════════════════════════════════════

    private val handlers: Map<String, ToolHandler> = mapOf(
        TOOL_COURSES to object : ToolHandler {
            override suspend fun execute(arguments: JsonObject): ToolResult {
                val startRaw = arguments.stringArg("start_date")
                val endRaw = arguments.stringArg("end_date")
                val start = parseDateArg(startRaw)
                    ?: return invalidArgument("start_date 需要 yyyy-MM-dd 格式的日期，收到：${startRaw.orEmpty().take(20)}")
                val end = parseDateArg(endRaw)
                    ?: return invalidArgument("end_date 需要 yyyy-MM-dd 格式的日期，收到：${endRaw.orEmpty().take(20)}")
                if (end.isBefore(start)) {
                    return invalidArgument("end_date 不能早于 start_date")
                }
                val spanDays = ChronoUnit.DAYS.between(start, end)
                if (spanDays > MAX_RANGE_DAYS) {
                    return invalidArgument("查询范围最多 $MAX_RANGE_DAYS 天，本次请求 $spanDays 天，请缩小范围")
                }

                val occurrences = aggregator.coursesInRange(start, end)
                val table = aggregator.loadCourseTable()
                val note = buildString {
                    if (table?.semesterStartDate == null) {
                        append("用户尚未设置开学日期，无法把日期换算成教学周，因此结果可能不完整。")
                    }
                    if (occurrences.isEmpty()) {
                        if (isNotEmpty()) append(' ')
                        append("该日期范围内没有查到课程。")
                    }
                }.ifEmpty { null }

                return ToolResult.Success(
                    payloadJson.encodeToString(
                        CoursesPayload.serializer(),
                        CoursesPayload(
                            startDate = start.toString(),
                            endDate = end.toString(),
                            count = occurrences.size,
                            courses = occurrences.take(MAX_ROWS).map { it.toRow() },
                            note = note
                        )
                    )
                )
            }
        },

        TOOL_EXAMS to object : ToolHandler {
            override suspend fun execute(arguments: JsonObject): ToolResult {
                val onlyUpcoming = arguments.booleanArg("only_upcoming") ?: true
                val exams = aggregator.exams(onlyUpcoming = onlyUpcoming)
                return ToolResult.Success(
                    payloadJson.encodeToString(
                        ExamsPayload.serializer(),
                        ExamsPayload(
                            onlyUpcoming = onlyUpcoming,
                            count = exams.size,
                            exams = exams.take(MAX_ROWS).map { it.toRow() },
                            note = if (exams.isEmpty()) "没有查到考试记录。" else null
                        )
                    )
                )
            }
        },

        TOOL_GRADES to object : ToolHandler {
            override suspend fun execute(arguments: JsonObject): ToolResult {
                // 注意区分「没传」与「传了但不合法」：
                // 没传 → 查全部成绩；传了但超长/含控制字符 → 报参数错误。
                // 若把两者混为一谈，用户问"我全部成绩"会被误判成参数错误。
                val rawName = arguments.stringArg("course_name")
                val keyword = if (rawName == null) {
                    null
                } else {
                    sanitizeText(rawName)
                        ?: return invalidArgument(
                            "course_name 过长或含非法字符，最多 $MAX_TEXT_LENGTH 个字符"
                        )
                }
                val grades = aggregator.gradesMatching(keyword)
                return ToolResult.Success(
                    payloadJson.encodeToString(
                        GradesPayload.serializer(),
                        GradesPayload(
                            query = keyword ?: "",
                            count = grades.size,
                            grades = grades.take(MAX_ROWS).map { it.toRow() },
                            note = if (grades.isEmpty()) {
                                if (keyword == null) "本地还没有成绩数据，用户可能尚未同步。"
                                else "没有匹配「$keyword」的课程。"
                            } else null
                        )
                    )
                )
            }
        },

        TOOL_CREDITS to object : ToolHandler {
            override suspend fun execute(arguments: JsonObject): ToolResult {
                val summary = aggregator.creditSummary()
                return ToolResult.Success(
                    payloadJson.encodeToString(
                        CreditsPayload.serializer(),
                        CreditsPayload(
                            gpa = round2(summary.gpa),
                            gradeCount = summary.gradeCount,
                            gradeBasedCredits = round1(summary.gradeBasedCredits),
                            failedCourses = summary.failedCourseNames,
                            plan = PlanTotalsPayload(
                                available = summary.hasPlan,
                                requiredTotal = round1(summary.planRequiredTotal),
                                earnedTotal = round1(summary.planEarnedTotal),
                                gapTotal = round1(summary.planGapTotal)
                            ),
                            categories = summary.categories.map { it.toRow() },
                            nonFormal = NonFormalTotalsPayload(
                                totalHours = summary.nonFormal.totalHours,
                                courseCount = summary.nonFormal.courseCount
                            ),
                            notes = buildList {
                                if (!summary.hasPlan) {
                                    add("培养计划数据为空，用户可能尚未同步【学业情况】，因此没有学分要求与差额。")
                                }
                                add(
                                    "grade_based_credits 是成绩表里学分列的算术和（含不及格课程），" +
                                        "不等于「已获学分」；已获学分请用 plan.earned_total。"
                                )
                            }
                        )
                    )
                )
            }
        },

        TOOL_PLAN to object : ToolHandler {
            override suspend fun execute(arguments: JsonObject): ToolResult {
                // 同 TOOL_GRADES：区分「没传」与「传了但不合法」
                val rawCategory = arguments.stringArg("category")
                val keyword = if (rawCategory == null) {
                    null
                } else {
                    sanitizeText(rawCategory)
                        ?: return invalidArgument(
                            "category 过长或含非法字符，最多 $MAX_TEXT_LENGTH 个字符"
                        )
                }
                val all = aggregator.planProgress()
                val matched = if (keyword == null) {
                    all
                } else {
                    all.filter { it.name.contains(keyword, ignoreCase = true) }
                }

                // 类别名不匹配时，把**可用的类别名**一并回传：
                // 模型据此可以自己纠正重试，或如实告诉用户"没有这一类"。
                // 这比只回一句"没查到"有用得多，且不额外花钱。
                val note = when {
                    all.isEmpty() -> "培养计划数据为空，用户可能尚未同步【学业情况】。"
                    matched.isEmpty() -> "没有匹配「$keyword」的类别。可用类别：" +
                        all.joinToString("、") { it.name }

                    else -> null
                }

                return ToolResult.Success(
                    payloadJson.encodeToString(
                        PlanPayload.serializer(),
                        PlanPayload(
                            query = keyword ?: "",
                            count = matched.size,
                            categories = matched.map { it.toRow() },
                            availableCategories = all.map { it.name },
                            note = note
                        )
                    )
                )
            }
        }
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 按分组取工具声明。
     *
     * 这是"工具数始终 ≤ 6"的实现点：入口在【校园】页时默认只加载
     * [ToolGroup.ACADEMIC]；将来命中其他模块时换成对应组，命中跨模块则两组并集。
     *
     * 预留组（[ToolGroup.MARKETPLACE] / [ToolGroup.FOOD]）本版**没有任何工具**，
     * 因此 `specsFor(MARKETPLACE)` 返回空列表 —— 这是刻意的：它们的具体工具签名
     * 要等模块设计完成，现在写必然要改（§9.6「明确不预留」）。
     */
    fun specsFor(groups: Set<ToolGroup>): List<ToolSpec> =
        allSpecs.filter { it.group in groups }

    /** 按分组取给模型看的工具声明。 */
    fun declarationsFor(groups: Set<ToolGroup>): List<AiToolDeclaration> =
        specsFor(groups).map { it.toDeclaration() }

    /** 按名字取声明（用于界面显示"正在查询…"的提示）。 */
    fun specOf(name: String): ToolSpec? = allSpecs.firstOrNull { it.name == name }

    /**
     * 执行一次模型请求的工具调用。
     *
     * 三层防御，任一层失败都返回**结构化的失败**而不是抛异常 ——
     * 失败信息要回传给模型，让它如实说明"没查到"或自我纠正，
     * 而不是让整轮对话崩掉（§6.6）。
     */
    suspend fun execute(call: AiToolCall): ToolResult {
        val handler = handlers[call.name]
            ?: return ToolResult.Failure(
                kind = ToolFailureKind.NOT_AVAILABLE,
                payload = """{"error":"未知工具 ${call.name.take(40)}"}""",
                userHintRes = R.string.ai_tool_error_unknown_tool
            )

        val arguments = runCatching {
            payloadJson.parseToJsonElement(call.arguments).jsonObject
        }.getOrNull()
            ?: return invalidArgument("arguments 不是合法的 JSON 对象")

        return runCatching { handler.execute(arguments) }
            .getOrElse { e ->
                // 只记异常类型，不记参数内容（参数里可能有课程名等个人信息）
                android.util.Log.w("LocalQueryTools", "tool ${call.name} failed: ${e.javaClass.simpleName}")
                ToolResult.Failure(
                    kind = ToolFailureKind.INTERNAL,
                    payload = """{"error":"工具执行失败，请如实告知用户查询未成功"}""",
                    userHintRes = R.string.ai_tool_error_internal
                )
            }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 参数校验
    // ═══════════════════════════════════════════════════════════════════════

    /** 取字符串参数；非字符串的原始值（数字等）也接受，取其字面量。 */
    private fun JsonObject.stringArg(name: String): String? =
        (this[name] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.booleanArg(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    /**
     * 严格解析日期参数。
     *
     * 只认 ISO `yyyy-MM-dd`，且年份必须在 2000–2100：
     * - 模型很容易给出 `"2026/9/23"`、`"下周三"` 这类值，一律拒绝并要求它换算
     * - 年份上界防的是 `"0001-01-01"` 这类把日期区间拉到几百万天的输入
     */
    private fun parseDateArg(raw: String?): LocalDate? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val date = runCatching { LocalDate.parse(text, ISO_DATE) }.getOrNull() ?: return null
        return date.takeIf { it.year in 2000..2100 }
    }

    /**
     * 清洗文本参数。
     *
     * @return null 表示非法（超长或含控制字符）—— 调用方据此返回参数错误。
     *         **调用方必须先判空"没传"**，不能把 null 一律当成非法。
     */
    private fun sanitizeText(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.length > MAX_TEXT_LENGTH) return null
        if (text.any { it.isISOControl() }) return null
        return text
    }

    private fun invalidArgument(reason: String): ToolResult = ToolResult.Failure(
        kind = ToolFailureKind.INVALID_ARGUMENT,
        payload = payloadJson.encodeToString(
            ErrorPayload.serializer(),
            ErrorPayload(error = reason, hint = "请修正参数后重新调用该工具")
        ),
        userHintRes = R.string.ai_tool_error_invalid_argument
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 映射
    // ═══════════════════════════════════════════════════════════════════════

    private fun CourseOccurrence.toRow() = CourseRow(
        date = date.toString(),
        weekday = WEEKDAY_LABELS.getOrElse(dayOfWeek - 1) { "未知" },
        week = weekNumber,
        name = name,
        teacher = teacher,
        location = position,
        time = when {
            startTime.isNotBlank() && endTime.isNotBlank() -> "$startTime-$endTime"
            startTime.isNotBlank() -> startTime
            else -> ""
        },
        section = sectionLabel
    )

    private fun AiExamItem.toRow() = ExamRow(
        name = name,
        course = course,
        time = rawTime,
        venue = venue,
        campus = campus,
        daysUntil = daysUntil
    )

    private fun GradeEntity.toRow() = GradeRow(
        course = kcmc.ifBlank { "—" },
        code = kch,
        credits = xf,
        score = cj.ifBlank { "—" },
        gpa = jd,
        nature = kcxzmc,
        teacher = jsxm,
        term = listOf(xnmmc, xqmmc).filter { it.isNotBlank() }.joinToString(" "),
        college = kkbmmc
    )

    private fun PlanCategoryProgress.toRow() = PlanCategoryRow(
        name = name,
        requiredCredits = round1(requiredCredits),
        earnedCredits = round1(earnedCredits),
        gapCredits = round1(gapCredits),
        courseCount = courseCount
    )

    private fun round1(value: Float): Double = Math.round(value * 10.0) / 10.0

    private fun round2(value: Float): Double = Math.round(value * 100.0) / 100.0
}

// ═══════════════════════════════════════════════════════════════════════════
// 工具返回给模型的 payload 结构
//
// 全部 internal（同模块可见）而不是 public：它们只是 wire 结构，
// 不构成对外 API。字段名用 snake_case，与工具参数的命名风格保持一致。
// ═══════════════════════════════════════════════════════════════════════════

@Serializable
internal data class ErrorPayload(
    val error: String,
    val hint: String? = null
)

@Serializable
internal data class CoursesPayload(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val count: Int,
    val courses: List<CourseRow>,
    val note: String? = null
)

@Serializable
internal data class CourseRow(
    val date: String,
    val weekday: String,
    val week: Int,
    val name: String,
    val teacher: String,
    val location: String,
    val time: String,
    val section: String
)

@Serializable
internal data class ExamsPayload(
    @SerialName("only_upcoming") val onlyUpcoming: Boolean,
    val count: Int,
    val exams: List<ExamRow>,
    val note: String? = null
)

@Serializable
internal data class ExamRow(
    val name: String,
    val course: String,
    val time: String,
    val venue: String,
    val campus: String,
    @SerialName("days_until") val daysUntil: Long?
)

@Serializable
internal data class GradesPayload(
    val query: String,
    val count: Int,
    val grades: List<GradeRow>,
    val note: String? = null
)

@Serializable
internal data class GradeRow(
    val course: String,
    val code: String,
    val credits: String,
    val score: String,
    val gpa: String,
    val nature: String,
    val teacher: String,
    val term: String,
    val college: String
)

@Serializable
internal data class CreditsPayload(
    val gpa: Double,
    @SerialName("grade_count") val gradeCount: Int,
    @SerialName("grade_based_credits") val gradeBasedCredits: Double,
    @SerialName("failed_courses") val failedCourses: List<String>,
    val plan: PlanTotalsPayload,
    val categories: List<PlanCategoryRow>,
    @SerialName("non_formal") val nonFormal: NonFormalTotalsPayload,
    val notes: List<String>
)

@Serializable
internal data class PlanTotalsPayload(
    val available: Boolean,
    @SerialName("required_total") val requiredTotal: Double,
    @SerialName("earned_total") val earnedTotal: Double,
    @SerialName("gap_total") val gapTotal: Double
)

@Serializable
internal data class NonFormalTotalsPayload(
    @SerialName("total_hours") val totalHours: Int,
    @SerialName("course_count") val courseCount: Int
)

@Serializable
internal data class PlanPayload(
    val query: String,
    val count: Int,
    val categories: List<PlanCategoryRow>,
    @SerialName("available_categories") val availableCategories: List<String>,
    val note: String? = null
)

@Serializable
internal data class PlanCategoryRow(
    val name: String,
    @SerialName("required_credits") val requiredCredits: Double,
    @SerialName("earned_credits") val earnedCredits: Double,
    @SerialName("gap_credits") val gapCredits: Double,
    @SerialName("course_count") val courseCount: Int
)
