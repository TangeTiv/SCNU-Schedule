package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.widget.Toast
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseCategory
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseClass
import com.xingheyuzhuan.shiguangschedule.data.network.selection.EnrolledCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectableCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectionRoundInfo
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 自主选课页面（二级页面，无底部导航栏）。
 *
 * ## 布局结构
 *
 * **未登录**：居中登录卡片（学号预填、密码需重新输入）
 *
 * **已登录**：`LazyColumn`
 * 1. 轮次信息条（含"不在选课时间"警告）
 * 2. 会话失效横幅（**不清空已加载列表**）
 * 3. 类别 Tab 行（已选课程 + 各课程类别）
 * 4. 「已选课程」列表 或 可选课程列表（滚动到底自动续拉）
 *
 * ## 视觉语言
 *
 * 全程使用 `MaterialTheme.colorScheme`，跟随深色模式，
 * 与同模块的 [SyncSelectionScreen] 保持一致，不硬编码浅色。
 *
 * ## 生命周期
 *
 * 离开本页面时通过 [DisposableEffect] 调用 `clearSession()`，
 * 清空课程缓存**并清除共享的 SCNU 会话**，确保下次进入必须重新登录。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSelectionScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: CourseSelectionViewModel = hiltViewModel()
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
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()

    val context = LocalContext.current

    // 当前是否在「已选课程」视图（区别于课程类别 Tab）
    var showEnrolledTab by remember { mutableStateOf(false) }

    // 教学班选择面板
    var pendingClassSheet by remember { mutableStateOf<PendingClassSheet?>(null) }
    // 待退选课程
    var pendingDrop by remember { mutableStateOf<EnrolledCourse?>(null) }

    // ── 离开页面时清理本地数据 + 共享会话 ──
    DisposableEffect(Unit) {
        onDispose { viewModel.clearSession() }
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
                    IconButton(onClick = onBack) {
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
                onCourseClick = { course ->
                    viewModel.loadClasses(
                        course = course,
                        onLoaded = { classes ->
                            pendingClassSheet = PendingClassSheet(course, classes)
                        },
                        onError = { }
                    )
                },
                onDropClick = { enrolled -> pendingDrop = enrolled }
            )
        }
    }

    // ── 教学班选择 / 子课程勾选 ──
    pendingClassSheet?.let { sheet ->
        ClassSelectionSheet(
            course = sheet.course,
            classes = sheet.classes,
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
    val course: SelectableCourse,
    val classes: List<CourseClass>
)

// ═══════════════════════════════════════════════════════════════════════════
// 登录面板
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
                // ── 顶部渐变头 ──
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
                    if (sessionExpired) {
                        Text(
                            text = stringResource(R.string.campus_course_selection_session_expired),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.campus_course_selection_login_desc),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text(stringResource(R.string.campus_sync_account_label)) },
                        placeholder = { Text(stringResource(R.string.campus_sync_account_placeholder)) },
                        singleLine = true,
                        enabled = !isLoggingIn,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.campus_sync_password_label)) },
                        placeholder = { Text(stringResource(R.string.campus_sync_password_placeholder)) },
                        singleLine = true,
                        enabled = !isLoggingIn,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
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
    displayedCourses: List<SelectableCourse>,
    enrolledCourses: List<EnrolledCourse>,
    isLoadingEnrolled: Boolean,
    showEnrolledTab: Boolean,
    onShowEnrolledTab: () -> Unit,
    onShowCategoryTab: (CourseCategory) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefreshEnrolled: () -> Unit,
    onCourseClick: (SelectableCourse) -> Unit,
    onDropClick: (EnrolledCourse) -> Unit
) {
    val listState = rememberLazyListState()

    // ── 滚动到底自动续拉下一批 ──
    // 用 derivedStateOf 收敛成布尔，避免每个像素都触发 LaunchedEffect 重启。
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
            .collect { reached ->
                if (reached) onLoadMore()
            }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        isDropping = false,
                        onDropClick = { onDropClick(enrolled) }
                    )
                }
                item {
                    TextButton(
                        onClick = onRefreshEnrolled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sync,
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

            if (displayedCourses.isEmpty() && !courses.isLoadingMore) {
                item { EmptyHint(text = stringResource(R.string.campus_course_selection_no_courses)) }
            } else {
                items(
                    items = displayedCourses,
                    key = { it.dedupeKey }
                ) { course ->
                    CourseRow(course = course, onClick = { onCourseClick(course) })
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
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

            // 不在选课时间：警告但不禁用浏览（只禁用"选课"按钮，见 CourseClassRow）
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
 * 可选课程行。
 *
 * 展示课程号、课程名、学分、教学班名、已选人数；`jxbzls > 1` 时标注"含子课程"。
 */
@Composable
private fun CourseRow(course: SelectableCourse, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.courseName.ifBlank { "—" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (course.courseCode.isNotBlank()) {
                        MetaText(course.courseCode)
                    }
                    if (course.credits.isNotBlank()) {
                        MetaText(stringResource(R.string.campus_course_selection_credits, course.credits))
                    }
                    if (course.enrolledCount.isNotBlank()) {
                        MetaText(stringResource(R.string.campus_course_selection_enrolled_count, course.enrolledCount))
                    }
                }
                if (course.className.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = course.className,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (course.hasSubCourses) {
                Spacer(modifier = Modifier.width(8.dp))
                // 子课程标记：提示该教学班需勾选子课程后才能选课
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.campus_course_selection_sub_course_badge),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

/**
 * 已选课程行。
 *
 * 退选按钮触发二次确认（对话框由页面层持有），且提交期间禁用。
 */
@Composable
private fun EnrolledCourseRow(
    course: EnrolledCourse,
    isDropping: Boolean,
    onDropClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course.courseName.ifBlank { "—" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (course.teacherName.isNotBlank()) MetaText(course.teacherName)
                    if (course.credits.isNotBlank()) {
                        MetaText(stringResource(R.string.campus_course_selection_credits, course.credits))
                    }
                }
                if (course.classTime.isNotBlank() || course.classLocation.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = listOf(course.classTime, course.classLocation)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            TextButton(onClick = onDropClick, enabled = !isDropping) {
                Text(
                    text = stringResource(R.string.campus_course_selection_drop),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
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
