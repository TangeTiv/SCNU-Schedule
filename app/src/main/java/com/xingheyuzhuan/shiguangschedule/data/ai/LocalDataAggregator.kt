package com.xingheyuzhuan.shiguangschedule.data.ai

import androidx.compose.runtime.Immutable
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicNonFormalCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicPlanNodeEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfigDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlotDao
import com.xingheyuzhuan.shiguangschedule.data.model.parseExamKssj
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════
// 本地数据的读取与**确定性计算**
//
// 这是 §6.8「让模型只做翻译，不做检索和计算」的落地处：
//
//   检索（读 Room）与计算（学分、绩点、培养计划树遍历）全部在本文件完成，
//   产出的都是**已经算好的数字**；模型拿到的 payload 里没有任何需要它再算的东西。
//
// 为什么这条这么重要：让模型自己读整棵培养计划树并汇总，**几乎必然算错** ——
// 而学分算错比"查不到"更糟，用户会当真。
//
// 本文件的所有方法都是 suspend + `Dispatchers.IO`，且不含任何 UI 依赖。
// ═══════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// 领域模型
// ─────────────────────────────────────────────────────────────────────────────

/** 一次具体的上课（某门课在某个日期的那一节）。 */
@Immutable
data class CourseOccurrence(
    val date: LocalDate,
    /** 1=周一 … 7=周日 */
    val dayOfWeek: Int,
    val weekNumber: Int,
    val name: String,
    val teacher: String,
    val position: String,
    /** "08:30"，取不到时为空串 */
    val startTime: String,
    val endTime: String,
    /** "第1-2节" / "自定义时间" */
    val sectionLabel: String,
    /** 排序用的时间键；自定义时间与节次混排时统一成可比较的字符串 */
    val sortKey: String
)

/** 当前课表的元信息快照。 */
@Immutable
data class CourseTableSnapshot(
    val tableId: String,
    val tableName: String,
    val semesterStartDate: LocalDate?,
    val totalWeeks: Int,
    val firstDayOfWeek: Int,
    val timeSlots: Map<Int, TimeSlot>,
    val courses: List<CourseWithWeeks>,
    /** 节假日等"跳过"的日期（yyyy-MM-dd），与课表网格/Ics 导出保持同一口径 */
    val skippedDates: Set<String>
)

/** 一条培养计划类别的完成度。 */
@Immutable
data class PlanCategoryProgress(
    val name: String,
    val requiredCredits: Float,
    val earnedCredits: Float,
    val gapCredits: Float,
    /** 该类别（含全部子节点）下的课程数 */
    val courseCount: Int
)

/** 一条非正式学时记录。 */
@Immutable
data class NonFormalItem(
    val courseName: String,
    val hours: Int,
    val result: String,
    val yearTerm: String
)

/** 非正式学时汇总。 */
@Immutable
data class NonFormalSummary(
    val totalHours: Int,
    val courseCount: Int,
    val items: List<NonFormalItem>
)

/**
 * 学分 / 绩点 / 非正式学时汇总。
 *
 * ## 数据来源的分工（**不要混用**）
 *
 * | 指标 | 来源 | 为什么 |
 * |---|---|---|
 * | 学分要求 / 已获学分 / 差额 | **培养计划树**（教务自己算的） | 教务是权威口径；用成绩表自己加会与教务页面不一致 |
 * | 绩点 | **成绩表**（本地加权平均） | 培养计划树不带总绩点 |
 * | 非正式学时 | 第二类课表 | 教务字段 `xssh` 直接可用 |
 *
 * @param gradeBasedCredits 成绩表里 `xf` 的算术和。**仅供参考**，它包含不及格课程，
 *        不等于"已获学分" —— payload 里会显式标注口径，避免模型把两者混为一谈
 * @param failedCourseNames 本地判定的不及格课程名（数字分 < 60 或结论含"不及格"）
 */
@Immutable
data class CreditSummary(
    val gpa: Float,
    val gradeCount: Int,
    val gradeBasedCredits: Float,
    val failedCourseNames: List<String>,
    val hasPlan: Boolean,
    val planRequiredTotal: Float,
    val planEarnedTotal: Float,
    val planGapTotal: Float,
    val categories: List<PlanCategoryProgress>,
    val nonFormal: NonFormalSummary
)

/** 一场考试（已换算好"距今天数"，模型不需要自己算日期差）。 */
@Immutable
data class AiExamItem(
    val name: String,
    val course: String,
    val rawTime: String,
    /** "教学楼A101"，与【考试安排】页的展示口径一致 */
    val venue: String,
    val campus: String,
    /** 距今天数；负数表示已过。无法解析时间时为 null */
    val daysUntil: Long?
)

