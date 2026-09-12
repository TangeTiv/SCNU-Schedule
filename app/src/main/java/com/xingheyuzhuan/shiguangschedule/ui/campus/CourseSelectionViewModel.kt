package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuCookieJar
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseCategory
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseClass
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseSelectionException
import com.xingheyuzhuan.shiguangschedule.data.network.selection.EnrolledCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuCourseSelector
import com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuLoginException
import com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuSsoLogin
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectableCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectionOutcome
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectionRoundInfo
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SubCourse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named

// ═══════════════════════════════════════════════════════════════════════════
// UI 状态模型
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 已加载课程在内存中的形态。
 *
 * 按类别分别持有（见 [CourseSelectionViewModel.coursesByCategory]），
 * 因此切换 Tab 不需要重新拉取。
 */
data class LoadedCourses(
    val items: List<SelectableCourse> = emptyList(),
    /** 已拉取到的批次号，下一批为 `batch + 1` */
    val batch: Int = 0,
    /** 是否已到末尾（本批未满 PAGE_SIZE） */
    val isEnd: Boolean = false,
    /** 是否正在加载下一批 */
    val isLoadingMore: Boolean = false
)

/**
 * 选课模块的顶层状态。
 *
 * ## 为什么把课程列表拆成独立 StateFlow 而不是塞进本类
 *
 * 课程列表每次续拉都会整体替换，若与登录态、轮次信息共用一个 State，
 * 加载 20 批就会触发 20 次整页重组（含 TopAppBar、Tabs）。故课程列表单独
 * 由 [CourseSelectionViewModel.coursesByCategory] 承载，本类只保留低频状态。
 */
data class CourseSelectionUiState(
    /** 登录请求进行中 */
    val isLoggingIn: Boolean = false,
    /** 已成功登录（会话可用） */
    val isLoggedIn: Boolean = false,
    /** 登录/加载失败的用户可读信息 */
    val errorMessage: String? = null,
    /** 会话中途失效，需重新登录；**已加载列表不会被清空** */
    val sessionExpired: Boolean = false,
    /** 用户主动退出模块时清理本地数据的提示（非错误） */
    val infoMessage: String? = null
)

/**
 * 一次性操作反馈（选课/退选结果）。
 *
 * 使用自增 [id] 作为一次性事件标识，UI 消费后调用
 * [CourseSelectionViewModel.consumeFeedback] 清除，避免重组导致重复弹 Toast。
 */
data class SelectionFeedback(
    val id: Long,
    val message: String,
    val isSuccess: Boolean
)

/**
 * 选课模块 ViewModel。
 *
 * ## 核心约束（逐条对应设计决策）
 *
 * 1. **临时沙盒**：所有数据仅存内存，退出模块即清空（见 [clearSession]）
 * 2. **凭证不落盘**：账号从 DataStore 读 `campus_account` 预填，密码仅存活于内存
 * 3. **按类别懒加载**：进入后只拉当前类别首批，滑到底续拉（见 [loadNextBatch]）
 * 4. **超时 ≠ 失败**：网络异常时查权威已选清单判断真实结果（见 [submitSelection]）
 * 5. **会话失效保留列表**：只置 [CourseSelectionUiState.sessionExpired]，不清数据
 * 6. **提交加锁**：`inFlightSubmissions` 防止连点造成重复提交
 * 7. **主线程零重活**：全部请求在 [Dispatchers.IO]；map/filter 走 [Dispatchers.Default]
 */
