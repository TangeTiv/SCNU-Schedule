package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeekDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.toEntity
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import com.xingheyuzhuan.shiguangschedule.data.network.toCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.network.parseWeeks
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.StyleSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import kotlin.random.Random

/**
 * 同步流程的 UI 状态密封接口。
 *
 * 各分支代表同步生命周期中的四个阶段：
 * - [Idle]：等待用户操作
 * - [Loading]：正在进行某个子步骤，[message] 描述当前正在执行的操作
 * - [Success]：全部同步完成，[message] 为成功摘要
 * - [Error]：同步过程中发生异常，[errorMsg] 为具体错误描述
 */
sealed interface SyncUiState {
    /** 空闲状态，等待用户触发同步 */
    data object Idle : SyncUiState

    /** 同步进行中，[message] 描述当前正在执行的子步骤 */
    data class Loading(val message: String) : SyncUiState

    /** 同步圆满成功，[message] 为成功提示 */
    data class Success(val message: String) : SyncUiState

    /** 同步失败，[errorMsg] 为具体错误描述 */
    data class Error(val errorMsg: String) : SyncUiState
}

/**
 * 教务系统同步页面的 ViewModel。
 *
 * 负责将 [ScnuScraper]（网络）与 [GradeDao]/[ExamDao]（本地数据库）串联，
 * 暴露 [syncUiState] 供 UI 层驱动全屏 Loading 遮罩和 Toast 反馈。
 *
 * 账号（学号）会在登录成功后自动持久化到 DataStore，下次打开页面时预填；
 * 密码出于安全考虑**绝不**落盘存储。
 */
