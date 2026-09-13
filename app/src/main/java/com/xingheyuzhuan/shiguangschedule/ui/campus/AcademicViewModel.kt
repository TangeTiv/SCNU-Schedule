package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * 【学业情况】页面 ViewModel。
 *
 * ## 状态拆分（对应性能红线 5）
 *
 * 刻意**不使用一个巨型 UiState**，而是按模块拆成多个独立 StateFlow：
 *
 * | StateFlow | 内容 | 变化频率 |
 * |---|---|---|
 * | [academicState] | 培养计划树（四个教育类） | 仅在同步后变化 |
 * | [nonFormalState] | 非正式学时列表 | 仅在同步后变化 |
 * | [selectedLevel1] | 当前一级标签下标 | 用户点击 |
 * | [selectedLevel2ByLevel1] | 各一级标签下的二级标签下标 | 用户点击 |
 *
 * 这样「切标签」只会让标签行重组，不会波及课程列表；反过来同步完成后
 * 也不会因为重建树而把标签选中状态一起刷新。
 *
 * 折叠面板的展开状态属于纯 UI 瞬时状态，放在 Composable 里用
 * `SnapshotStateMap` 管理，不进 ViewModel。
 *
 * ## 线程（对应性能红线 2）
 *
 * [AcademicTreeBuilder.build] 要做 33 个节点的建树 + 135 条课程的分组排序与
 * 字符串格式化，全部是 CPU 工作，因此整条管道以 `.flowOn(Dispatchers.Default)`
 * 收尾，绝不在主线程执行。Room 本身已在后台线程，但 `combine`/`map` 的算子
 * 默认跟随收集方的上下文，必须显式指定。
 */
@HiltViewModel
class AcademicViewModel @Inject constructor(
    private val academicDao: AcademicDao
) : ViewModel() {

    companion object {
        private const val TAG = "AcademicVM"
    }

    /**
     * 培养计划树状态。
     *
     * `SharingStarted.WhileSubscribed(5000)` 与项目其他 ViewModel 保持一致：
     * 页面退到后台 5 秒后停止上游查询，避免无谓的数据库订阅。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val academicState: StateFlow<AcademicUiState> = combine(
        academicDao.observePlanNodes(),
        academicDao.observeCourses()
    ) { nodes, courses ->
        // 诊断日志：把「库里有几条」和「建树后落到各节点上有几条」都打出来。
        // 排查「界面显示暂无课程明细」时对着看：
        // - nodes 非空、courses 为 0        → 抓取或落库没写入课程
        // - courses 非空但 attached 明显偏少 → 课程行的 nodeId 与计划点 id 对不上
        val state = AcademicTreeBuilder.build(nodes, courses)
        val attached = state.flatNodes.sumOf { it.courses.size }
        Log.i(
            TAG,
            "学业树构建：库中计划点 ${nodes.size} 个 / 课程 ${courses.size} 门，" +
                    "挂到节点上 $attached 门，一级标签 ${state.level1Nodes.size} 个"
        )
        state
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AcademicUiState.Empty
        )

    /** 非正式学时状态。独立管道，与培养计划树互不影响。 */
    val nonFormalState: StateFlow<NonFormalUiState> = academicDao.observeNonFormalCourses()
        .map { AcademicTreeBuilder.buildNonFormal(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NonFormalUiState.Empty
        )

    // ── 标签选中状态 ──

    private val _selectedLevel1 = MutableStateFlow(0)

    /** 当前一级标签下标；越界由 UI 侧 clamp。 */
    val selectedLevel1: StateFlow<Int> = _selectedLevel1.asStateFlow()

    private val _selectedLevel2ByLevel1 = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 一级标签 id → 该标签下当前选中的二级标签下标。 */
    val selectedLevel2ByLevel1: StateFlow<Map<String, Int>> =
        _selectedLevel2ByLevel1.asStateFlow()

    /**
     * 切换一级标签。
     *
     * 同时**清空该一级标签下二级标签的选中记录**，使下次进入时回到第一个子标签。
     *
     * 为什么不保留上次的位置：二级标签是一组互斥的**视图切换器**（不是导航），
     * 用户切走再切回时如果还停在上次的位置，很容易误以为内容变了、
     * 或漏看了别的选项。回到第一个是这类控件最稳定的预期。
     *
     * 实现上不需要知道"上一个"是哪个：UI 侧读取时对缺失的 key 默认取下标 0，
     * 所以这里只需把所有记录清掉即可 —— 一条 map 操作，没有额外状态。
     */
    fun selectLevel1(index: Int) {
        _selectedLevel1.value = index
        _selectedLevel2ByLevel1.value = emptyMap()
    }

    /** 切换某个一级标签下的二级标签。 */
    fun selectLevel2(level1Id: String, index: Int) {
        _selectedLevel2ByLevel1.update { current ->
            current + (level1Id to index)
        }
    }
}
