package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 成绩查询 ViewModel。
 *
 * 纯响应式设计——所有状态均由 Room Flow 驱动派生，无 init {} 副作用。
 *
 * 核心推导链：
 * 1. gradeDao.getAll() 是唯一的 Room 订阅（热流）
 * 2. availableTerms 由 allGrades 派生（去重 + 排序）
 * 3. _selectedTerm 初始为 null，通过 combine 优雅回退到第一个可用学期
 * 4. displayedGrades / termGpa 通过 combine(allGrades, availableTerms, _selectedTerm) 派生
 * 5. totalGpa 仅依赖 allGrades
 */
@HiltViewModel
class GradeViewModel @Inject constructor(
    private val gradeDao: GradeDao
) : ViewModel() {

    // --- 唯一 Room 订阅（共享热流） ---

    @OptIn(ExperimentalCoroutinesApi::class)
    private val _allGrades: StateFlow<List<GradeEntity>> = gradeDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allGrades: StateFlow<List<GradeEntity>> = _allGrades

    // --- 学期列表（去重 + 降序，无副作用） ---

    val availableTerms: StateFlow<List<String>> = _allGrades.map { grades ->
        grades.map { "${it.xnmmc} ${termLabel(it.xqmmc)}" }
            .distinct()
            .sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- 用户选中学期（null = 尚未选择，自动回退到首个可用学期） ---

    private val _selectedTerm = MutableStateFlow<String?>(null)

    /**
     * 当前生效的学期。
     * 若用户尚未手动选择（null），则自动取 availableTerms 的首项；
     * 若 availableTerms 也为空，则返回空字符串。
     */
    val selectedTerm: StateFlow<String> = combine(availableTerms, _selectedTerm) { terms, selected ->
        selected ?: terms.firstOrNull() ?: ""
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // --- 当前学期成绩列表 ---

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayedGrades: StateFlow<List<GradeEntity>> = combine(
        _allGrades, availableTerms, _selectedTerm
    ) { grades, terms, selected ->
        val effectiveTerm = selected ?: terms.firstOrNull() ?: ""
        if (effectiveTerm.isBlank()) grades
        else grades.filter { "${it.xnmmc} ${termLabel(it.xqmmc)}" == effectiveTerm }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- 总平均绩点（全学期） ---

    val totalGpa: StateFlow<Float> = _allGrades.map { grades ->
        calculateGpa(grades)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // --- 当前学期平均绩点 ---

    @OptIn(ExperimentalCoroutinesApi::class)
    val termGpa: StateFlow<Float> = combine(
        _allGrades, availableTerms, _selectedTerm
    ) { grades, terms, selected ->
        val effectiveTerm = selected ?: terms.firstOrNull() ?: ""
        if (effectiveTerm.isBlank()) {
            calculateGpa(grades)
        } else {
            calculateGpa(grades.filter { "${it.xnmmc} ${termLabel(it.xqmmc)}" == effectiveTerm })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    // --- 用户操作 ---

    /**
     * 用户手动切换学期过滤器。
     * 传入 null 即恢复默认（首个可用学期）。
     */
    fun selectTerm(term: String?) {
        _selectedTerm.value = term
    }

    // --- 纯函数：学期标签映射 ---

    /**
     * 将学期编码（如 "1", "第一学期"）统一映射为可读的学期标签 "第一学期"。
     */
    private fun termLabel(raw: String): String = when (raw.trim()) {
        "1" -> "第一学期"
        "2" -> "第二学期"
        "3" -> "第三学期"
        else -> raw
    }

    // --- 纯函数：GPA 计算 ---

    /**
     * 计算 GPA = Σ(学分 × 绩点) ÷ Σ(学分)。
     *
     * 安全策略：
     * - 所有 String → Float 解析使用 toFloatOrNull() ?: 0f
     * - 仅当 xf > 0f 且 jd > 0f 时纳入累计
     * - 总学分 == 0f 时硬编码返回 0.00f，绝不抛 NaN 或 Infinity
     */
    private fun calculateGpa(grades: List<GradeEntity>): Float {
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
}
