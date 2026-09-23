package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamEntity
import com.xingheyuzhuan.shiguangschedule.data.model.parseExamKssj
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * 考试展示模型，包装原始考试实体并附加倒计时与状态信息。
 */
data class ExamUiModel(
    val exam: ExamEntity,
    val countdownText: String,
    val isFinished: Boolean,
    val statusColor: Color
)

/**
 * 考试时间解析正则 —— 兼容两种格式：
 * - "2026-06-29 14:30-16:30"（空格 + 范围）
 * - "2026-06-29(14:30-16:30)"（括号包裹）
 *
 * ⚠️ v1.8.0 起**已迁出**：本文件原先自带一份正则，与
 * `data/network/ExamItemMapper` 里的那份完全重复。
 * P-AI 的本地问答也要判断"还有几门没考"，若再抄一份就是第三份，
 * 因此统一到 [com.xingheyuzhuan.shiguangschedule.data.model.parseExamKssj]。
 * 这样 AI 的回答与【考试安排】页的倒计时**必然一致**。
 */

/** 倒计时分级颜色 */
private object CountdownColors {
    val finished = Color(0xFFBDBDBD)       // 灰色
    val inProgress = Color(0xFF10B981)     // 亮绿
    val urgent = Color(0xFFEF4444)         // 红色
    val tomorrow = Color(0xFFF97316)       // 深橙
    val approaching = Color(0xFFF59E0B)    // 橙色
    val normal = Color(0xFF6366F1)         // 主色调
}

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val examDao: ExamDao
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _allExams: StateFlow<List<ExamEntity>> = examDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allExams: StateFlow<List<ExamEntity>> = _allExams

    // --- 学期列表 ---

    val availableTerms: StateFlow<List<String>> = _allExams.map { exams ->
        exams.map { resolveTerm(it.kssj) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- 选中学期 ---

    private val _selectedTerm = MutableStateFlow<String?>(null)

    val selectedTerm: StateFlow<String> = combine(availableTerms, _selectedTerm) { terms, selected ->
        selected ?: terms.firstOrNull() ?: ""
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- 当前学期考试列表（ExamUiModel + 倒计时 + 沉底排序） ---

    val displayedExams: StateFlow<List<ExamUiModel>> = combine(
        _allExams, availableTerms, _selectedTerm
    ) { exams, terms, selected ->
        val effectiveTerm = selected ?: terms.firstOrNull() ?: ""
        val filtered = if (effectiveTerm.isBlank()) exams
        else exams.filter { resolveTerm(it.kssj) == effectiveTerm }

        val now = LocalDateTime.now()

        filtered.map { exam ->
            val parsed = parseExamTime(exam.kssj)
            if (parsed != null) {
                val (examDate, startTime, endTime) = parsed
                computeCountdownStatus(exam, now, LocalDateTime.of(examDate, startTime), LocalDateTime.of(examDate, endTime))
            } else {
                ExamUiModel(exam = exam, countdownText = "", isFinished = false, statusColor = CountdownColors.normal)
            }
        }.let { list ->
            val unfinished = list.filter { !it.isFinished }.sortedBy { getExamStartInstant(it.exam) }
            val finished = list.filter { it.isFinished }.sortedBy { getExamStartInstant(it.exam) }
            unfinished + finished
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectTerm(term: String?) {
        _selectedTerm.value = term
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 纯函数：倒计时 & 时间解析
    // ═══════════════════════════════════════════════════════════════════════

    internal fun parseExamTime(kssj: String): Triple<LocalDate, LocalTime, LocalTime>? =
        parseExamKssj(kssj)?.let { Triple(it.date, it.start, it.end) }

    private fun computeCountdownStatus(exam: ExamEntity, now: LocalDateTime, start: LocalDateTime, end: LocalDateTime): ExamUiModel {
        return if (now.isAfter(end)) {
            ExamUiModel(exam, "已结束", true, CountdownColors.finished)
        } else if (now.isAfter(start) && now.isBefore(end)) {
            ExamUiModel(exam, "进行中", false, CountdownColors.inProgress)
        } else if (now.toLocalDate() == start.toLocalDate()) {
            val t = start.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
            ExamUiModel(exam, "今天 $t", false, CountdownColors.urgent)
        } else {
            val days = Duration.between(now.toLocalDate().atStartOfDay(), start.toLocalDate().atStartOfDay()).toDays()
            when {
                days == 1L -> ExamUiModel(exam, "明天", false, CountdownColors.tomorrow)
                days in 2..3 -> ExamUiModel(exam, "剩 ${days} 天", false, CountdownColors.approaching)
                days > 3 -> ExamUiModel(exam, "还有 ${days} 天", false, CountdownColors.normal)
                else -> ExamUiModel(exam, "今天", false, CountdownColors.urgent)
            }
        }
    }

    private fun getExamStartInstant(exam: ExamEntity): LocalDateTime {
        val p = parseExamTime(exam.kssj) ?: return LocalDateTime.of(9999, 12, 31, 23, 59)
        return LocalDateTime.of(p.first, p.second)
    }

    private fun resolveTerm(kssj: String): String {
        val parts = kssj.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return ""
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return ""
        val semester = when (month) {
            in 9..12, in 1..2 -> "第一学期"
            in 3..7 -> "第二学期"
            else -> return ""
        }
        val academicYear = if (month in 1..7) "${year - 1}-$year" else "$year-${year + 1}"
        return "$academicYear $semester"
    }
}
