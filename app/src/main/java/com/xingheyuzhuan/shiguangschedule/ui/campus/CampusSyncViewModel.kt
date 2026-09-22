package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.auth.AuthStateRepository
import com.xingheyuzhuan.shiguangschedule.data.auth.ScnuAuthManager
import com.xingheyuzhuan.shiguangschedule.data.auth.SessionResult
import com.xingheyuzhuan.shiguangschedule.data.db.main.Course
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTable
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseTableDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeek
import com.xingheyuzhuan.shiguangschedule.data.db.main.CourseWeekDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.ExamDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeDao
import com.xingheyuzhuan.shiguangschedule.data.db.main.toEntity
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuAcademicScraper
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import com.xingheyuzhuan.shiguangschedule.data.network.toCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.network.parseWeeks
import java.time.LocalDate
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
 * ## v1.7.0：不再接收明文凭据
 *
 * 改造前本类接收 `account` / `password` 并自行登录、自行把学号写进
 * DataStore（与 `CourseSelectionViewModel` 重复写同一个键）。
 *
 * 现在改为：
 * - 凭据由 [ScnuAuthManager] 统一管理，本类只调用 [ScnuAuthManager.ensureSession]
 * - 学号不再由本类持久化 —— 写入收敛到 `CredentialStore` 一处
 * - UI 只读 [maskedAccount]（脱敏），完整学号不经过 UI
 */
