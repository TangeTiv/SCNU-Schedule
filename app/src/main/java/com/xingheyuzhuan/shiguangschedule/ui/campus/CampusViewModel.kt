package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableConfig
import com.xingheyuzhuan.shiguangschedule.data.db.main.TimeSlot
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.CourseTableRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.SchoolRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.TimeSlotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * 校园模块 ViewModel。
 *
 * 职责：
 * 1. 加载今日课程数据供「今日速览」胶囊行展示
 * 2. 持久化同步选项至 DataStore（供 AndroidBridge JS 读取）
 * 3. 查找 SCNU（华南师范大学）适配器并构造 WebView 直达跳转
 */
@HiltViewModel
class CampusViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    private val courseTableRepository: CourseTableRepository,
    private val timeSlotRepository: TimeSlotRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * 校园首页 UI 状态流。
     * 包含学期周次与今日课程列表，用于 WelcomeCard 信息展示与胶囊行渲染。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val campusState: StateFlow<CampusUiState> = appSettingsRepository.getAppSettings()
        .flatMapLatest { settings ->
            val tableId = settings.currentCourseTableId
            val today = LocalDate.now()
            val dayOfWeek = today.dayOfWeek.value

            combine(
                appSettingsRepository.calculateCurrentWeekFromDb(),
                appSettingsRepository.getCourseTableConfigFlow(tableId),
                timeSlotRepository.getTimeSlotsByCourseTableId(tableId)
            ) { weekIndex: Int?, config: CourseTableConfig?, timeSlots: List<TimeSlot> ->

                val startDate = config?.semesterStartDate?.let {
                    try { LocalDate.parse(it) } catch (e: Exception) { null }
                }

                val isSkippedDay = settings.skippedDates.contains(today.toString())

                val canLoad = config?.semesterStartDate != null
                        && startDate != null && !today.isBefore(startDate)
                        && weekIndex != null && !isSkippedDay

                CampusDataSnapshot(weekIndex, canLoad, timeSlots)
            }.flatMapLatest { snapshot ->
                if (snapshot.canLoad && snapshot.weekIndex != null) {
                    courseTableRepository.getCoursesForDay(tableId, snapshot.weekIndex, dayOfWeek).map { courses ->
                        buildCampusUiState(courses, snapshot)
                    }
                } else {
                    flowOf(CampusUiState(weekNumber = snapshot.weekIndex, todayCourses = emptyList()))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CampusUiState())

    /**
     * 内部数据快照，用于在 Flow 管道各阶段间传递上下文。
     */
    private data class CampusDataSnapshot(
        val weekIndex: Int?,
        val canLoad: Boolean,
        val timeSlots: List<TimeSlot>
    )

    /**
     * 将 CourseWithWeeks 列表映射为 CampusUiState。
     */
    private fun buildCampusUiState(
        courses: List<com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWithWeeks>,
        snapshot: CampusDataSnapshot
    ): CampusUiState {
        val slotMap = snapshot.timeSlots.associateBy { it.number }

        val displayModels = courses.map { item ->
            val startSlot = slotMap[item.course.startSection]
            val endSlot = slotMap[item.course.endSection]
            TodayCourseDisplay(
                courseName = item.course.name,
                location = item.course.position.ifEmpty { "—" },
                startTime = item.course.customStartTime ?: startSlot?.startTime ?: "?",
                endTime = item.course.customEndTime ?: endSlot?.endTime ?: "?",
                colorIndex = item.course.colorInt
            )
        }.sortedBy { it.startTime }

        return CampusUiState(
            weekNumber = snapshot.weekIndex,
            todayCourses = displayModels
        )
    }

    /**
     * 开始同步入口。
     *
     * 先保存用户选择的同步开关，随后从已下发的 school_index 中
     * 匹配华师适配器信息，最终直接跳转至 WebView 页面（硬编码直达）。
     */
    fun onSyncStart(options: SyncOptions, onNavigate: (Destination) -> Unit) {
        viewModelScope.launch {
            // 1. 持久化同步选项
            appSettingsRepository.setSyncOptions(
                courses = options.courses,
                grades = options.grades,
                exams = options.exams
            )

            // 2. 从 school_index 匹配华师适配器（SCNU hardcode）
            val schools = SchoolRepository.getSchools(appContext)
            val scnu = schools.find { school ->
                school.id.contains("scnu", ignoreCase = true) ||
                school.name.contains("华南师范")
            }
            val adapter = scnu?.adapters?.firstOrNull()

            // 3. 直接跳转 WebView（绕过学校选择列表）
            onNavigate(
                Destination.WebView(
                    initialUrl = adapter?.import_url?.takeIf { it.isNotBlank() } ?: "about:blank",
                    assetJsPath = if (scnu != null && adapter != null) {
                        "${scnu.resource_folder}/${adapter.adapter_id}.js"
                    } else null
                )
            )
        }
    }
}

/**
 * 校园首页 UI 状态。
 *
 * @param weekNumber 学期当前周次；null 表示尚未配置开学日期
 * @param todayCourses 今日课程列表（按开始时间升序）
 */
data class CampusUiState(
    val weekNumber: Int? = null,
    val todayCourses: List<TodayCourseDisplay> = emptyList()
)

/**
 * 今日课程展示模型（精简版，仅供胶囊行使用）。
 *
 * @param courseName 课程名称
 * @param location 上课地点
 * @param startTime 开始时间 "HH:MM"
 * @param endTime 结束时间 "HH:MM"
 * @param colorIndex 课程颜色索引，对应 DEFAULT_COLOR_MAPS 下标
 */
data class TodayCourseDisplay(
    val courseName: String,
    val location: String,
    val startTime: String,
    val endTime: String,
    val colorIndex: Int
)
