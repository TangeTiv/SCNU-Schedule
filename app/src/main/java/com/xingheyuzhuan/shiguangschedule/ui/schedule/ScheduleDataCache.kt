package com.xingheyuzhuan.shiguangschedule.ui.schedule

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨 ViewModel 实例的课表 UI 状态缓存。
 *
 * 一级页面切换（如【校园】→【课表】）会销毁并重建 WeeklyScheduleViewModel，
 * 导致其内部的 Flow 需要重新查询数据库、重新合并课程，出现短暂的内容空白。
 * 通过单例缓存上次计算好的 UI 状态，使页面重建时能立即恢复内容，避免空白闪烁。
 */
@Singleton
class ScheduleDataCache @Inject constructor() {
    private val _state = MutableStateFlow(WeeklyScheduleUiState())
    val state: StateFlow<WeeklyScheduleUiState> = _state.asStateFlow()

    fun update(state: WeeklyScheduleUiState) {
        // 累积历史已计算的周次：即使滑动窗口移动，之前访问过的周次数据仍被保留，
        // 使页面重建（如一级页面切换回来）时即使定位到其他周次也能立即显示内容。
        val mergedCache = _state.value.courseCache + state.courseCache
        _state.value = state.copy(courseCache = mergedCache)
    }
}
