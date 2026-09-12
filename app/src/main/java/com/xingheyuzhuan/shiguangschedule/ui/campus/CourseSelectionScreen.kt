package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
    val savedAccount by viewModel.savedAccount.collectAsStateWithLifecycle()
    val courses by viewModel.currentCourses.collectAsStateWithLifecycle()
    val displayedCourses by viewModel.displayedCourses.collectAsStateWithLifecycle()
    val enrolledCourses by viewModel.enrolledCourses.collectAsStateWithLifecycle()
    val isLoadingEnrolled by viewModel.isLoadingEnrolled.collectAsStateWithLifecycle()
    val hiddenEnrolledCount by viewModel.hiddenEnrolledCount.collectAsStateWithLifecycle()
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
                savedAccount = savedAccount,
                isLoggingIn = uiState.isLoggingIn,
                sessionExpired = uiState.sessionExpired,
                onLogin = { account, password -> viewModel.login(account, password) },
                onReLogin = { account, password -> viewModel.reLogin(account, password) }
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
                hiddenEnrolledCount = hiddenEnrolledCount,
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
 * 登录卡片。
 *
 * - 学号：从 DataStore 的 `campus_account` **预填**（与教务同步共用）
 * - 密码：**不预填、不落盘**，每次进入模块都需重新输入
 *
 * [sessionExpired] 为 true 时使用 [onReLogin]（区分语义：会话过期而非首次登录）。
 */
@Composable
private fun LoginPane(
    modifier: Modifier = Modifier,
    savedAccount: String,
    isLoggingIn: Boolean,
    sessionExpired: Boolean,
    onLogin: (String, String) -> Unit,
    onReLogin: (String, String) -> Unit
) {
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    // 账号预填（仅在用户尚未输入时填充，避免覆盖正在编辑的内容）
    LaunchedEffect(savedAccount) {
        if (account.isEmpty() && savedAccount.isNotEmpty()) account = savedAccount
    }

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

                    AccountPasswordFields(
                        account = account,
                        onAccountChange = { account = it },
                        password = password,
                        onPasswordChange = { password = it },
                        passwordVisible = passwordVisible,
                        onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                        enabled = !isLoggingIn
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (sessionExpired) {
                                onReLogin(account.trim(), password)
                            } else {
                                onLogin(account.trim(), password)
                            }
                        },
                        enabled = !isLoggingIn && account.isNotBlank() && password.isNotBlank(),
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
    enabled: Boolean
) {
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
    hiddenEnrolledCount: Int,
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

                // 已选课程被隐藏的说明，避免用户以为课程凭空消失
                if (hiddenEnrolledCount > 0) {
                    item {
                        HiddenEnrolledNotice(count = hiddenEnrolledCount)
                    }
                }

                if (displayedCourses.isEmpty() && !courses.isLoadingMore) {
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

/** 已选课程被隐去时的说明条 */
@Composable
private fun HiddenEnrolledNotice(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.campus_course_selection_hidden_enrolled, count),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
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
 * 结构完全沿用考试卡：`Card(16dp 圆角, containerColor=surface)` →
 * `Column(16dp/14dp padding)` → 头部行（标题 + 右侧 Badge）→ 带图标的信息行。
 * 差别只在中性色上用主题色而非考试卡的硬编码色，以兼容深色模式。
 *
 * ## 分组展示
 *
 * 一门课一张卡。卡片右侧 Badge 显示可选教学班数量，因此同一门课不会
 * 因为"有 3 个教学班"而重复出现 3 次。
 */
@Composable
private fun CourseRow(group: CourseGroup, onClick: () -> Unit) {
    val course = group.course
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 头部行：课程名 + 教学班数量 Badge（对应考试卡的倒计时 Badge 位置）
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
                InfoBadge(
                    text = if (group.classCount > 1) {
                        stringResource(R.string.campus_course_selection_class_count, group.classCount)
                    } else {
                        stringResource(R.string.campus_course_selection_single_class)
                    },
                    contentColor = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 信息行：课程号 + 学分（对应考试卡的时间行）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 信息行：已选人数 + 子课程提示（对应考试卡的地点行）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (course.hasSubCourses) {
                        Icons.Filled.WarningAmber
                    } else {
                        Icons.Filled.Info
                    },
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
                    text = buildString {
                        if (course.enrolledCount.isNotBlank()) {
                            append(
                                stringResource(
                                    R.string.campus_course_selection_enrolled_count,
                                    course.enrolledCount
                                )
                            )
                        }
                        if (course.hasSubCourses) {
                            if (isNotEmpty()) append(" · ")
                            append(stringResource(R.string.campus_course_selection_sub_course_hint))
                        }
                        if (isEmpty()) append("—")
                    },
                    fontSize = 14.sp,
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                TextButton(onClick = onDropClick) {
                    Text(
                        text = stringResource(R.string.campus_course_selection_drop),
                        color = MaterialTheme.colorScheme.error,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = listOf(course.classTime, course.classLocation)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.campus_course_selection_time_pending) },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
