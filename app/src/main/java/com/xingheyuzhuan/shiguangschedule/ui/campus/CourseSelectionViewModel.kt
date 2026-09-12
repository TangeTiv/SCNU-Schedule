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
import com.xingheyuzhuan.shiguangschedule.data.network.selection.isCourseEnrolled
import com.xingheyuzhuan.shiguangschedule.data.network.selection.isEnrolledIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * 一门课程及其全部可选教学班（用于列表分组展示）。
 *
 * ## 为什么按课程分组
 *
 * 教务列表接口返回的是**教学班**粒度：同一门课若有 3 个教学班（不同教师/时间），
 * 就会出现 3 条记录。直接平铺会让用户看到"同一门课重复出现多次"。
 *
 * 分组后每个课程只占一张卡，卡片上标注可选教学班数量，点击后再进入教学班选择。
 * 这与正方教务网页本身的交互一致。
 *
 * @param key 分组键（`kch_id`，与已选清单比对也用它）
 * @param classes 该课程下已加载的全部教学班；**过滤已选课程时会整组移除**
 */
data class CourseGroup(
    val key: String,
    val course: SelectableCourse,
    val classes: List<SelectableCourse>
) {
    /** 可选教学班数量，用于卡片角标 */
    val classCount: Int get() = classes.size
}

/**
 * 选课模块的顶层状态。
 *
 * ## 为什么把课程列表拆成独立 StateFlow 而不是塞进本类
 *
 * 课程列表每次续拉都会整体替换，若与登录态、轮次信息共用一个 State，
 * 加载 20 批就会触发 20 次整页重组（含 TopAppBar、Tabs）。故课程列表单独
 * 由 [CourseSelectionViewModel.currentCourses] 承载，本类只保留低频状态。
 */
