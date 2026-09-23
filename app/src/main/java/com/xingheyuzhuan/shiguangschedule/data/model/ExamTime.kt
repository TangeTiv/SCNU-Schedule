package com.xingheyuzhuan.shiguangschedule.data.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 考试时间的解析结果。
 *
 * @param end 结束时间。教务只下发开始时间时，按 [DEFAULT_EXAM_DURATION_MINUTES] 推算。
 */
data class ExamTime(
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime
)

/**
 * 教务 `kssj` 字段的解析正则 —— 兼容两种实际格式：
 * - `"2026-06-29 14:30-16:30"`（空格 + 范围）
 * - `"2026-06-29(14:30-16:30)"`（括号包裹）
 *
 * 分组：1=日期（yyyy-MM-dd）、2=开始时间（HH:mm）、3=结束时间（HH:mm）
 */
private val KSSJ_REGEX = Regex(
    """(\d{4}-\d{2}-\d{2})\s*\(?(\d{2}:\d{2})-(\d{2}:\d{2})\)?"""
)

/** 教务只给开始时间时的默认考试时长（分钟）。 */
const val DEFAULT_EXAM_DURATION_MINUTES = 90L

/**
 * 解析考试时间。
 *
 * ## 为什么单独放在这里
 *
 * 这份正则原先在 `ui/campus/ExamViewModel` 与 `data/network/ExamItemMapper` 里
 * **各写了一份**。v1.8.0（P-AI）的本地数据问答也要判断"还有几门没考"，
 * 若再抄一份就是第三份 —— 因此统一到这里，三处共用同一份实现。
 *
 * 这一点对本功能尤其重要：**AI 的回答必须与【考试安排】页显示的一致**。
 * 两处用不同解析器时，同一场考试可能在页面上"还有 3 天"、在 AI 嘴里变成"已结束"。
 *
 * @return 解析失败（格式异常、日期/时间非法）返回 null
 */
fun parseExamKssj(kssj: String): ExamTime? {
    val match = KSSJ_REGEX.find(kssj) ?: return null
    return try {
        val date = LocalDate.parse(match.groupValues[1])
        val start = LocalTime.parse(match.groupValues[2])
        val endText = match.groupValues[3]
        val end = if (endText.isNotBlank()) {
            LocalTime.parse(endText)
        } else {
            start.plusMinutes(DEFAULT_EXAM_DURATION_MINUTES)
        }
        ExamTime(date = date, start = start, end = end)
    } catch (_: Exception) {
        null
    }
}