@HiltViewModel
class CampusSyncViewModel @Inject constructor(
    private val scraper: ScnuScraper,
    private val gradeDao: GradeDao,
    private val examDao: ExamDao,
    @Named("AppSettings") private val dataStore: DataStore<Preferences>,
    // ── 课程表同步所需依赖 ──
    private val courseDao: CourseDao,
    private val courseWeekDao: CourseWeekDao,
    private val courseTableDao: CourseTableDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    companion object {
        /** DataStore 键：上一次成功登录的学号 */
        val KEY_CAMPUS_ACCOUNT = stringPreferencesKey("campus_account")
    }

    // ── 同步状态 ──

    private val _syncUiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncUiState: StateFlow<SyncUiState> = _syncUiState.asStateFlow()

    // ── 持久化的学号（用于预填） ──

    private val _savedAccount = MutableStateFlow("")
    val savedAccount: StateFlow<String> = _savedAccount.asStateFlow()

    init {
        // 异步从 DataStore 加载上一次成功登录的学号，绝不阻塞主线程
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            _savedAccount.value = prefs[KEY_CAMPUS_ACCOUNT] ?: ""
        }
    }

    /**
     * 开始教务系统同步。
     *
     * 整个流程在 [Dispatchers.IO] 中执行，通过 [runCatching] 统一捕获异常，
     * 并通过 [_syncUiState] 实时反馈进度。
     *
     * @param account     学号
     * @param password    密码（仅用于本次登录，不持久化）
     * @param syncCourses 是否需要同步学期课程表
     * @param syncGrades  是否需要同步成绩数据
     * @param syncExams   是否需要同步考试安排
     */
    fun startSync(
        account: String,
        password: String,
        syncCourses: Boolean,
        syncGrades: Boolean,
        syncExams: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // ── 第 1 步：登录统一身份认证 ──
                _syncUiState.value = SyncUiState.Loading("正在登录统一身份认证…")
                scraper.login(account, password)

                // 登录成功 → 持久化学号，下次打开页面时自动预填
                dataStore.edit { prefs ->
                    prefs[KEY_CAMPUS_ACCOUNT] = account
                }
                _savedAccount.value = account

                // ── 第 2 步：抓取并写入学期课程表 ──
                if (syncCourses) {
                    _syncUiState.value = SyncUiState.Loading("正在拉取学期课程表…")

                    // 防线 4: 课表 API 不接受空 xnm/xqm，需根据当前月份计算
                    // 6-12 月查询当前学年第一学期(3), 1-5 月查询上一年第一学期(3)
                    val cal = Calendar.getInstance()
                    val year = cal.get(Calendar.YEAR)
                    val month = cal.get(Calendar.MONTH) + 1
                    val xnm = year.toString()
                    val xqm = if (month in 1..5) "12" else "3"

                    val courseItems = scraper.fetchCourses(xnm = xnm, xqm = xqm)

                    if (courseItems.isNotEmpty()) {
                        _syncUiState.value = SyncUiState.Loading("正在写入课程数据…")

                        // 1. 确定目标课表 ID
                        val targetTableId = resolveTargetTableId()

                        // 2. 清空旧课程（ForeignKey.CASCADE 自动清理 course_weeks）
                        courseDao.deleteCoursesByTableId(targetTableId)

                        // 3. 计算颜色（名称分组 + 轮转配色）
                        val colorSize =
                            styleSettingsRepository.styleFlow.first().courseColorMaps.size
                        val nameToColor = mutableMapOf<String, Int>()
                        var colorIdx = if (colorSize > 0) Random.nextInt(colorSize) else 0

                        // 4. 转换为 Course + CourseWeek
                        val courses = mutableListOf<Course>()
                        val weeks = mutableListOf<CourseWeek>()
                        for (item in courseItems) {
                            val name = item.kcmc.trim()
                            val ci = nameToColor.getOrPut(name) {
                                val c = if (colorSize > 0) colorIdx % colorSize else 0
                                colorIdx++
                                c
                            }
                            val course = item.toCourseEntity(targetTableId, ci)
                            courses.add(course)
                            item.parseWeeks().forEach { w ->
                                weeks.add(CourseWeek(courseId = course.id, weekNumber = w))
                            }
                        }

                        // 5. 批量写入
                        if (courses.isNotEmpty()) courseDao.insertAll(courses)
                        if (weeks.isNotEmpty()) courseWeekDao.insertAll(weeks)
                    }
                }

                // ── 第 3 步：抓取并写入成绩数据 ──
                if (syncGrades) {
                    _syncUiState.value = SyncUiState.Loading("正在抓取成绩数据…")
                    val gradeItems = scraper.fetchGrades()
                    val gradeEntities = gradeItems.map { it.toEntity() }
                    gradeDao.replaceAll(gradeEntities)
                }

                // ── 第 4 步：抓取并写入考试安排 ──
                if (syncExams) {
                    _syncUiState.value = SyncUiState.Loading("正在抓取考试安排…")
                    val examItems = scraper.fetchExams()
                    val examEntities = examItems.map { it.toEntity() }
                    examDao.replaceAll(examEntities)
                }

                // ── 全部完成 ──
                _syncUiState.value = SyncUiState.Success("同步圆满成功！")
            }.onFailure { e ->
                _syncUiState.value = SyncUiState.Error(
                    e.message ?: "同步失败，请稍后重试"
                )
            }
        }
    }

    /**
     * 确定课程表同步的目标课表 ID。
     *
     * 查找优先级:
     * 1. 用户当前使用的课表 (AppSettings.currentCourseTableId)
     * 2. 数据库中最早创建的课表
     * 3. 创建新课表（名称含时间戳，如 "教务系统导入 2026-06-19 15:30"）
     */
    private suspend fun resolveTargetTableId(): String {
        // 优先使用用户当前课表
        val settings = appSettingsRepository.getAppSettingsOnce()
        if (settings.currentCourseTableId.isNotEmpty()) {
            return settings.currentCourseTableId
        }

        // 回退到数据库中最旧的课表
        val firstTable = courseTableDao.getFirstTableOnce()
        if (firstTable != null) {
            return firstTable.id
        }

        // 兜底: 创建新课表
        val now = System.currentTimeMillis()
        val name = "教务系统导入 ${
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(now))
        }"
        val newTable = CourseTable(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAt = now
        )
        courseTableDao.insert(newTable)
        return newTable.id
    }

    /**
     * 将同步状态重置为 [SyncUiState.Idle]。
     *
     * UI 层在消费完 [SyncUiState.Error] 或 [SyncUiState.Success] 后**必须**
     * 调用此方法，否则重组（Recomposition）会导致 Toast 或导航重复触发。
     */
    fun resetToIdle() {
        _syncUiState.value = SyncUiState.Idle
    }
}