data class CourseSelectionUiState(
    /** 登录请求进行中 */
    val isLoggingIn: Boolean = false,
    /** 已成功登录（会话可用） */
    val isLoggedIn: Boolean = false,
    /** 登录/加载失败的用户可读信息 */
    val errorMessage: String? = null,
    /**
     * 浏览过程中会话**中途失效**，需原地重新登录。
     *
     * 只用于选课页内的重登提示；**不代表"已退出模块"** ——
     * 那个状态由 [sessionActive] 表达。早期实现把两者混用一个字段，
     * 导致退出后再点选课卡，校园页弹窗错误显示"会话过期，重新登录"。
     */
    val sessionExpired: Boolean = false,
    /**
     * 是否持有一个可用的选课会话。
     *
     * 退出模块后置为 false（但 [isLoggedIn] 保持 true，避免返回动画闪出
     * 登录面板），因此校园页判断"能否直接进选课页"必须读本字段。
     */
    val sessionActive: Boolean = false,
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

    // ── 已选课程（声明在 displayedCourses 之前：过滤逻辑要读它）──

    private val _enrolledCourses = MutableStateFlow<List<EnrolledCourse>>(emptyList())
    val enrolledCourses: StateFlow<List<EnrolledCourse>> = _enrolledCourses.asStateFlow()

    private val _isLoadingEnrolled = MutableStateFlow(false)
    val isLoadingEnrolled: StateFlow<Boolean> = _isLoadingEnrolled.asStateFlow()

    /**
     * 已选清单是否已成功加载过至少一次。
     *
     * **这是"已选课程过滤"的前置条件**：若清单尚未就绪就把课程渲染出去，
     * 已选课程会先显示、再被过滤掉（或干脆漏过滤）。因此 [displayedCourses]
     * 在清单未就绪时输出空列表，由 UI 显示加载中，避免用户看到"已选的课
     * 短暂出现又消失"或"根本没被过滤"。
     */
    private val _isEnrolledReady = MutableStateFlow(false)
    val isEnrolledReady: StateFlow<Boolean> = _isEnrolledReady.asStateFlow()

    /**
     * 列表关键字（本地过滤，不发起服务端搜索）。
     *
     * 对应脚本 `keyword` 参数的语义：只在**已拉取到本地**的课程中筛选，
     * 因此输入关键字不会自动补齐未加载的批次。
     */
    val keyword: MutableStateFlow<String> = MutableStateFlow("")

    /**
     * 当前列表用的关键字过滤结果。
     *
     * ## 处理顺序（顺序有意义）
     *
     * 1. **等待已选清单就绪** —— 否则过滤无从谈起（见 [_isEnrolledReady]）
     * 2. 关键字过滤（对应脚本 `_match()` 的**本地**过滤语义）
     * 3. **排除已选课程** —— 教务列表接口会照常返回已选上的课程，
     *    不过滤就会出现"我已选上却仍列在主修里"的现象
     * 4. 按 `kch_id` **分组** —— 教务返回教学班粒度，同一门课的多个教学班
     *    会重复出现；分组后一门课只占一张卡
     *
     * `map` 阶段显式切到 [Dispatchers.Default]：以上都是 O(n) 的 CPU 工作，
     * 而 `stateIn` 的上游默认在收集者上下文执行，不能让它落回主线程。
     */
    val displayedCourses: StateFlow<List<CourseGroup>> = combine(
        currentCourses,
        keyword,
        _enrolledCourses,
        _isEnrolledReady
    ) { loaded, kw, enrolled, enrolledReady ->
        CourseFilterInput(loaded.items, kw, enrolled, enrolledReady)
    }.map { input ->
        withContext(Dispatchers.Default) {
            // 1. 已选清单未就绪 → 先不显示任何课程，等过滤依据到位
            if (!input.enrolledReady) return@withContext emptyList<CourseGroup>()

            // 2. 关键字过滤
            val trimmed = input.keyword.trim().lowercase()
            val matched = if (trimmed.isEmpty()) {
                input.items
            } else {
                input.items.filter { c ->
                    "${c.courseCode} ${c.courseName} ${c.className}".lowercase().contains(trimmed)
                }
            }

            // 3. 排除已选：多口径判定（kch_id / kch + 课程名交叉校验）
            val visible = matched.filterNot { it.isEnrolledIn(input.enrolled) }

            // 4. 按课程分组，组内按批次行号保持教务原始顺序。
            //    分组键用 kch_id（缺失时退化为 kch），保证一门课只出现一次。
            visible
                .sortedBy { it.rankInBatch.toIntOrNull() ?: 0 }
                .groupBy { it.courseId.ifBlank { it.courseCode } }
                .map { (key, classes) ->
                    CourseGroup(key = key, course = classes.first(), classes = classes)
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** [displayedCourses] 的 combine 输入载体（避免超过 5 个参数上限） */
    private data class CourseFilterInput(
        val items: List<SelectableCourse>,
        val keyword: String,
        val enrolled: List<EnrolledCourse>,
        val enrolledReady: Boolean
    )

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

    /**
     * 加载当前类别下一批的协程句柄。
     *
     * **必须持有并取消**：`loadNextBatch()` 曾在协程内部读取 `_selectedCategory.value`，
     * 若用户在请求在途时切换类别，回调会把**旧类别**的课程写进**新类别**的缓存桶，
     * 表现为"主修课程只显示少量课程，切走再切回才正常"。
     * 现在把类别在启动瞬间捕获、并取消上一个在途请求。
     */
    private var selectionLoadJob: Job? = null

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
     * 登录并建立选课会话。
     *
     * 登录成功后立即调用 [refreshContext] 抓取选课页上下文 ——
     * 这既是"数据落地"的起点，也是会话有效性的唯一真实校验。
     *
     * ## 为什么不再自动加载课程
     *
     * 登录入口现在位于【校园】页的对话框（见 `CourseSelectionLoginDialog`），
     * 对话框成功关闭后才会导航进选课页。若在此处自动拉取，会在用户还没进入
     * 页面时就开始网络请求。改为由选课页进入后自行触发
     * （见 [ensureInitialLoad]）。
     *
     * @param onSuccess 登录成功的回调（在主线程执行），供对话框决定是否关闭并导航
     * @param onError 失败信息的回调（在主线程执行）；为 null 时走 [CourseSelectionUiState.errorMessage]
     */
    fun login(
        account: String,
        password: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (_uiState.value.isLoggingIn) return
        val trimmedAccount = account.trim()
        if (trimmedAccount.isBlank() || password.isBlank()) {
            onError?.invoke("请输入学号与密码")
                ?: run { _uiState.value = _uiState.value.copy(errorMessage = "请输入学号与密码") }
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
                _uiState.value = _uiState.value.copy(
                    isLoggingIn = false,
                    isLoggedIn = true,
                    sessionActive = true
                )
                // 已选清单是过滤已选课程的依据，登录后立即拉一次
                refreshEnrolled(silent = true)
                onSuccess?.invoke()
            }.onFailure { e ->
                val message = e.friendlyMessage()
                _uiState.value = _uiState.value.copy(isLoggingIn = false, errorMessage = message)
                onError?.invoke(message)
            }
        }
    }

    /**
     * 【校园】页登录对话框专用的登录入口。
     *
     * 与 [login] 的唯一差别：**先清掉 [CourseSelectionUiState.sessionExpired]**。
     *
     * 该字段专表"浏览中途会话失效"，若带着它进校园页弹窗，弹窗会显示
     * "登录状态已失效，请重新输入密码" —— 而用户只是正常点击卡片准备登录，
     * 措辞具有误导性。
     *
     * 顺带保证：无论上一次退出模块时留下什么状态，从这里进入的登录都是干净的。
     */
    fun loginFromCampusDialog(
        account: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        _uiState.value = _uiState.value.copy(sessionExpired = false)
        login(account, password, onSuccess = onSuccess, onError = onError)
    }

    /**
     * 选课页进入后确保首个类别已开始加载。
     *
     * 由页面的一次性副作用调用（`LaunchedEffect`），与 [login] 解耦 ——
     * 这样无论登录发生在校园页对话框还是页面内的重登面板，进入后都能自动出数据。
     */
    fun ensureInitialLoad() {
        if (!_uiState.value.isLoggedIn) return
        if (_selectedCategory.value != null) return
        _categories.value.firstOrNull()?.let { selectCategory(it, autoLoad = true) }
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
                    sessionExpired = false,
                    sessionActive = true
                )
                // 重登后已选清单可能已变化，重新拉取
                refreshEnrolled(silent = true)
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
            // 显式传类别：避免 loadNextBatch 内部再读可能已变化的 _selectedCategory
            loadNextBatch(forCategory = category)
        }
    }

    /**
     * 加载指定类别的下一批课程。
     *
     * @param forCategory 目标类别。**在切换类别的瞬间就固定下来**，
     *                    不再读 [selectedCategory]，避免请求在途时切 Tab
     *                    导致数据写错缓存桶（见 [selectionLoadJob]）
     *
     * 对应脚本 `list_courses()` 的循环体：窗口宽度 [ScnuCourseSelector.PAGE_SIZE]，
     * 本批未满即视为到底。到达末尾后再次调用将直接返回。
     */
    fun loadNextBatch(forCategory: CourseCategory? = _selectedCategory.value) {
        val category = forCategory ?: return
        val cached = _coursesByCategory.value[category.typeCode] ?: LoadedCourses()

        // 已到底或正在加载则忽略（滚动触发的重复调用很常见）
        if (cached.isEnd || cached.isLoadingMore) return

        val nextBatch = cached.batch + 1
        updateCategoryCache(category.typeCode) { it.copy(isLoadingMore = true) }

        // 取消上一个在途请求，保证同一时刻只有一个类别在拉取
        selectionLoadJob?.cancel()
        selectionLoadJob = viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    selector.listCoursesBatch(category = category, batch = nextBatch)
                }
            }.onSuccess { batch ->
                // 双保险：若期间用户已切走，丢弃这批结果而不污染其他类别
                if (_selectedCategory.value?.typeCode != category.typeCode) {
                    updateCategoryCache(category.typeCode) { it.copy(isLoadingMore = false) }
                    return@onSuccess
                }
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
                // 主动取消不算失败，不要弹错误
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
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

    /**
     * 重新加载指定类别：丢弃已缓存分页，回到第一批重新拉取。
     *
     * 供页面右下角的刷新按钮使用。之所以整体重置而非增量刷新，是因为
     * 教务的 `kcrow` 窗口编号依赖"从第一批开始数"，半途重拉会错位。
     */
    fun reloadCategory(category: CourseCategory) {
        updateCategoryCache(category.typeCode) { LoadedCourses() }
        // 已选清单是过滤依据，刷新课程时一并刷新
        refreshEnrolled(silent = true)
        loadNextBatch(forCategory = category)
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
     * ## 为什么必须传整个 [group] 而不是单个课程
     *
     * 教学班详情接口**不返回 `jxbmc`（教学班名）与 `jxbzls`（子课程数）**，
     * 这两个字段只能从课程列表的**同一个 `jxb_id`** 那条记录回填。
     * 若只拿组内第一条去补，多教学班课程的其余教学班就会显示空名称，
     * 且 `jxbzls` 缺失会导致**含多子课程的教学班被误判为单子课程**——
     * 提交时只传一个 `do_jxb_id`，结果是选课不完整。
     *
     * 因此这里接收整个分组，建立 `jxb_id → 列表项` 映射逐个回填。
     *
     * @param onLoaded 回调在主线程执行，供 UI 弹出选择面板
     * @param onError 失败时的用户可读信息
     */
    fun loadClasses(
        group: CourseGroup,
        onLoaded: (List<CourseGroup>) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    selector.classesOf(
                        courseId = group.course.courseId.ifBlank { group.course.courseCode },
                        category = _selectedCategory.value
                    )
                }
            }.onSuccess { classes ->
                val groups = withContext(Dispatchers.Default) {
                    // 列表项按 jxb_id 建索引，供回填 jxbmc / jxbzls
                    val listByJxbId = group.classes.associateBy { it.classId }

                    classes
                        .groupBy { it.courseId.ifBlank { it.courseCode } }
                        .map { (key, detailRows) ->
                            val mapped = detailRows.map { detail ->
                                // 详情行的 classId 即 jxb_id，用它精确匹配列表项
                                val fromList = listByJxbId[detail.classId]
                                detail.toSelectableCourse(
                                    fallback = fromList ?: group.course
                                )
                            }
                            CourseGroup(key = key, course = mapped.first(), classes = mapped)
                        }
                }
                onLoaded(groups)
            }.onFailure { e ->
                handleFailure(e)
                onError(e.friendlyMessage())
            }
        }
    }

    /**
     * 把教学班详情转成"可提交的课程"形态。
     *
     * 与列表接口的差异必须在这里对齐，否则提交会失败：
     * - **`classId` 取 `doJxbId`** —— 选课提交真正需要的是 `do_jxb_id`，
     *   而非列表返回的 `jxb_id`（两者不是同一个值）
     * - **`className` / `subCourseCount` 必须从列表回填** —— 详情接口不返回
     *   `jxbmc` 与 `jxbzls`，缺失会导致教学班无名、并把多子课程教学班
     *   误判成单子课程
     *
     * @param fallback 同一 `jxb_id` 的**列表项**（由调用方按 jxb_id 精确匹配后传入）
     */
    private fun CourseClass.toSelectableCourse(fallback: SelectableCourse): SelectableCourse =
        SelectableCourse(
            courseId = courseId.ifBlank { fallback.courseId },
            courseCode = courseCode.ifBlank { fallback.courseCode },
            courseName = courseName.ifBlank { fallback.courseName },
            credits = credits.ifBlank { fallback.credits },
            classId = doJxbId.ifBlank { classId },
            // 详情为空，必须回填列表的 jxbmc
            className = className.ifBlank { fallback.className },
            rankInBatch = fallback.rankInBatch,
            typeCode = fallback.typeCode,
            isRetake = isRetake,
            isMinor = isMinor,
            hasPrerequisite = fallback.hasPrerequisite,
            isRecommended = fallback.isRecommended,
            subCourseCount = subCourseCount.ifBlank { fallback.subCourseCount },
            enrolledCount = classEnrolledCount.ifBlank { enrolledCount },
            totalHours = fallback.totalHours,
            courseNature = courseNature,
            // 容量与详情已选人数来自详情接口 —— "已满禁选"精确判定的唯一依据
            capacity = capacity,
            classEnrolledCount = classEnrolledCount
        )

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
                val alreadyEnrolled = enrolledList?.let { course.isEnrolledIn(it) } ?: false

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
                        is SelectionOutcome.ParameterRejected -> {
                            // 教务拒绝提交（flag=0）。**绝不引导重登** ——
                            // 这是参数校验问题，重登没有意义，只会让用户陷入死循环。
                            pushFeedback(
                                "教务拒绝了本次提交：${result.rawMessage}。" +
                                        "若反复出现请尝试先刷新课程列表再选",
                                isSuccess = false
                            )
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
                    val nowEnrolled = confirmed?.let { list ->
                        isCourseEnrolled(
                            selectableCourseId = targetId,
                            selectableCourseCode = course.courseCode,
                            selectableCourseName = course.courseName,
                            enrolled = list
                        )
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
            sessionActive = false,
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
                                sessionExpired = true,
                                sessionActive = false
                            )
                            pushFeedback("会话已失效，请重新登录", isSuccess = false)
                        }
                        is SelectionOutcome.Failure -> {
                            // 透传教务原文（_DROP_MSG），而非笼统"失败"
                            pushFeedback(result.rawMessage.ifBlank { "退选失败" }, isSuccess = false)
                        }
                        is SelectionOutcome.ParameterRejected -> {
                            // 教务拒绝退选（code=4 非法访问）——同样不引导重登
                            pushFeedback(
                                "教务拒绝了退选：${result.rawMessage}。请刷新后重试",
                                isSuccess = false
                            )
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
                // 标记清单就绪 —— 过滤已选课程的前置条件（见 displayedCourses）
                _isEnrolledReady.value = true
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
            _uiState.value = _uiState.value.copy(
                isLoggedIn = false,
                sessionExpired = true,
                sessionActive = false
            )
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
     * ## 为什么默认不清 [CourseSelectionUiState.isLoggedIn]
     *
     * 退出时会先调用本方法、再执行导航返回。若在这里把 `isLoggedIn` 置为 false，
     * 返回动画的一帧里选课页会重新组合成"未登录"分支，用户会**瞬间看到密码输入界面**
     * —— 这是必须避免的视觉瑕疵。
     *
     * 因此默认保留 `isLoggedIn`（数据已清空，下次进入会重新拉取），
     * 同时 Session 已被 [ScnuCookieJar.clear] 失效，安全性不受影响。
     * 调用方若要立刻复位 UI（例如测试或强制登出），传 `clearUserData = true`。
     *
     * 由 UI 层在**显式返回**时调用（顶部返回键 / 系统返回键）。
     */
    fun clearSession(clearUserData: Boolean = false) {
        selectionLoadJob?.cancel()
        selectionLoadJob = null
        _coursesByCategory.value = emptyMap()
        _enrolledCourses.value = emptyList()
        _isEnrolledReady.value = false
        _categories.value = emptyList()
        _selectedCategory.value = null
        _roundInfo.value = SelectionRoundInfo()
        keyword.value = ""
        _feedback.value = null
        pendingSelection = null
        if (clearUserData) {
            _uiState.value = CourseSelectionUiState()
        } else {
            // 保留 isLoggedIn（避免返回动画期间闪出登录面板），
            // 但把 sessionActive 置为 false —— 会话确实已被清掉，
            // 下次进入必然要求重新登录，而不会进到一片空白的数据页。
            // 注意：这里**不**设置 sessionExpired，那个字段专表"浏览中途失效"，
            // 否则校园页弹窗会误显示"会话过期"。
            _uiState.value = _uiState.value.copy(
                isLoggingIn = false,
                errorMessage = null,
                infoMessage = null,
                sessionActive = false
            )
        }
        // 清空共享会话，确保下次进入必须重新登录
        (cookieJar as? ScnuCookieJar)?.clear()
    }

    /**
     * 当前是否持有一个**可用**的选课会话。
     *
     * 与 `uiState.isLoggedIn` 的区别：后者在退出模块后仍为 true（为了不在返回
     * 动画中闪出登录面板），因此不能直接用来判断"能否直接进选课页"。
     * 校园页的选课卡应使用本方法决定是弹登录框还是直接进入。
     */
    fun hasActiveSession(): Boolean = _uiState.value.sessionActive

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

    /**
     * 判断异常是否属于"会话已失效"类别。
     *
     * 只认**明确信号**：教务的 `911` 状态码，或消息中显式提到"会话"。
     *
     * 刻意**不**匹配"登录"字样 —— `ScnuLoginException` 的消息是"登录失败: 账号或
     * 密码错误"，那是凭据错误而非会话失效，若判成会话失效会让 UI 反复提示重登，
     * 用户就会陷入"重登 → 又提示重登"的循环。
     */
    private fun Throwable.isSessionError(): Boolean {
        val text = message.orEmpty()
        return text.contains("会话") || text.contains("911")
    }
}
