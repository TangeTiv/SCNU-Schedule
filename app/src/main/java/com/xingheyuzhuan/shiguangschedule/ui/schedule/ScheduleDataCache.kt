package com.xingheyuzhuan.shiguangschedule.ui.schedule

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨 ViewModel 实例的课表状态缓存。
 *
 * 一级页面切换（如【校园】→【课表】）会销毁并重建 WeeklyScheduleViewModel，
 * 导致其内部的 Flow 需要重新查询数据库、重新合并课程，出现短暂的内容空白。
 * 通过单例缓存上次计算好的 UI 状态与课程数据，使页面重建时能立即恢复内容。
 *
 * 课程缓存使用 [SnapshotStateMap] 保存：按周次日期键控且按结构相等比较，
 * 滑动窗口移动时未变化的周次不会触发重组，从而避免翻页（含落定后）的整页重组卡顿。
 */
@Singleton
class ScheduleDataCache @Inject constructor() {
    private val _state = MutableStateFlow(WeeklyScheduleUiState())
    val state: StateFlow<WeeklyScheduleUiState> = _state.asStateFlow()

    private val _courseCache = mutableStateMapOf<String, List<MergedCourseBlock>>()
    val courseCache: SnapshotStateMap<String, List<MergedCourseBlock>> = _courseCache

    private val _weekIndexInPager = MutableStateFlow<Int?>(null)
    val weekIndexInPager: StateFlow<Int?> = _weekIndexInPager.asStateFlow()

    fun update(
        state: WeeklyScheduleUiState,
        newCourseCache: Map<String, List<MergedCourseBlock>>,
        weekIndexInPager: Int?
    ) {
        _state.value = state
        // 累积历史已计算的周次：即使滑动窗口移动，之前访问过的周次数据仍被保留，
        // 使页面重建时即使定位到其他周次也能立即显示内容。
        _courseCache.putAll(newCourseCache)
        _weekIndexInPager.value = weekIndexInPager
    }
}