// ─────────────────────────────────────────────────────────────────────────────
// 聚合器
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 本地数据的唯一读取入口。
 *
 * 所有方法都保证**不抛异常**：数据缺失（用户还没同步）返回空值，
 * 由上层如实告诉用户"查不到"，而不是崩掉或让模型瞎编（§6.6）。
 */
@Singleton
class LocalDataAggregator @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableDao: CourseTableDao,
    private val courseTableConfigDao: CourseTableConfigDao,
    private val courseDao: CourseDao,
    private val timeSlotDao: TimeSlotDao,
    private val gradeDao: GradeDao,
    private val examDao: ExamDao,
    private val academicDao: AcademicDao
) {

    // ═══════════════════════════════════════════════════════════════════════
    // 课表
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 读取"当前生效的课表"（`AppSettingsModel.currentCourseTableId`）。
     *
     * @return null = 没有任何课表 / 数据未就绪
     */
    suspend fun loadCourseTable(): CourseTableSnapshot? = withContext(Dispatchers.IO) {
        val settings = runCatching { appSettingsRepository.getAppSettingsOnce() }.getOrNull()
            ?: return@withContext null
        val tableId = settings.currentCourseTableId
        if (tableId.isBlank()) return@withContext null

        val table = runCatching { courseTableDao.getCourseTableById(tableId) }.getOrNull()
            ?: return@withContext null
        val config = runCatching { courseTableConfigDao.getConfigOnce(tableId) }.getOrNull()

        CourseTableSnapshot(
            tableId = tableId,
            tableName = table.name,
            semesterStartDate = config?.semesterStartDate
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            totalWeeks = config?.semesterTotalWeeks ?: 0,
            firstDayOfWeek = config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value,
            timeSlots = runCatching { timeSlotDao.getTimeSlotsByCourseTableId(tableId).first() }
                .getOrDefault(emptyList())
                .associateBy { it.number },
            courses = runCatching { courseDao.getCoursesWithWeeksByTableId(tableId).first() }
                .getOrDefault(emptyList()),
            skippedDates = settings.skippedDates
        )
    }

    /**
     * 某个日期属于第几周。
     *
     * **算法与 `AppSettingsRepository` / `ExamItemMapper` / 小组件完全一致**：
     * 把开学日期与目标日期都对齐到该课表设置的"一周起始日"，
     * 再取两者相差的整周数 + 1。
     *
     * 之所以必须照抄同一套对齐规则：若这里算出来的周次与课表网格差一周，
     * AI 会信誓旦旦地告诉用户"周三没课"，而用户打开课表看到的却是满课。
     *
     * @return 0 表示该日期在开学之前
     */
    fun weekNumberOf(date: LocalDate, semesterStart: LocalDate, firstDayOfWeek: Int): Int {
        val first = DayOfWeek.of(firstDayOfWeek.coerceIn(1, 7))
        val alignedStart = semesterStart.with(TemporalAdjusters.previousOrSame(first))
        val alignedDate = date.with(TemporalAdjusters.previousOrSame(first))
        if (alignedDate.isBefore(alignedStart)) return 0
        return ChronoUnit.WEEKS.between(alignedStart, alignedDate).toInt() + 1
    }

    /**
     * 当前是第几周。
     *
     * @return null = 未设置开学日期、未在学期内、或超出总周数
     */
    suspend fun currentWeek(today: LocalDate = LocalDate.now()): Int? {
        val table = loadCourseTable() ?: return null
        val start = table.semesterStartDate ?: return null
        if (table.totalWeeks <= 0) return null
        val week = weekNumberOf(today, start, table.firstDayOfWeek)
        return week.takeIf { it in 1..table.totalWeeks }
    }

    /**
     * 展开指定日期范围内的每一次上课。
     *
     * 与课表网格保持一致的两点：
     * 1. 用 [weekNumberOf] 判定周次，再匹配 `course_weeks`；
     * 2. **跳过** `skippedDates`（法定节假日）—— 与 Ics 导出、小组件同一口径，
     *    否则 AI 会告诉用户"国庆假期里周三有课"。
     *
     * @return 按日期、上课时间升序；无数据或未设开学日期时返回空列表
     */
    suspend fun coursesInRange(start: LocalDate, end: LocalDate): List<CourseOccurrence> =
        withContext(Dispatchers.IO) {
            val table = loadCourseTable() ?: return@withContext emptyList()
            val semesterStart = table.semesterStartDate ?: return@withContext emptyList()

            val out = ArrayList<CourseOccurrence>()
            var cursor = start
            while (!cursor.isAfter(end)) {
                val dateKey = cursor.toString()
                if (dateKey !in table.skippedDates) {
                    val week = weekNumberOf(cursor, semesterStart, table.firstDayOfWeek)
                    if (week >= 1) {
                        val day = cursor.dayOfWeek.value
                        table.courses.forEach { courseWithWeeks ->
                            val course = courseWithWeeks.course
                            if (course.day == day &&
                                courseWithWeeks.weeks.any { it.weekNumber == week }
                            ) {
                                out += courseWithWeeks.toOccurrence(cursor, week, table.timeSlots)
                            }
                        }
                    }
                }
                cursor = cursor.plusDays(1)
            }
            out.sortedWith(compareBy({ it.date }, { it.sortKey }))
        }

    /**
     * 某一天的课表（供规则前置直接回答"明天有什么课"）。
     */
    suspend fun coursesOn(date: LocalDate): List<CourseOccurrence> = coursesInRange(date, date)

    /** `CourseWithWeeks` → 展开后的一次上课。 */
    private fun CourseWithWeeks.toOccurrence(
        date: LocalDate,
        week: Int,
        timeSlots: Map<Int, TimeSlot>
    ): CourseOccurrence {
        val course = this.course
        val startSection = course.startSection
        val endSection = course.endSection

        val startTime: String
        val endTime: String
        val sectionLabel: String
        val sortKey: String

        if (course.isCustomTime) {
            startTime = course.customStartTime.orEmpty()
            endTime = course.customEndTime.orEmpty()
            sectionLabel = "自定义时间"
            sortKey = startTime.ifBlank { "99:99" }
        } else {
            startTime = startSection?.let { timeSlots[it]?.startTime }.orEmpty()
            endTime = endSection?.let { timeSlots[it]?.endTime }.orEmpty()
            sectionLabel = when {
                startSection != null && endSection != null && endSection != startSection ->
                    "第${startSection}-${endSection}节"

                startSection != null -> "第${startSection}节"
                else -> "节次未知"
            }
            sortKey = startTime.ifBlank {
                startSection?.let { String.format(java.util.Locale.US, "%02d:00", it) } ?: "99:99"
            }
        }

        return CourseOccurrence(
            date = date,
            dayOfWeek = date.dayOfWeek.value,
            weekNumber = week,
            name = course.name.ifBlank { "未命名课程" },
            teacher = course.teacher.ifBlank { "—" },
            position = course.position.ifBlank { "—" },
            startTime = startTime,
            endTime = endTime,
            sectionLabel = sectionLabel,
            sortKey = sortKey
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 成绩与学分
    // ═══════════════════════════════════════════════════════════════════════

    /** 全部成绩记录（按学年学期降序，与成绩页一致）。 */
    suspend fun allGrades(): List<GradeEntity> = withContext(Dispatchers.IO) {
        runCatching { gradeDao.getAll().first() }.getOrDefault(emptyList())
    }

    /**
     * 按课程名过滤成绩（子串匹配，忽略大小写与首尾空白）。
     *
     * @param courseName 为空则返回全部
     */
    suspend fun gradesMatching(courseName: String?): List<GradeEntity> {
        val all = allGrades()
        val keyword = courseName?.trim().orEmpty()
        if (keyword.isEmpty()) return all
        return all.filter { it.kcmc.contains(keyword, ignoreCase = true) }
    }

    /**
     * 学分 / 绩点 / 非正式学时汇总。
     *
     * 计算全部在这里完成，模型只负责把数字翻译成人话。
     */
    suspend fun creditSummary(): CreditSummary = withContext(Dispatchers.IO) {
        val grades = allGrades()
        val categories = planProgress()
        val nonFormal = nonFormalSummary()

        val requiredTotal = categories.sumOf { it.requiredCredits.toDouble() }.toFloat()
        val earnedTotal = categories.sumOf { it.earnedCredits.toDouble() }.toFloat()

        CreditSummary(
            gpa = calculateGpa(grades),
            gradeCount = grades.size,
            gradeBasedCredits = grades.sumOf { it.xf.toFloatOrNull()?.toDouble() ?: 0.0 }.toFloat(),
            failedCourseNames = grades.filter { isFailed(it) }.map { it.kcmc }.distinct(),
            hasPlan = categories.isNotEmpty(),
            planRequiredTotal = requiredTotal,
            planEarnedTotal = earnedTotal,
            planGapTotal = (requiredTotal - earnedTotal).coerceAtLeast(0f),
            categories = categories,
            nonFormal = nonFormal
        )
    }

    /**
     * 绩点 = Σ(学分 × 绩点) ÷ Σ(学分)。
     *
     * **算法与 `GradeViewModel.calculateGpa` 逐字一致** —— 这是刻意的：
     * AI 说的绩点必须和【成绩查询】页顶部的数字相同，否则用户会以为某一处在骗人。
     * 仅当 `xf > 0` 且 `jd > 0` 时纳入累计；总学分为 0 时返回 0，绝不产生 NaN。
     */
    fun calculateGpa(grades: List<GradeEntity>): Float {
        var totalXf = 0f
        var totalXfJd = 0f
        for (grade in grades) {
            val xf = grade.xf.toFloatOrNull() ?: 0f
            val jd = grade.jd.toFloatOrNull() ?: 0f
            if (xf > 0f && jd > 0f) {
                totalXf += xf
                totalXfJd += xf * jd
            }
        }
        return if (totalXf == 0f) 0.00f else totalXfJd / totalXf
    }

    /**
     * 本地判定是否不及格。
     *
     * 教务的 `cj` 既可能是数字也可能是文字，因此两条规则：
     * - 能解析成数字且 < 60 → 不及格
     * - 文本里含"不及格" → 不及格
     *
     * 明确**不**处理的形态：`缓考` / `缺考` / `—`。它们既不是及格也不是不及格，
     * 归入"未知"比强行归类更诚实（这些行仍会原样出现在成绩明细里）。
     */
    private fun isFailed(grade: GradeEntity): Boolean {
        val raw = grade.cj.trim()
        if (raw.contains("不及格")) return true
        val score = raw.toFloatOrNull() ?: return false
        return score < 60f
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 培养计划
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 培养计划的**类别级**汇总（每个根节点的"要求 / 已修 / 差额"）。
     *
     * 刻意只到类别这一层，不返回全部节点明细：整棵树序列化后 token 量很大，
     * 且上下文越长模型注意力越分散（§9.2 上下文分层）。
     * 需要明细时由模型调 `get_plan_progress` 指定类别再查。
     *
     * @return 空列表 = 用户还没同步学业情况
     */
    suspend fun planProgress(): List<PlanCategoryProgress> = withContext(Dispatchers.IO) {
        val nodes = runCatching { academicDao.observePlanNodes().first() }
            .getOrDefault(emptyList())
        if (nodes.isEmpty()) return@withContext emptyList()

        val courses = runCatching { academicDao.observeCourses().first() }
            .getOrDefault(emptyList())
        val coursesByNode = courses.groupBy { it.nodeId }
        val knownIds = nodes.map { it.id }.toSet()

        val childrenOf = HashMap<String, MutableList<String>>()
        nodes.forEach { node ->
            val parent = node.parentId?.takeIf { it in knownIds }
            if (parent != null) childrenOf.getOrPut(parent) { mutableListOf() } += node.id
        }

        // 自底向上汇总课程数。带 visited 防环 —— 教务数据异常时不能让递归爆栈。
        val visited = HashSet<String>()
        fun countCourses(nodeId: String): Int {
            if (!visited.add(nodeId)) return 0
            var count = coursesByNode[nodeId].orEmpty().size
            childrenOf[nodeId].orEmpty().forEach { count += countCourses(it) }
            return count
        }

        nodes.filter { it.parentId == null || it.parentId !in knownIds }
            .map { root -> root.toProgress(countCourses(root.id)) }
    }

    private fun AcademicPlanNodeEntity.toProgress(courseCount: Int): PlanCategoryProgress =
        PlanCategoryProgress(
            name = name.ifBlank { "未命名类别" },
            requiredCredits = requiredCredits,
            earnedCredits = earnedCredits,
            gapCredits = (requiredCredits - earnedCredits).coerceAtLeast(0f),
            courseCount = courseCount
        )

    // ═══════════════════════════════════════════════════════════════════════
    // 非正式学时（第二类课）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 非正式学时汇总 + 明细。
     *
     * 注意：第二类课的 `credits` 实测恒为 "0"（非正式课程不计学分），
     * 学时数看 `hours`（教务字段 `xssh`）—— 这是**正常现象**，不是数据坏了。
     */
    suspend fun nonFormalSummary(): NonFormalSummary = withContext(Dispatchers.IO) {
        val rows = runCatching { academicDao.observeNonFormalCourses().first() }
            .getOrDefault(emptyList())
        buildNonFormal(rows)
    }

    private fun buildNonFormal(rows: List<AcademicNonFormalCourseEntity>): NonFormalSummary =
        NonFormalSummary(
            totalHours = rows.sumOf { it.hours.trim().toIntOrNull() ?: 0 },
            courseCount = rows.size,
            items = rows.map { row ->
                NonFormalItem(
                    courseName = row.courseName.ifBlank { "—" },
                    hours = row.hours.trim().toIntOrNull() ?: 0,
                    result = row.result.ifBlank { "—" },
                    yearTerm = formatYearTerm(row.academicYear, row.term)
                )
            }
        )

    /**
     * 拼「学年 学期」文案：`("2024-2025", "1")` → `"2024-2025 第1学期"`。
     *
     * 与 `AcademicTreeBuilder.formatYearTerm` 的**输出口径一致**（那边是私有的，
     * 且属于 UI 层，data 层不应反向依赖）。两者若哪天不一致，
     * 会出现"AI 说 2024-2025 第1学期、页面写 2024-2025 1"这种尴尬。
     */
    private fun formatYearTerm(academicYear: String, term: String): String {
        val year = academicYear.trim()
        val t = term.trim()
        if (year.isEmpty() && t.isEmpty()) return ""
        val termLabel = when (t) {
            "1" -> "第1学期"
            "2" -> "第2学期"
            "" -> ""
            else -> "第${t}学期"
        }
        return listOf(year, termLabel).filter { it.isNotEmpty() }.joinToString(" ")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 考试
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 考试列表。
     *
     * @param onlyUpcoming true = 只保留"还没考完"的（结束时间 >= 今天 00:00）
     * @param today 便于测试注入"今天"
     *
     * 时间解析复用 [parseExamKssj]，因此与【考试安排】页的倒计时判定**必然一致**。
     * 解析不出时间的记录在 [onlyUpcoming] 模式下**保留**（宁可多显示也不要漏掉一场考试），
     * 其 `daysUntil` 为 null。
     */
    suspend fun exams(
        onlyUpcoming: Boolean,
        today: LocalDate = LocalDate.now()
    ): List<AiExamItem> = withContext(Dispatchers.IO) {
        val rows = runCatching { examDao.getAll().first() }.getOrDefault(emptyList())
        rows.mapNotNull { it.toAiExam(today) }
            .filter { item ->
                if (!onlyUpcoming) true
                else {
                    val days = item.daysUntil
                    days == null || days >= 0
                }
            }
            .sortedWith(compareBy({ it.daysUntil ?: Long.MAX_VALUE }, { it.rawTime }))
    }

    private fun ExamEntity.toAiExam(today: LocalDate): AiExamItem? {
        if (kcmc.isBlank() && ksmc.isBlank() && kssj.isBlank()) return null

        val parsed = parseExamKssj(kssj)
        val daysUntil = parsed?.let {
            ChronoUnit.DAYS.between(today, it.date)
        }

        return AiExamItem(
            name = ksmc.ifBlank { "考试" },
            course = kcmc.ifBlank { "—" },
            rawTime = kssj.ifBlank { "时间待定" },
            // 与 ExamScreen 的场地口径一致：校区 + 场地名称，空格分隔
            venue = listOf(cdxqmc, cdmc).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { "场地待定" },
            campus = cdxqmc.ifBlank { "—" },
            daysUntil = daysUntil
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 本周 / 下周摘要（供上下文基础层注入）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 取"本周 + 下周"的上课明细。
     *
     * 方案文档 §6.7 的注入口径：课表**只注入本周 + 下周**，不注入全学期。
     * 全学期约 100+ 条，既贵又会让模型注意力分散，而用户问得最多的就是这两天。
     *
     * @return 空列表 = 未设开学日期或不在学期内（调用方需如实说明）
     */
    suspend fun currentAndNextWeekOccurrences(
        today: LocalDate = LocalDate.now()
    ): List<CourseOccurrence> = withContext(Dispatchers.IO) {
        val table = loadCourseTable() ?: return@withContext emptyList()
        val semesterStart = table.semesterStartDate ?: return@withContext emptyList()
        val first = DayOfWeek.of(table.firstDayOfWeek.coerceIn(1, 7))

        val weekStart = today.with(TemporalAdjusters.previousOrSame(first))
        // 两周窗口：本周一 → 下周日
        coursesInRange(weekStart, weekStart.plusDays(13))
    }

    /** 某个日期所在周的周一（按课表设置的一周起始日对齐）。 */
    suspend fun weekStartOf(date: LocalDate): LocalDate? {
        val table = loadCourseTable() ?: return null
        val first = DayOfWeek.of(table.firstDayOfWeek.coerceIn(1, 7))
        return date.with(TemporalAdjusters.previousOrSame(first))
    }
}
