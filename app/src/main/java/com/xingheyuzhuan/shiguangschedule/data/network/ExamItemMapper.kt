package com.xingheyuzhuan.shiguangschedule.data.network

import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

// ═══════════════════════════════════════════════════════════════════════════
// ExamItem → Course / CourseWeek 数据转换扩展函数
//
// 将教务系统考试安排转换为自定义时间（isCustomTime=true）的课程实体，
// 物理存入课程数据库，让课表网格、今日课表、小组件、日历导出等
// 所有下游消费方天然支持。
//
// 核心防御：
// 1. ID 前缀 "SYNC_EXAM_" → 后续清理的唯一凭证
// 2. 周次校验 [1, 30] → 拒绝跨学期脏数据
// 3. 时间解析异常 → return null，绝不崩溃
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 考试时间 kssj 解析正则。
 *
 * 支持所有实际教务格式：
 * - "2024-01-15 14:30-16:30"（空格 + 范围）
 * - "2025-01-10 09:00"（仅开始时间，结束时间自动推算 +90min）
 * - "2026-07-09(09:00-11:00)"（括号包裹，无空格）
 *
 * 分组：
 * 1: 日期 (yyyy-MM-dd)
 * 2: 开始时间 (HH:mm)
 * 3: 结束时间 (HH:mm)
 */
private val KSSJ_REGEX = Regex(
    """(\d{4}-\d{2}-\d{2})\s*\(?(\d{2}:\d{2})-(\d{2}:\d{2})\)?"""
)

/** 当 kssj 仅有开始时间时，默认考试时长为 90 分钟 */
private const val DEFAULT_EXAM_DURATION_MINUTES = 90L

/**
 * 将 [ExamItem] 转换为模拟课程 [Course] 及其关联的 [CourseWeek]。
 *
 * ## 安全护城河
 *
 * | 风险 | 防护 |
 * |------|------|
 * | kssj 格式异常 | 正则匹配失败 → return null |
 * | 跨学期脏数据 | weekNumber !in 1..30 → return null |
 * | 日期解析失败 | try-catch 包裹 → return null |
 *
 * ## 映射规则
 *
 * - `id = "SYNC_EXAM_" + UUID.randomUUID()` — 前缀用于定向清理
 * - `name = "【考试】" + kcmc` — 醒目标识
 * - `isCustomTime = true` — 触发绝对时间渲染
 * - `customStartTime / customEndTime` — 从 kssj 解析
 *
 * @param targetTableId 目标课表 ID
 * @param termStartDate 当前学期开学日期（用于计算周次）
 * @param colorInt 课程颜色索引（已由调用方安全校验）
 * @return Pair<Course, List<CourseWeek>>，解析失败返回 null
 */
fun ExamItem.toCourseEntity(
    targetTableId: String,
    termStartDate: LocalDate,
    colorInt: Int,
    firstDayOfWeek: Int = DayOfWeek.MONDAY.value
): Pair<Course, List<CourseWeek>>? {
    // ── 1. 解析 kssj ──
    val match = KSSJ_REGEX.find(kssj) ?: return null
    val dateStr = match.groupValues[1]
    val startTimeStr = match.groupValues[2]
    val endTimeStr = match.groupValues[3]

    val examDate = try {
        LocalDate.parse(dateStr)
    } catch (_: Exception) {
        return null
    }
    val startTime = try {
        LocalTime.parse(startTimeStr)
    } catch (_: Exception) {
        return null
    }
    val endTime = if (endTimeStr.isNotBlank()) {
        try { LocalTime.parse(endTimeStr) } catch (_: Exception) { return null }
    } else {
        // 仅有开始时间，推算结束时间
        startTime.plusMinutes(DEFAULT_EXAM_DURATION_MINUTES)
    }

    // ── 2. 计算坐标：周次 + 星期 ──
    // 必须与 AppSettingsRepository.getWeekIndexAtDate() 使用相同的对齐算法，
    // 否则与课表网格的周次计算不一致，导致考试显示在错误的周次。
    val dayOfWeekEnum = DayOfWeek.of(firstDayOfWeek)
    val alignedStart = termStartDate.with(TemporalAdjusters.previousOrSame(dayOfWeekEnum))
    val alignedExam = examDate.with(TemporalAdjusters.previousOrSame(dayOfWeekEnum))
    val weekNumber = ChronoUnit.WEEKS.between(alignedStart, alignedExam).toInt() + 1

    // 时空结界：拒绝跨学期脏数据
    if (weekNumber < 1 || weekNumber > 30) return null

    val dayOfWeek = examDate.dayOfWeek.value // 1=Monday, 7=Sunday

    // ── 3. 组装考场位置 ──
    val position = listOf(cdxqmc, cdmc, cdbh)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "考场待定" }

    // ── 4. 构造 Course 实体 ──
    val courseId = "SYNC_EXAM_${UUID.randomUUID()}"
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val course = Course(
        id = courseId,
        courseTableId = targetTableId,
        name = "【考试】${kcmc.trim().ifBlank { "未命名考试" }}",
        teacher = "",
        position = position,
        day = dayOfWeek,
        startSection = null,
        endSection = null,
        isCustomTime = true,
        customStartTime = startTimeStr,
        customEndTime = endTime.format(fmt),
        colorInt = colorInt.coerceIn(0, Int.MAX_VALUE),
        remark = null
    )

    // ── 5. 构造 CourseWeek ──
    val weeks = listOf(CourseWeek(courseId = courseId, weekNumber = weekNumber))

    return course to weeks
}