@HiltViewModel
class CampusSyncViewModel @Inject constructor(
    private val scraper: ScnuScraper,
    private val academicScraper: ScnuAcademicScraper,
    private val gradeDao: GradeDao,
    private val examDao: ExamDao,
    private val academicDao: AcademicDao,
    private val authManager: ScnuAuthManager,
    private val authState: AuthStateRepository,
    // ── 课程表同步所需依赖 ──
    private val courseDao: CourseDao,
    private val courseWeekDao: CourseWeekDao,
    private val courseTableDao: CourseTableDao,
    private val appSettingsRepository: AppSettingsRepository,
    private val styleSettingsRepository: StyleSettingsRepository
) : ViewModel() {

    // ── 同步状态 ──

    private val _syncUiState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncUiState: StateFlow<SyncUiState> = _syncUiState.asStateFlow()

    // ── 登录凭据状态 ──

    /**
     * 最近一次会话检查结果；`null` 表示尚未检查。
     *
     * UI 直接按 [SessionResult] 的分支决定显示什么：能同步 / 要验证身份 /
     * 要去账号页 / 冷却中。不再由 UI 猜"为什么不能同步"。
     */
    private val _authSession = MutableStateFlow<SessionResult?>(null)
    val authSession: StateFlow<SessionResult?> = _authSession.asStateFlow()

    /** 脱敏学号，供页面显示"当前账号" */
    val maskedAccount: StateFlow<String?> = authState.maskedAccount

    /**
     * 检查会话状态。
     *
     * 由页面进入时调用一次。**不会弹生物识别** —— 需要验证身份时返回
     * [SessionResult.NeedsUnlock]，由 UI 决定何时弹窗（见 `SyncSelectionScreen`）。
     */
    fun refreshAuthSession() {
        viewModelScope.launch(Dispatchers.IO) {
            _authSession.value = authManager.ensureSession()
        }
    }

    /**
     * 生成解密密码用的 cipher，交给 UI 弹生物识别。
     *
     * @return null 表示密钥已作废（用户换了指纹），此时应引导去账号页重输密码
     */
    suspend fun createUnlockCipher(): javax.crypto.Cipher? = authManager.createUnlockCipher()

    /**
     * 生物识别通过后完成解锁并重登。
     *
     * @return 解锁后的会话状态
     */
    suspend fun completeUnlock(authenticatedCipher: javax.crypto.Cipher): SessionResult {
        val result = authManager.completeUnlock(authenticatedCipher)
        _authSession.value = result
        return result
    }

    /**
     * 开始教务系统同步。
     *
     * 整个流程在 [Dispatchers.IO] 中执行，通过 [runCatching] 统一捕获异常，
     * 并通过 [_syncUiState] 实时反馈进度。
     *
     * 凭据不再由本方法接收：会话必须**已经**通过 [ScnuAuthManager] 建立，
     * 否则本方法会拒绝执行并把原因写进 [authSession]（不谎报成功）。
     *
     * @param syncCourses 是否需要同步学期课程表
     * @param syncGrades  是否需要同步成绩数据
     * @param syncExams   是否需要同步考试安排
     * @param syncAcademic 是否需要同步学业情况（培养计划 + 学分完成度 + 非正式学时）
     */
    fun startSync(
        syncCourses: Boolean,
        syncGrades: Boolean,
        syncExams: Boolean,
        syncAcademic: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // ── 第 0 步：确认会话可用（凭据缺失/失效一律拒绝执行）──
                val session = authManager.ensureSession()
                _authSession.value = session
                if (session !is SessionResult.Active) {
                    // 不静默返回：按钮点下去毫无反应会被当成卡死。
                    // 弹一条明确提示，同时状态卡片也会刷新成"需要验证身份 / 去账号页"。
                    _syncUiState.value = SyncUiState.Error("登录状态已变化，请按上方提示处理后再试")
                    return@launch
                }

                // ── 第 1 步：抓取并写入学期课程表 ──
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

                // ── 第 2 步：抓取并写入成绩数据 ──
                if (syncGrades) {
                    _syncUiState.value = SyncUiState.Loading("正在抓取成绩数据…")
                    val gradeItems = scraper.fetchGrades()
                    val gradeEntities = gradeItems.map { it.toEntity() }
                    gradeDao.replaceAll(gradeEntities)
                }

                // ── 第 3 步：抓取并写入考试安排 ──
                if (syncExams) {
                    _syncUiState.value = SyncUiState.Loading("正在抓取考试安排…")
                    val examItems = scraper.fetchExams()

                    // 4a. 始终更新 ExamDao（保持 ExamScreen 独立运转）
                    val examEntities = examItems.map { it.toEntity() }
                    examDao.replaceAll(examEntities)

                    // 4b. 同时将考试作为自定义时间课程写入 Course 数据库
                    runCatching {
                        val targetTableId = resolveTargetTableId()
                        val config = appSettingsRepository.getCourseConfigOnce(targetTableId)
                        val termStartDate = config?.semesterStartDate?.let {
                            try { LocalDate.parse(it) } catch (_: Exception) { null }
                        }
                        val firstDayOfWeek = config?.firstDayOfWeek ?: java.time.DayOfWeek.MONDAY.value

                        if (termStartDate != null) {
                            // 安全获取颜色索引：取调色板最后一个颜色，永不越界
                            val colorSize = runCatching {
                                styleSettingsRepository.styleFlow.first().courseColorMaps.size
                            }.getOrElse { 0 }
                            val examColor = (colorSize - 1).coerceAtLeast(0)

                            // 清理上一次同步的旧考试课程
                            courseDao.deleteSyncedExamsByTableId(targetTableId)

                            // 批量转换并写入
                            val courses = mutableListOf<Course>()
                            val weeks = mutableListOf<CourseWeek>()
                            for (item in examItems) {
                                item.toCourseEntity(targetTableId, termStartDate, examColor, firstDayOfWeek)
                                    ?.let { (c, ws) ->
                                        courses.add(c)
                                        weeks.addAll(ws)
                                    }
                            }
                            if (courses.isNotEmpty()) courseDao.insertAll(courses)
                            if (weeks.isNotEmpty()) courseWeekDao.insertAll(weeks)
                        }
                    }.onFailure { e ->
                        android.util.Log.w("ExamSync", "写入考试到课表失败", e)
                    }
                }

                // ── 第 4 步：抓取并写入学业情况（培养计划 + 非正式学时）──
                var academicFailureCount = 0
                if (syncAcademic) {
                    academicFailureCount = syncAcademicData()
                }

                // ── 全部完成 ──
                _syncUiState.value = if (academicFailureCount > 0) {
                    // 逐项容错：部分计划点/学期没取到数据时，如实告知而不是谎报成功
                    SyncUiState.Success(
                        "同步完成。学业情况有 $academicFailureCount 项未取到数据，" +
                                "其余数据已更新"
                    )
                } else {
                    SyncUiState.Success("同步圆满成功！")
                }
            }.onFailure { e ->
                _syncUiState.value = SyncUiState.Error(
                    e.message ?: "同步失败，请稍后重试"
                )
            }
        }
    }

    /**
     * 执行学业情况同步，返回**失败项数量**（0 表示全部成功）。
     *
     * ## 为什么单独抽一个方法
     *
     * 学业情况要打 33 个计划点 + 6~8 个学期的接口（约 40 次请求），
     * 是整个同步流程里最容易遇到抖动的部分。把它隔离出来后：
     * - 抓取器的逐项容错结果能转成对用户有意义的「N 项失败」文案
     * - 落库与抓取分离，抓取失败不会污染已有数据
     *
     * ## 落库顺序（重要）
     *
     * **先抓完再写库**，且只在抓到非空数据时才覆盖。
     * DAO 的 `replaceXxx` 对空列表是 no-op，因此一次失败的同步
     * 不会把用户上次成功的学业数据抹掉。
     */
    private suspend fun syncAcademicData(): Int {
        // 学业情况的接口需要学号作为参数。会话此刻必然可用（[startSync] 已前置校验），
        // 因此 currentAccount() 拿不到值属于不该发生的状态，直接如实报错而不是硬编码。
        val account = authManager.currentAccount()
            ?: throw IllegalStateException("无法读取学号，请到「我的 → 账号」重新设置凭据")

        var failureCount = 0

        // ── 5a. 培养计划树 + 各计划点课程 ──
        _syncUiState.value = SyncUiState.Loading("正在抓取培养计划…")
        val planResult = academicScraper.fetchAcademicPlan(account) { done, total, current ->
            if (total > 0 && current.isNotEmpty()) {
                // 在 IO 线程更新进度状态，UI 侧只读，安全
                _syncUiState.value =
                    SyncUiState.Loading("正在抓取培养计划… ($done/$total) $current")
            }
        }

        _syncUiState.value = SyncUiState.Loading("正在写入学业数据…")
        academicDao.replacePlanNodes(
            planResult.nodes.mapIndexed { index, node -> node.toEntity(index) }
        )
        academicDao.replaceCourses(
            planResult.coursesByNode.flatMap { (nodeId, courses) ->
                courses.map { it.toEntity(nodeId) }
            }
        )
        failureCount += planResult.failedNodes.size

        // ── 5b. 非正式学时（第二类课，需扫描全部学年学期）──
        _syncUiState.value = SyncUiState.Loading("正在抓取非正式学时…")
        val nonFormalResult = academicScraper.fetchNonFormalCourses(account) { done, total, label ->
            if (total > 0 && label.isNotEmpty()) {
                _syncUiState.value =
                    SyncUiState.Loading("正在抓取非正式学时… ($done/$total) $label")
            }
        }

        academicDao.replaceNonFormalCourses(
            nonFormalResult.courses.map { it.toEntity() }
        )
        failureCount += nonFormalResult.failedTerms.size

        return failureCount
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
