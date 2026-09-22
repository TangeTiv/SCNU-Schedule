package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseCategory
import com.xingheyuzhuan.shiguangschedule.data.network.selection.EnrolledCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectionRoundInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 自主选课页面（二级页面，无底部导航栏）。
 *
 * ## 布局结构
 *
 * **未登录**（例如会话中途失效后停留在此页）：居中登录卡片
 *
 * **已登录**：`Scaffold` + `LazyColumn`
 * 1. 轮次信息条（学年学期 + 已选统计 + 不在选课时间的警告）
 * 2. 悬浮控制按钮：回到顶部 + 刷新（常驻右下角）
 * 3. 类别 Tab 行（已选课程 + 各课程类别）
 * 4. 搜索框 / 已隐藏已选课程的提示
 * 5. 课程卡片（**按课程分组**，一门课一张卡，样式对齐 [ExamScreen] 的考试卡）
 *
 * ## 登录入口
 *
 * 正常入口在【校园】页的登录对话框（`CourseSelectionLoginDialog`），
 * 登录成功后才导航到本页，因此进入时通常已是登录态。
 * 本页的登录面板只用于**会话中途失效后原地重登**。
 *
 * ## 视觉语言
 *
 * 全程使用 `MaterialTheme.colorScheme`，跟随深色模式，
 * 与同模块的 [SyncSelectionScreen] 保持一致，不硬编码浅色。
 *
 * ## 生命周期与会话清理
 *
 * 会话清理挂在**显式返回**上（顶部返回键 / 系统返回键，见 [exitModule]），
 * 而**不是** `onDispose`。原因：从本页导航去「同步课表」等兄弟页面时
 * ViewModel 仍然存活，若在 `onDispose` 里清会话，用户同步完课表回来
 * 就得重新登录。
 *
 * @param viewModel 由 MainActivity 在 NavDisplay 之上创建并透传，
 *                  与【校园】页的登录对话框共用同一实例
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectionScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: CourseSelectionViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val roundInfo by viewModel.roundInfo.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val maskedAccount by viewModel.maskedAccount.collectAsStateWithLifecycle()
    val courses by viewModel.currentCourses.collectAsStateWithLifecycle()
    val displayedCourses by viewModel.displayedCourses.collectAsStateWithLifecycle()
    val enrolledCourses by viewModel.enrolledCourses.collectAsStateWithLifecycle()
    val isLoadingEnrolled by viewModel.isLoadingEnrolled.collectAsStateWithLifecycle()
    val isEnrolledReady by viewModel.isEnrolledReady.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 当前是否在「已选课程」视图（区别于课程类别 Tab）
    var showEnrolledTab by remember { mutableStateOf(false) }

    // 教学班选择面板
    var pendingClassSheet by remember { mutableStateOf<PendingClassSheet?>(null) }
    // 待退选课程
    var pendingDrop by remember { mutableStateOf<EnrolledCourse?>(null) }

    // ── 退出模块：清空课程缓存 + 共享 SCNU 会话，再返回 ──
    // 清会话是必须的：ScnuCookieJar 是单例，若只清课程数据，下次进入会跳过登录。
    val exitModule: () -> Unit = {
        viewModel.clearSession()
        onBack()
    }

    // 拦截系统返回键，走同一套清理逻辑（否则物理返回会绕开清理）
    BackHandler(enabled = true, onBack = exitModule)

    // ── 进入页面后确保首个类别开始加载 ──
    // 登录发生在校园页对话框，此处负责"进入即出数据"。
    LaunchedEffect(uiState.isLoggedIn) {
        viewModel.ensureInitialLoad()
    }

    // ── 一次性反馈 → Toast ──
    LaunchedEffect(feedback) {
        feedback?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            viewModel.consumeFeedback()
        }
    }

    // ── 错误提示 → Toast ──
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.campus_course_selection),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = exitModule) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                actions = {
                    if (uiState.isLoggedIn) {
                        // 常驻「同步课表」入口：选课/退选后课表不会自动更新，
                        // 用户需要主动去教务同步。此处只导航，绝不自动触发。
                        IconButton(onClick = { onNavigate(Destination.SyncSelection) }) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = stringResource(R.string.campus_course_selection_sync_hint)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        if (!uiState.isLoggedIn) {
            LoginPane(
                modifier = Modifier.padding(innerPadding),
                maskedAccount = maskedAccount,
                isLoggingIn = uiState.isLoggingIn,
                sessionExpired = uiState.sessionExpired,
                onLogin = { account, password -> viewModel.login(account, password) },
                onReLogin = { account, password -> viewModel.reLogin(account, password) },
                onLoginWithSavedAccount = { password -> viewModel.loginWithSavedAccount(password) },
                onReLoginWithSavedAccount = { password -> viewModel.reLoginWithSavedAccount(password) },
                onGoToAccount = { onNavigate(Destination.Account) }
            )
        } else {
            CourseBrowserPane(
                modifier = Modifier.padding(innerPadding),
                roundInfo = roundInfo,
                categories = categories,
                selectedCategory = selectedCategory,
                courses = courses,
                displayedCourses = displayedCourses,
                enrolledCourses = enrolledCourses,
                isLoadingEnrolled = isLoadingEnrolled,
                isEnrolledReady = isEnrolledReady,
                showEnrolledTab = showEnrolledTab,
                onShowEnrolledTab = { showEnrolledTab = true },
                onShowCategoryTab = { category ->
                    showEnrolledTab = false
                    viewModel.selectCategory(category)
                },
                keyword = keyword,
                onKeywordChange = viewModel::onKeywordChange,
                onLoadMore = viewModel::loadNextBatch,
                onRefreshEnrolled = { viewModel.refreshEnrolled() },
                onRefreshCurrent = {
                    // 刷新当前类别：重置该类别缓存后重新拉首批
                    selectedCategory?.let { viewModel.reloadCategory(it) }
                },
                onCourseClick = { group ->
                    // 单教学班直接进；多教学班需要先拉详情
                    pendingClassSheet = PendingClassSheet(group = group)
                },
                onDropClick = { enrolled -> pendingDrop = enrolled }
            )
        }
    }

    // ── 教学班选择 / 子课程勾选 ──
    pendingClassSheet?.let { sheet ->
        ClassSelectionSheet(
            group = sheet.group,
            viewModel = viewModel,
            onDismiss = { pendingClassSheet = null }
        )
    }

    // ── 退选二次确认 ──
    pendingDrop?.let { enrolled ->
        DropConfirmDialog(
            course = enrolled,
            isDropping = viewModel.isDropping(enrolled.courseId.ifBlank { enrolled.courseCode }),
            onConfirm = {
                viewModel.dropCourse(enrolled)
                pendingDrop = null
            },
            onDismiss = { pendingDrop = null }
        )
    }
}