/**
 * [ExamEntity] 版本的考试→课程转换。
 *
 * 与 [ExamItem.toCourseEntity] 逻辑完全一致，但接收者改为 Room 实体，
 * 用于在 [WeeklyScheduleViewModel] 中直接从 examDao.getAll() 读取并融合。
 *
 * 两个版本保持独立的函数以避免相互依赖。
 */
fun ExamEntity.toCourseEntity(
    targetTableId: String,
    termStartDate: LocalDate,
    colorInt: Int,
    firstDayOfWeek: Int = DayOfWeek.MONDAY.value
): Pair<Course, List<CourseWeek>>? {
    // ── 1. 解析 kssj ──
    val match = KSSJ_REGEX.find(kssj) ?: return null
    val dateStr = match.groupValues[1]
    val startTimeStr = match.groupValues[2]
    val endTimeStr = match.groupValues[3]

    val examDate = try {
        LocalDate.parse(dateStr)
    } catch (_: Exception) {
        return null
    }
    val startTime = try {
        LocalTime.parse(startTimeStr)
    } catch (_: Exception) {
        return null
    }
    val endTime = if (endTimeStr.isNotBlank()) {
        try { LocalTime.parse(endTimeStr) } catch (_: Exception) { return null }
    } else {
        startTime.plusMinutes(DEFAULT_EXAM_DURATION_MINUTES)
    }

    // ── 2. 计算坐标：周次 + 星期 ──
    val dayOfWeekEnum = DayOfWeek.of(firstDayOfWeek)
    val alignedStart = termStartDate.with(TemporalAdjusters.previousOrSame(dayOfWeekEnum))
    val alignedExam = examDate.with(TemporalAdjusters.previousOrSame(dayOfWeekEnum))
    val weekNumber = ChronoUnit.WEEKS.between(alignedStart, alignedExam).toInt() + 1

    // 时空结界：拒绝跨学期脏数据
    if (weekNumber < 1 || weekNumber > 30) return null

    val dayOfWeek = examDate.dayOfWeek.value // 1=Monday, 7=Sunday

    // ── 3. 组装考场位置 ──
    val position = listOf(cdxqmc, cdmc, cdbh)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { "考场待定" }

    // ── 4. 构造 Course 实体 ──
    val courseId = "SYNC_EXAM_${UUID.randomUUID()}"
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val course = Course(
        id = courseId,
        courseTableId = targetTableId,
        name = "【考试】${kcmc.trim().ifBlank { "未命名考试" }}",
        teacher = "",
        position = position,
        day = dayOfWeek,
        startSection = null,
        endSection = null,
        isCustomTime = true,
        customStartTime = startTimeStr,
        customEndTime = endTime.format(fmt),
        colorInt = colorInt.coerceIn(0, Int.MAX_VALUE),
        remark = null
    )

    // ── 5. 构造 CourseWeek ──
    val weeks = listOf(CourseWeek(courseId = courseId, weekNumber = weekNumber))

    return course to weeks
}