@HiltViewModel
class CourseSelectionViewModel @Inject constructor(
    private val selector: ScnuCourseSelector,
    private val ssoLogin: ScnuSsoLogin,
    /**
     * 共享的 SCNU CookieJar。
     *
     * 注入接口（`okhttp3.CookieJar`）而非具体类，因为 Hilt 只提供了
     * `@Named("scnu") CookieJar` 这一种绑定；退出模块时需下调为
     * [ScnuCookieJar] 以调用其 `clear()`。
     */
    @Named("scnu") private val cookieJar: CookieJar,
    @Named("AppSettings") private val dataStore: DataStore<Preferences>
) : ViewModel() {

    // ── 顶层状态 ──

    private val _uiState = MutableStateFlow(CourseSelectionUiState())
    val uiState: StateFlow<CourseSelectionUiState> = _uiState.asStateFlow()

    private val _roundInfo = MutableStateFlow(SelectionRoundInfo())
    val roundInfo: StateFlow<SelectionRoundInfo> = _roundInfo.asStateFlow()

    private val _categories = MutableStateFlow<List<CourseCategory>>(emptyList())
    val categories: StateFlow<List<CourseCategory>> = _categories.asStateFlow()

    private val _selectedCategory = MutableStateFlow<CourseCategory?>(null)
    val selectedCategory: StateFlow<CourseCategory?> = _selectedCategory.asStateFlow()

    /** 账号预填值（来自 DataStore 的 `campus_account`） */
    private val _savedAccount = MutableStateFlow("")
    val savedAccount: StateFlow<String> = _savedAccount.asStateFlow()

    /**
     * 按类别缓存的课程列表。
     *
     * 使用 [MutableStateFlow] + 不可变 Map 整体替换，读取端通过
     * [currentCourses] 做细粒度映射；切换 Tab 不触发其他类别的重组。
     */
    private val _coursesByCategory = MutableStateFlow<Map<String, LoadedCourses>>(emptyMap())

    /** 当前选中类别已加载的课程（派生流，只在该类别数据变化时发射） */
    val currentCourses: StateFlow<LoadedCourses> = combine(
        _selectedCategory,
        _coursesByCategory
    ) { category, map ->
        category?.let { map[it.typeCode] } ?: LoadedCourses()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadedCourses())

    /**
     * 当前列表用的关键字过滤结果。
     *
     * `map` 阶段显式切到 [Dispatchers.Default]：过滤是 O(n) 的 CPU 工作，
     * 而 `stateIn` 的上游默认在收集者上下文执行，不能让它落回主线程。
     */
    val keyword: MutableStateFlow<String> = MutableStateFlow("")

    val displayedCourses: StateFlow<List<SelectableCourse>> = combine(
        currentCourses,
        keyword
    ) { loaded, kw ->
        // 对应脚本 `_match()` 的本地过滤语义
        if (kw.isBlank()) loaded.items
        else loaded.items.filter { c ->
            "${c.courseCode} ${c.courseName} ${c.className}".lowercase().contains(kw.trim().lowercase())
        }
    }.map { list -> withContext(Dispatchers.Default) { list.sortedBy { it.rankInBatch.toIntOrNull() ?: 0 } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── 已选课程 ──

    private val _enrolledCourses = MutableStateFlow<List<EnrolledCourse>>(emptyList())
    val enrolledCourses: StateFlow<List<EnrolledCourse>> = _enrolledCourses.asStateFlow()

    private val _isLoadingEnrolled = MutableStateFlow(false)
    val isLoadingEnrolled: StateFlow<Boolean> = _isLoadingEnrolled.asStateFlow()

    // ── 一次性反馈 ──

    private val _feedback = MutableStateFlow<SelectionFeedback?>(null)
    val feedback: StateFlow<SelectionFeedback?> = _feedback.asStateFlow()

    private var feedbackCounter = 0L

    // ── 并发控制 ──

    /**
     * 正在提交选课的教学班 ID 集合。
     *
     * **这是刻意偏离 Python 脚本的一处**：脚本是同步阻塞 CLI，不存在连点问题；
     * 而 App 里连点两次会发出两次请求，第二次可能返回 `flag='5'`（校验不通过），
     * 用户会误以为选课失败，实际第一次已经成功。
     */
    private val inFlightSubmissions = mutableSetOf<String>()

    /** 正在退选的课程 ID 集合，同上目的 */
    private val inFlightDrops = mutableSetOf<String>()

    /** 因会话失效而中断的选课草稿，重新登录后可一键重试 */
    private var pendingSelection: PendingSelection? = null

    private data class PendingSelection(
        val course: SelectableCourse,
        val category: CourseCategory?,
        val doJxbId: String,
        val pickedSubCourses: List<SubCourse>
    )

    init {
        // 异步读取上次成功登录的学号用于预填；密码绝不读取（从未落盘）
        viewModelScope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            _savedAccount.value = prefs[CampusSyncViewModel.KEY_CAMPUS_ACCOUNT] ?: ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 登录
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 登录并自动加载第一个类别。
     *
     * 登录成功后立即调用 [refreshContext] 抓取选课页上下文 ——
     * 这既是"数据落地"的起点，也是会话有效性的唯一真实校验。
     */
    fun login(account: String, password: String) {
        if (_uiState.value.isLoggingIn) return
        val trimmedAccount = account.trim()
        if (trimmedAccount.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请输入学号与密码")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoggingIn = true,
                errorMessage = null,
                sessionExpired = false
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    ssoLogin.login(trimmedAccount, password)
                    // 抓取上下文 = 验证会话真的可用（对应脚本 _refresh_context）
                    selector.refreshContext()
                }
                // 记下账号供下次预填（仅账号，不存密码）
                withContext(Dispatchers.IO) {
                    dataStore.edit { it[CampusSyncViewModel.KEY_CAMPUS_ACCOUNT] = trimmedAccount }
                }
                _savedAccount.value = trimmedAccount
            }.onSuccess {
                val info = withContext(Dispatchers.IO) { selector.roundInfo() }
                _roundInfo.value = info
                _categories.value = selector.categories
                _uiState.value = _uiState.value.copy(isLoggingIn = false, isLoggedIn = true)

                // 登录后立即加载第一个类别（对应决策：不做"查询"二次点击）
                selector.categories.firstOrNull()?.let { first ->
                    selectCategory(first, autoLoad = true)
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    errorMessage = e.friendlyMessage()
                )
            }
        }
    }

    /**
     * 会话失效后原地重新登录。
     *
     * 与 [login] 的差异：**不清空任何已加载数据**，成功后刷新轮次信息，
     * 并尝试重试因失效而中断的选课。
     */
    fun reLogin(account: String, password: String) {
        if (_uiState.value.isLoggingIn) return
        val trimmedAccount = account.trim()
        if (trimmedAccount.isBlank() || password.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "请输入学号与密码")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoggingIn = true, errorMessage = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    ssoLogin.login(trimmedAccount, password)
                    selector.refreshContext()
                }
            }.onSuccess {
                val info = withContext(Dispatchers.IO) { selector.roundInfo() }
                _roundInfo.value = info
                _categories.value = selector.categories
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    isLoggedIn = true,
                    sessionExpired = false
                )
                // 重试中断的选课
                pendingSelection?.let { pending ->
                    pendingSelection = null
                    submitSelection(
                        course = pending.course,
                        category = pending.category,
                        doJxbId = pending.doJxbId,
                        pickedSubCourses = pending.pickedSubCourses
                    )
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    errorMessage = e.friendlyMessage()
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 类别与课程列表
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 切换课程类别。
     *
     * 已缓存的类别直接复用，不重新拉取；未缓存的拉取首批。
     *
     * @param autoLoad 是否在切换后自动拉首批（登录后首次进入时为 true）
     */
    fun selectCategory(category: CourseCategory, autoLoad: Boolean = true) {
        _selectedCategory.value = category
        // 清空关键字：不同类别的课程集不同，沿用旧关键字会立刻得到空列表
        keyword.value = ""
        val cached = _coursesByCategory.value[category.typeCode]
        if (autoLoad && (cached == null || cached.items.isEmpty()) && cached?.isEnd != true) {
            loadNextBatch()
        }
    }

    /**
     * 加载当前类别的下一批课程。
     *
     * 对应脚本 `list_courses()` 的循环体：窗口宽度 [ScnuCourseSelector.PAGE_SIZE]，
     * 本批未满即视为到底。到达末尾后再次调用将直接返回。
     */
    fun loadNextBatch() {
        val category = _selectedCategory.value ?: return
        val cached = _coursesByCategory.value[category.typeCode] ?: LoadedCourses()

        // 已到底或正在加载则忽略（滚动触发的重复调用很常见）
        if (cached.isEnd || cached.isLoadingMore) return

        val nextBatch = cached.batch + 1
        updateCategoryCache(category.typeCode) { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    selector.listCoursesBatch(category = category, batch = nextBatch)
                }
            }.onSuccess { batch ->
                updateCategoryCache(category.typeCode) { current ->
                    // 按 dedupeKey 去重：滑动窗口确实会重复推送同一教学班
                    val existingKeys = current.items.mapTo(mutableSetOf()) { it.dedupeKey }
                    val fresh = batch.courses.filter { existingKeys.add(it.dedupeKey) }
                    current.copy(
                        items = current.items + fresh,
                        batch = batch.batch,
                        isEnd = batch.isEnd,
                        isLoadingMore = false
                    )
                }
                // 已选人数等属于易变信息，拉到新数据时顺带刷新已选清单
                if (batch.batch == 1) refreshEnrolled(silent = true)
            }.onFailure { e ->
                updateCategoryCache(category.typeCode) { it.copy(isLoadingMore = false) }
                handleFailure(e)
            }
        }
    }

    private fun updateCategoryCache(
        typeCode: String,
        transform: (LoadedCourses) -> LoadedCourses
    ) {
        _coursesByCategory.value = _coursesByCategory.value.toMutableMap().apply {
            this[typeCode] = transform(this[typeCode] ?: LoadedCourses())
        }
    }

    fun onKeywordChange(value: String) {
        keyword.value = value
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 教学班详情
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 拉取某课程的全部教学班详情。
     *
     * @param onLoaded 回调在主线程执行，供 UI 弹出选择面板
     * @param onError 失败时的用户可读信息
     */
    fun loadClasses(
        course: SelectableCourse,
        onLoaded: (List<CourseClass>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    selector.classesOf(
                        courseId = course.courseId.ifBlank { course.courseCode },
                        category = _selectedCategory.value
                    )
                }
            }.onSuccess(onLoaded)
                .onFailure { e ->
                    handleFailure(e)
                    onError(e.friendlyMessage())
                }
        }
    }

    /**
     * 拉取某教学班的子课程列表。
     *
     * 教学班由多个子课程组成（理论/实验/上机…）时必须走本流程，
     * 否则只提交单个 `do_jxb_id` 会导致选课失败或**只选上部分子课程**。
     */
    fun loadSubCourses(
        course: SelectableCourse,
        onLoaded: (List<SubCourse>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    selector.subCourses(course, _selectedCategory.value)
                }
            }.onSuccess(onLoaded)
                .onFailure { e ->
                    handleFailure(e)
                    onError(e.friendlyMessage())
                }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 选课提交
    // ═══════════════════════════════════════════════════════════════════════

    /** 某教学班是否正在提交中（用于禁用按钮） */
    fun isSubmitting(classId: String): Boolean = classId in inFlightSubmissions

    /**
     * 提交选课。
     *
     * ## 高并发下的三条硬规则
     *
     * 1. **已选预检查**（对应脚本 `_is_enrolled`）：避免重复提交，但预检查
     *    失败时**不阻断**，而是照常提交并把"未能确认"作为中性提示
     * 2. **提交加锁**：同教学班并发提交直接忽略
     * 3. **超时 ≠ 失败**：网络异常时查 `my_enrolled()` 权威状态
     *    —— 已选上则提示成功，未选上则提示未知并让用户决定是否重试。
     *    **绝不盲目重试**。
     */
    fun submitSelection(
        course: SelectableCourse,
        category: CourseCategory? = null,
        doJxbId: String = "",
        pickedSubCourses: List<SubCourse> = emptyList()
    ) {
        val lockKey = course.classId.ifBlank { course.courseId }
        if (lockKey in inFlightSubmissions) return
        inFlightSubmissions.add(lockKey)

        viewModelScope.launch {
            try {
                val effectiveCategory = category ?: _selectedCategory.value

                // ── 规则 1：已选预检查 ──
                val preCheck = runCatching {
                    withContext(Dispatchers.IO) { selector.myEnrolled() }
                }
                val enrolledList = preCheck.getOrNull()
                val alreadyEnrolled = enrolledList?.any { enrolled ->
                    val targetId = course.courseId.ifBlank { course.courseCode }
                    enrolled.courseId == targetId || enrolled.courseCode == course.courseCode
                } ?: false

                if (alreadyEnrolled) {
                    pushFeedback("该课程已在你的已选清单中，无需重复选课", isSuccess = true)
                    refreshEnrolled(silent = true)
                    return@launch
                }
                if (preCheck.isFailure) {
                    // 预检查失败不阻断，但明确告知"未能确认"，避免用户误以为系统没在查
                    pushFeedback("未能确认是否已选，正在直接提交…", isSuccess = true)
                }

                // ── 实际提交 ──
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        selector.select(
                            course = course,
                            category = effectiveCategory,
                            doJxbId = doJxbId.takeIf { it.isNotBlank() },
                            pickedSubCourses = pickedSubCourses
                        )
                    }
                }

                outcome.onSuccess { result ->
                    when (result) {
                        is SelectionOutcome.Success -> {
                            pushFeedback("选课成功：${course.courseName}", isSuccess = true)
                            refreshEnrolled(silent = true)
                        }
                        is SelectionOutcome.AlreadyEnrolled -> {
                            // flag='6' 不是失败
                            pushFeedback("${course.courseName} 已选中，无需重复选课", isSuccess = true)
                            refreshEnrolled(silent = true)
                        }
                        is SelectionOutcome.ClassFull -> {
                            pushFeedback("${course.courseName} 名额已满，请选择其他教学班", isSuccess = false)
                        }
                        is SelectionOutcome.SessionExpired -> {
                            onSessionExpired(course, effectiveCategory, doJxbId, pickedSubCourses)
                        }
                        is SelectionOutcome.Failure -> {
                            pushFeedback(result.rawMessage.ifBlank { "选课未成功" }, isSuccess = false)
                        }
                    }
                }.onFailure { e ->
                    // ── 规则 3：结果未知 → 查权威状态，绝不盲目重试 ──
                    val confirmed = runCatching {
                        withContext(Dispatchers.IO) { selector.myEnrolled() }
                    }.getOrNull()

                    val targetId = course.courseId.ifBlank { course.courseCode }
                    val nowEnrolled = confirmed?.any {
                        it.courseId == targetId || it.courseCode == course.courseCode
                    } ?: false

                    when {
                        nowEnrolled -> {
                            pushFeedback(
                                "已选上「${course.courseName}」（上次请求实际已成功）",
                                isSuccess = true
                            )
                            refreshEnrolled(silent = true)
                        }
                        confirmed == null -> {
                            pushFeedback(
                                "未收到教务结果，且查询已选清单失败。请稍后手动确认后再决定是否重试",
                                isSuccess = false
                            )
                        }
                        else -> {
                            pushFeedback(
                                "未收到教务结果，查询显示尚未选上。可重新尝试选课",
                                isSuccess = false
                            )
                        }
                    }
                    if (e.isSessionError()) {
                        onSessionExpired(course, effectiveCategory, doJxbId, pickedSubCourses)
                    }
                }
            } finally {
                inFlightSubmissions.remove(lockKey)
            }
        }
    }

    private fun onSessionExpired(
        course: SelectableCourse,
        category: CourseCategory?,
        doJxbId: String,
        pickedSubCourses: List<SubCourse>
    ) {
        // 保留草稿：重新登录后自动重试
        pendingSelection = PendingSelection(course, category, doJxbId, pickedSubCourses)
        _uiState.value = _uiState.value.copy(
            isLoggedIn = false,
            sessionExpired = true,
            errorMessage = null
        )
        pushFeedback("会话已失效，请重新登录后自动重试", isSuccess = false)
    }

    /** 是否有因会话失效而中断、等待重试的选课 */
    val hasPendingSelection: Boolean get() = pendingSelection != null

    // ═══════════════════════════════════════════════════════════════════════
    // 退选
    // ═══════════════════════════════════════════════════════════════════════

    /** 某课程是否正在退选中 */
    fun isDropping(courseId: String): Boolean = courseId in inFlightDrops

    /**
     * 提交退选。
     *
     * **破坏性操作**：成功后教务端名额可能无法立即重新选上。
     * UI 层必须先经二次确认对话框并展示完整教学班标识信息。
     *
     * 成功后**不做本地乐观删除**，而是重新拉取权威已选清单
     * —— 否则一旦后端实际未退成功，界面会欺骗用户。
     */
    fun dropCourse(course: EnrolledCourse) {
        val lockKey = course.courseId.ifBlank { course.courseCode }
        if (lockKey in inFlightDrops) return
        inFlightDrops.add(lockKey)

        viewModelScope.launch {
            try {
                runCatching {
                    withContext(Dispatchers.IO) { selector.drop(course) }
                }.onSuccess { result ->
                    when (result) {
                        is SelectionOutcome.Success -> {
                            pushFeedback("已退选：${course.courseName}", isSuccess = true)
                            refreshEnrolled(silent = false)
                        }
                        is SelectionOutcome.SessionExpired -> {
                            _uiState.value = _uiState.value.copy(
                                isLoggedIn = false,
                                sessionExpired = true
                            )
                            pushFeedback("会话已失效，请重新登录", isSuccess = false)
                        }
                        is SelectionOutcome.Failure -> {
                            // 透传教务原文（_DROP_MSG），而非笼统"失败"
                            pushFeedback(result.rawMessage.ifBlank { "退选失败" }, isSuccess = false)
                        }
                        is SelectionOutcome.AlreadyEnrolled, is SelectionOutcome.ClassFull -> {
                            pushFeedback("退选返回了意外结果，请刷新后确认", isSuccess = false)
                        }
                    }
                }.onFailure { e ->
                    handleFailure(e)
                    pushFeedback(e.friendlyMessage(), isSuccess = false)
                }
            } finally {
                inFlightDrops.remove(lockKey)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 已选课程
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 刷新权威已选清单。
     *
     * @param silent 静默模式：失败不打扰用户（用于选课后的顺带刷新）
     */
    fun refreshEnrolled(silent: Boolean = false) {
        if (_isLoadingEnrolled.value) return
        viewModelScope.launch {
            _isLoadingEnrolled.value = true
            runCatching {
                withContext(Dispatchers.IO) { selector.myEnrolled() }
            }.onSuccess {
                _enrolledCourses.value = it
                if (!silent) pushFeedback("已选课程已刷新", isSuccess = true)
            }.onFailure { e ->
                if (!silent) {
                    handleFailure(e)
                    pushFeedback(e.friendlyMessage(), isSuccess = false)
                }
            }
            _isLoadingEnrolled.value = false
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 反馈与清理
    // ═══════════════════════════════════════════════════════════════════════

    private fun pushFeedback(message: String, isSuccess: Boolean) {
        feedbackCounter += 1
        _feedback.value = SelectionFeedback(feedbackCounter, message, isSuccess)
    }

    /** UI 消费一次性反馈后调用，避免重组重复弹窗 */
    fun consumeFeedback() {
        _feedback.value = null
    }

    /** 清除错误提示（用户已阅读） */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 清除信息提示 */
    fun clearInfo() {
        _uiState.value = _uiState.value.copy(infoMessage = null)
    }

    private fun handleFailure(e: Throwable) {
        if (e.isSessionError()) {
            _uiState.value = _uiState.value.copy(isLoggedIn = false, sessionExpired = true)
        } else {
            _uiState.value = _uiState.value.copy(errorMessage = e.friendlyMessage())
        }
    }

    /**
     * 退出模块时清理全部本地数据与会话。
     *
     * ## 为什么必须清 CookieJar
     *
     * `ScnuCookieJar` 是单例，且选课与教务同步共用同一套 SSO 凭据。
     * 若只清课程数据不清会话，用户**再次进入模块时会跳过登录**
     * —— 与"密码需要重新输入"的设计意图相反。
     *
     * 由 UI 层的 `DisposableEffect` 在离开选课页面时调用。
     */
    fun clearSession() {
        _coursesByCategory.value = emptyMap()
        _enrolledCourses.value = emptyList()
        _categories.value = emptyList()
        _selectedCategory.value = null
        _roundInfo.value = SelectionRoundInfo()
        keyword.value = ""
        _feedback.value = null
        pendingSelection = null
        _uiState.value = CourseSelectionUiState()
        // 清空共享会话，确保下次进入必须重新登录
        (cookieJar as? ScnuCookieJar)?.clear()
    }

    /**
     * 把底层异常翻译成用户可读信息。
     *
     * 重点是**不要把"会话失效"说成"JSON 解析失败"** ——
     * 现有教务同步链路就存在这个问题，此处刻意修正。
     */
    private fun Throwable.friendlyMessage(): String = when {
        this is ScnuLoginException -> message ?: "登录失败，请检查学号与密码"
        this is CourseSelectionException -> message ?: "选课请求失败"
        this is IOException -> "网络连接失败，请检查校园网后重试"
        else -> message ?: "操作失败，请稍后重试"
    }

    /** 判断异常是否属于"会话失效"类别 */
    private fun Throwable.isSessionError(): Boolean {
        val text = message.orEmpty()
        return text.contains("会话") ||
                text.contains("911") ||
                text.contains("登录") ||
                text.contains("未返回")
    }
}