/** 教学班选择面板的持有状态 */
internal data class PendingClassSheet(
    val group: CourseGroup
)

// ═══════════════════════════════════════════════════════════════════════════
// 登录面板（仅用于会话中途失效后的原地重登）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 登录卡片（会话中途失效时的原地重登）。
 *
 * ## v1.7.0 起的行为
 *
 * - 已保存过凭据 → **只显示密码框**，学号以脱敏形式呈现（`为 2024****41 重新登录`）
 * - 从未保存过 → 显示完整表单，并提供去【我的 → 账号】的入口
 * - 密码仍然**不预填、不落盘**，只在本次会话的内存里短暂存在
 *
 * [sessionExpired] 为 true 时使用 [onReLogin]（区分语义：会话过期而非首次登录）。
 */
@Composable
private fun LoginPane(
    modifier: Modifier = Modifier,
    maskedAccount: String?,
    isLoggingIn: Boolean,
    sessionExpired: Boolean,
    onLogin: (String, String) -> Unit,
    onReLogin: (String, String) -> Unit,
    onLoginWithSavedAccount: (String) -> Unit,
    onReLoginWithSavedAccount: (String) -> Unit,
    onGoToAccount: () -> Unit
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // 收敛成非空局部量：既让 `useSavedAccount` 与展示文案同源，
    // 也避免 `useSavedAccount && maskedAccount != null` 这种"条件恒为真"的写法。
    val savedAccountText: String? = maskedAccount?.takeIf { it.isNotBlank() }
    val useSavedAccount = savedAccountText != null

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer
                                )
                            )
                        )
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.School,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.campus_course_selection),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = if (sessionExpired) {
                            stringResource(R.string.campus_course_selection_session_expired)
                        } else {
                            stringResource(R.string.campus_course_selection_login_desc)
                        },
                        fontSize = 13.sp,
                        color = if (sessionExpired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    if (savedAccountText != null) {
                        Text(
                            text = stringResource(
                                R.string.campus_course_selection_login_for_account,
                                savedAccountText
                            ),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }

                    AccountPasswordFields(
                        account = account,
                        onAccountChange = { account = it },
                        password = password,
                        onPasswordChange = { password = it },
                        passwordVisible = passwordVisible,
                        onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                        enabled = !isLoggingIn,
                        showAccountField = !useSavedAccount
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            when {
                                // 会话中途失效且已保存学号 → 走"原地重登"（保留已加载列表）
                                sessionExpired && useSavedAccount ->
                                    onReLoginWithSavedAccount(password)
                                useSavedAccount -> onLoginWithSavedAccount(password)
                                sessionExpired -> onReLogin(account.trim(), password)
                                else -> onLogin(account.trim(), password)
                            }
                        },
                        enabled = !isLoggingIn && password.isNotBlank() &&
                                (useSavedAccount || account.isNotBlank()),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        if (isLoggingIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.campus_course_selection_logging_in))
                        } else {
                            Text(
                                text = stringResource(R.string.campus_course_selection_login_action),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.campus_course_selection_password_notice),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (!useSavedAccount) {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(onClick = onGoToAccount, enabled = !isLoggingIn) {
                            Text(
                                text = stringResource(R.string.campus_course_selection_go_account),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 学号 / 密码输入组。
 *
 * 同时被【校园】页的登录对话框与本页的重登面板复用，
 * 保证两处输入体验与校验提示完全一致。
 */
@Composable
internal fun AccountPasswordFields(
    account: String,
    onAccountChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    enabled: Boolean,
    /**
     * 是否显示学号输入框。
     *
     * 已保存过凭据时置 false —— 学号由凭据仓库提供（只以脱敏形式展示），
     * 用户只需补密码即可，不必重新输一遍学号。
     */
    showAccountField: Boolean = true
) {
    if (showAccountField) {
        OutlinedTextField(
            value = account,
            onValueChange = onAccountChange,
            label = { Text(stringResource(R.string.campus_sync_account_label)) },
            placeholder = { Text(stringResource(R.string.campus_sync_account_placeholder)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))
    }

    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.campus_sync_password_label)) },
        placeholder = { Text(stringResource(R.string.campus_sync_password_placeholder)) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = onTogglePasswordVisible) {
                Icon(
                    imageVector = if (passwordVisible) {
                        Icons.Filled.Visibility
                    } else {
                        Icons.Filled.VisibilityOff
                    },
                    contentDescription = if (passwordVisible) {
                        stringResource(R.string.a11y_hide_password)
                    } else {
                        stringResource(R.string.a11y_show_password)
                    }
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 课程浏览面板
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CourseBrowserPane(
    modifier: Modifier = Modifier,
    roundInfo: SelectionRoundInfo,
    categories: List<CourseCategory>,
    selectedCategory: CourseCategory?,
    courses: LoadedCourses,
    displayedCourses: List<CourseGroup>,
    enrolledCourses: List<EnrolledCourse>,
    isLoadingEnrolled: Boolean,
    /** 已选清单是否已就绪；未就绪时展示加载态而非"无课程" */
    isEnrolledReady: Boolean,
    showEnrolledTab: Boolean,
    onShowEnrolledTab: () -> Unit,
    onShowCategoryTab: (CourseCategory) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefreshEnrolled: () -> Unit,
    onRefreshCurrent: () -> Unit,
    onCourseClick: (CourseGroup) -> Unit,
    onDropClick: (EnrolledCourse) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── 悬浮控制按钮：回到顶部 + 刷新 ──
    Box(modifier = modifier.fillMaxSize()) {
        val shouldLoadMore by remember {
            derivedStateOf {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val total = listState.layoutInfo.totalItemsCount
                total > 0 && lastVisible >= total - 3
            }
        }

        LaunchedEffect(listState, showEnrolledTab, selectedCategory?.typeCode) {
            if (showEnrolledTab) return@LaunchedEffect
            snapshotFlow { shouldLoadMore }
                .distinctUntilChanged()
                .collect { reached -> if (reached) onLoadMore() }
        }

        // ── 视口未填满则持续续拉 ──
        //
        // 滚动触发的续拉有个盲区：若首批返回的条数不足以填满屏幕，列表根本
        // 无法滚动，`shouldLoadMore` 也就永远不会变为 true —— 表现为
        // "登录后主修课程只显示少量课程"。这里在每次数据落地后主动检查一次，
        // 直到填满视口或确认到底为止。
        LaunchedEffect(courses.items.size, courses.isEnd, courses.isLoadingMore, showEnrolledTab) {
            if (showEnrolledTab) return@LaunchedEffect
            if (!courses.isEnd && !courses.isLoadingMore && shouldLoadMore) {
                onLoadMore()
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                // 给右下角悬浮按钮留出空间，避免遮挡最后一项
                bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { RoundInfoBar(roundInfo = roundInfo) }

            item {
                CategoryTabs(
                    categories = categories,
                    selectedCategory = selectedCategory,
                    showEnrolledTab = showEnrolledTab,
                    enrolledCount = enrolledCourses.size,
                    onShowEnrolledTab = onShowEnrolledTab,
                    onShowCategoryTab = onShowCategoryTab
                )
            }

            if (showEnrolledTab) {
                // ── 已选课程视图 ──
                if (isLoadingEnrolled && enrolledCourses.isEmpty()) {
                    item { LoadingRow() }
                } else if (enrolledCourses.isEmpty()) {
                    item { EmptyHint(text = stringResource(R.string.campus_course_selection_no_enrolled)) }
                } else {
                    items(
                        items = enrolledCourses,
                        key = { it.courseId.ifBlank { it.courseCode } }
                    ) { enrolled ->
                        EnrolledCourseRow(
                            course = enrolled,
                            onDropClick = { onDropClick(enrolled) }
                        )
                    }
                    item {
                        TextButton(
                            onClick = onRefreshEnrolled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.campus_course_selection_refresh))
                        }
                    }
                }
            } else {
                // ── 可选课程视图 ──
                item {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = onKeywordChange,
                        placeholder = { Text(stringResource(R.string.campus_course_selection_search_hint)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 已选清单未就绪时不显示课程 —— 过滤依据尚未到位，
                // 此时渲染会导致已选课程先出现再消失，或干脆漏过滤
                if (!isEnrolledReady) {
                    item { LoadingRow() }
                } else if (displayedCourses.isEmpty() && !courses.isLoadingMore) {
                    item { EmptyHint(text = stringResource(R.string.campus_course_selection_no_courses)) }
                } else {
                    items(
                        items = displayedCourses,
                        key = { it.key }
                    ) { group ->
                        CourseRow(group = group, onClick = { onCourseClick(group) })
                    }
                }

                if (courses.isLoadingMore) {
                    item { LoadingRow() }
                } else if (courses.isEnd && displayedCourses.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.campus_course_selection_all_loaded),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // 悬浮控制按钮（与刷新放在一起，按需求合并为一组）
        SelectionControlButtons(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 20.dp),
            onBackToTop = {
                scope.launch { listState.animateScrollToItem(0) }
            },
            onRefresh = {
                if (showEnrolledTab) onRefreshEnrolled() else onRefreshCurrent()
            }
        )
    }
}

/**
 * 右下角悬浮控制按钮组：回到顶部 + 刷新。
 *
 * 两个按钮合并为一个纵向 pill 组，紧邻放置 —— 按需求"和刷新按钮放到一起"。
 */
@Composable
private fun SelectionControlButtons(
    modifier: Modifier = Modifier,
    onBackToTop: () -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 6.dp
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onBackToTop) {
                Icon(
                    imageVector = Icons.Filled.VerticalAlignTop,
                    contentDescription = stringResource(R.string.campus_course_selection_back_to_top),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .width(28.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f))
            )
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.campus_course_selection_refresh_list),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 轮次信息条
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun RoundInfoBar(roundInfo: SelectionRoundInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = roundInfo.termDisplay,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (roundInfo.selectedCount.isNotBlank() || roundInfo.selectedCredits.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.campus_course_selection_credit_summary,
                            roundInfo.selectedCount.ifBlank { "—" },
                            roundInfo.selectedCredits.ifBlank { "—" }
                        ),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 不在选课时间：警告但不禁用浏览（只禁用"选课"按钮）
            if (!roundInfo.isInSelectionWindow) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.WarningAmber,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.campus_course_selection_out_of_window),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 类别 Tab
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 类别 Tab 行。
 *
 * 第一个固定为「已选课程」（权威清单，退选入口），其后是教务页面解析出的
 * 各课程类别（主修 01 / 通识选修 10 / 第二类 41 …）。
 *
 * 用 `LazyRow` 而非 `ScrollableTabRow`：后者需要固定宽度的 Tab 才能正确居中
 * 指示器，而类别名称由教务下发、长度不可控。
 */
@Composable
private fun CategoryTabs(
    categories: List<CourseCategory>,
    selectedCategory: CourseCategory?,
    showEnrolledTab: Boolean,
    enrolledCount: Int,
    onShowEnrolledTab: () -> Unit,
    onShowCategoryTab: (CourseCategory) -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            CategoryChip(
                label = if (enrolledCount > 0) {
                    "${stringResource(R.string.campus_course_selection_enrolled)} $enrolledCount"
                } else {
                    stringResource(R.string.campus_course_selection_enrolled)
                },
                selected = showEnrolledTab,
                onClick = onShowEnrolledTab
            )
        }
        items(categories, key = { it.typeCode }) { category ->
            CategoryChip(
                label = category.displayName,
                selected = !showEnrolledTab && selectedCategory?.typeCode == category.typeCode,
                onClick = { onShowCategoryTab(category) }
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        onClick = onClick
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 列表行
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 可选课程卡片。
 *
 * ## UI 对齐 [ExamScreen] 的考试卡
 *
 * 结构沿用考试卡：`Card(16dp 圆角)` → `Column(16dp/14dp)` → 头部行（标题 +
 * 右侧 Badge）→ 带图标的信息行。差别在于**用色彩承担信息区分**：
 *
 * | 信息 | 配色 |
 * |---|---|
 * | 教学班数量 Badge | 主色 / 已满时错误色 / 含子课程时第三色 |
 * | 课程号 + 学分 | 主色（可扫读的标识信息） |
 * | 已选人数 | 已满错误色、余量紧张第三色、充足时中性灰 |
 * | 含子课程提示 | 第三色 |
 *
 * ## 深色模式
 *
 * 卡片底色取 `surfaceContainerHigh` 而非 `surface`：深色主题下 `surface`
 * 接近纯黑，卡片与页面背景无法区分。`surfaceContainerHigh` 是浅一档的容器色，
 * 在浅色下仍接近白色，两端都有清晰层次。
 *
 * ## 已满课程
 *
 * 列表接口不返回容量，只能按**已选人数**与阈值（[FULL_THRESHOLD]）标注提示；
 * 真正的容量判定在教学班面板里用 `jxbrl` 完成并禁用按钮。
 */
@Composable
private fun CourseRow(group: CourseGroup, onClick: () -> Unit) {
    val course = group.course

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        // 描边让相邻卡片边界明确（仅靠底色在深色下几乎分不出彼此）
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 头部行：课程名 + 状态 Badge
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.courseName.ifBlank { "—" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 列表层不标注"已满"：列表接口无容量字段，任何判定都是猜测。
                // 仅区分"含子课程"与"教学班数量"这两类**确定**信息。
                if (course.hasSubCourses) {
                    InfoBadge(
                        text = stringResource(R.string.campus_course_selection_sub_course_badge),
                        contentColor = MaterialTheme.colorScheme.tertiary
                    )
                } else {
                    InfoBadge(
                        text = if (group.classCount > 1) {
                            stringResource(R.string.campus_course_selection_class_count, group.classCount)
                        } else {
                            stringResource(R.string.campus_course_selection_single_class)
                        },
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 信息行：课程号 + 学分（主色，作为课程标识）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = listOf(
                        course.courseCode.takeIf { it.isNotBlank() },
                        course.credits.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.campus_course_selection_credits, it)
                        }
                    ).filterNotNull().joinToString(" · ").ifBlank { "—" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 信息行：子课程 / 教学班数量提示
            //
            // 人数**不在此处显示**：列表接口没有容量字段，光有已选人数无法判断
            // 是否已满，反而误导。人数与"已满"判定统一放在点开后的教学班弹窗里
            // （那里有 jxbrs/jxbrl 精确数据）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (course.hasSubCourses) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        course.hasSubCourses ->
                            stringResource(R.string.campus_course_selection_sub_course_hint)
                        group.classCount > 1 -> stringResource(
                            R.string.campus_course_selection_class_count,
                            group.classCount
                        )
                        else -> stringResource(R.string.campus_course_selection_tap_to_view_classes)
                    },
                    fontSize = 14.sp,
                    fontWeight = if (course.hasSubCourses) FontWeight.Medium else FontWeight.Normal,
                    color = if (course.hasSubCourses) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 已选课程卡片，样式与 [CourseRow] 对齐（沿用考试卡结构）。
 *
 * 退选按钮触发二次确认（对话框由页面层持有）。
 */
@Composable
private fun EnrolledCourseRow(
    course: EnrolledCourse,
    onDropClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        // 与 CourseRow 一致用 surfaceContainerHigh：深色下 surface 近纯黑，
        // 卡片彼此无法区分
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.courseName.ifBlank { "—" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 退选：红底白字按钮（而非仅红色文字），更醒目且明确是可执行操作
                Button(
                    onClick = onDropClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.campus_course_selection_drop),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 教师 + 学分
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = listOf(
                        course.teacherName.takeIf { it.isNotBlank() },
                        course.credits.takeIf { it.isNotBlank() }?.let {
                            stringResource(R.string.campus_course_selection_credits, it)
                        }
                    ).filterNotNull().joinToString(" · ").ifBlank { "—" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 上课时间 + 地点
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = listOf(course.classTime, course.classLocation)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.campus_course_selection_time_pending) },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 小角标，对应考试卡的 [CountdownBadge] 视觉规格 */
@Composable
private fun InfoBadge(text: String, contentColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun LoadingRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.campus_course_selection_loading),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
