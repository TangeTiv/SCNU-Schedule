package com.xingheyuzhuan.shiguangschedule.ui.campus

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.components.BottomNavigationBar
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// color palette for the warm-toned campus section (light mode only)
private val SurfaceBackgroundColor = Color(0xFFFCF9F8)
private val CardBackgroundColor = Color(0xFFEFE8E4)
private val TextPrimary = Color(0xFF333333)
private val TextSecondary = Color(0xFF666666)

/**
 * 星期名的格式化参数。
 *
 * 提为顶层常量而非写在 Composable 内：两者都是 immutable 的单例
 * （[TextStyle.FULL] 为枚举、[Locale.CHINESE] 为常量），无需每帧重建，
 * 也避免 `remember` 键值引入不必要的相等性比较。
 */
private val WEEKDAY_TEXT_STYLE = TextStyle.FULL
private val WEEKDAY_LOCALE = Locale.CHINESE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    campusViewModel: CampusViewModel = hiltViewModel(),
    // 选课模块的 ViewModel 由 MainActivity 在 NavDisplay 之上创建后传入。
    // 不在此处 hiltViewModel()：NavDisplay 的 ViewModel 作用域是单个 NavEntry，
    // 在此创建会在导航到选课页时被销毁，导致登录态丢失。
    courseSelectionViewModel: CourseSelectionViewModel = hiltViewModel()
) {
    val campusState by campusViewModel.campusState.collectAsStateWithLifecycle()
    val isDark = LocalIsDarkTheme.current

    // 选课登录对话框的显隐。点击【选课】卡片时置为 true —— 按需求
    // 先弹框输入凭据，登录成功后才导航进选课界面，而不是直接开新页面。
    var showSelectionLogin by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = if (isDark) MaterialTheme.colorScheme.surface else SurfaceBackgroundColor,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.campus_title_discover),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = { onNavigate(Destination.Settings) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = if (isDark) MaterialTheme.colorScheme.surface else SurfaceBackgroundColor
                )
            )
        },
        bottomBar = {
            BottomNavigationBar(
                currentDestination = Destination.Campus,
                onTabSelected = onNavigate
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { WelcomeCard(state = campusState, isDark = isDark) }

            item {
                Text(
                    text = stringResource(R.string.campus_section_academic),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                )
                PrimaryServiceGrid(onNavigate = onNavigate, isDark = isDark)
            }

            // 次功能网格与选课卡放在同一个 item 内：
            // 这样「选课」与「图书馆资源」共享同一套列宽（各 1/3 栅格），
            // 且两行之间只有 12dp 间距，视觉上选课卡正好落在图书馆卡正下方。
            item {
                Column {
                    SecondaryServiceGrid(isDark = isDark)
                    Spacer(modifier = Modifier.height(12.dp))
                    TertiaryServiceGrid(
                        isDark = isDark,
                        onCourseSelectionClick = {
                            // 优先读 ViewModel 的显式会话判定，
                            // 而不是 uiState.isLoggedIn —— 退出模块后后者仍为 true
                            // （为了不在返回动画里闪出登录面板），
                            // 此时数据已清空，直接进入会看到一片空白页。
                            if (courseSelectionViewModel.hasActiveSession()) {
                                onNavigate(Destination.CourseSelection)
                            } else {
                                showSelectionLogin = true
                            }
                        }
                    )
                }
            }
        }
    }

    // ── 选课登录对话框 ──
    // 登录成功 → 关闭对话框并导航进选课页（此时 ViewModel 已是登录态）
    if (showSelectionLogin) {
        CourseSelectionLoginDialog(
            viewModel = courseSelectionViewModel,
            onSuccess = {
                showSelectionLogin = false
                onNavigate(Destination.CourseSelection)
            },
            onDismiss = { showSelectionLogin = false }
        )
    }
}

// region 欢迎卡片

@Composable
private fun WelcomeCard(state: CampusUiState, isDark: Boolean) {
    val weekNumber = state.weekNumber
    // 性能红线 3：禁止在 Composable 函数体里直接 new DateTimeFormatter /
    // LocalDate.now() 等 —— 它们在每次重组（含滚动、动画导致的每一帧）都会重新求值。
    // 此处用 remember 固定到首次组合；搭配 immutable 的 TextStyle/Locale 常量，
    // 使 getDisplayName 的格式化工作整场只发生一次。
    val dayOfWeekName = remember {
        LocalDate.now().dayOfWeek.getDisplayName(WEEKDAY_TEXT_STYLE, WEEKDAY_LOCALE)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else CardBackgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.campus_week_info, weekNumber ?: 1, dayOfWeekName),
                fontSize = 13.sp,
                color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.campus_school_name),
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.campus_school_subtitle),
                fontSize = 14.sp,
                color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else TextSecondary
            )

            if (state.todayCourses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                CourseCapsuleRow(courses = state.todayCourses, isDark = isDark)
            }
        }
    }
}

// endregion

// region 今日速览胶囊行

@Composable
private fun CourseCapsuleRow(courses: List<TodayCourseDisplay>, isDark: Boolean) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = courses,
            key = { "${it.courseName}_${it.startTime}" }
        ) { course ->
            Surface(
                shape = CircleShape,
                color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    else Color.White.copy(alpha = 0.5f),
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = course.startTime,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "【${course.courseName} - ${course.location}】",
                        fontSize = 13.sp,
                        color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// endregion

// region 主功能网格

@Composable
private fun PrimaryServiceGrid(onNavigate: (Destination) -> Unit, isDark: Boolean) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceCard(
                icon = Icons.Filled.Sync,
                iconBgColor = Color(0xFF6366F1),
                title = stringResource(R.string.campus_card_sync),
                subtitle = stringResource(R.string.campus_card_sync_desc),
                onClick = { onNavigate(Destination.SyncSelection) },
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
            ServiceCard(
                icon = Icons.Filled.Grading,
                iconBgColor = Color(0xFF10B981),
                title = stringResource(R.string.campus_card_grades),
                subtitle = stringResource(R.string.campus_card_grades_desc),
                onClick = { onNavigate(Destination.Grades) },
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceCard(
                icon = Icons.Filled.CalendarMonth,
                iconBgColor = Color(0xFFF97316),
                title = stringResource(R.string.campus_card_exams),
                subtitle = stringResource(R.string.campus_card_exams_desc),
                onClick = { onNavigate(Destination.Exams) },
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
            ServiceCard(
                icon = Icons.Filled.Map,
                iconBgColor = Color(0xFFF43F5E),
                title = stringResource(R.string.campus_card_map),
                subtitle = stringResource(R.string.campus_card_map_desc),
                onClick = { WeChatMiniProgramLauncher.launchMap(context) },
                modifier = Modifier.weight(1f),
                isDark = isDark
            )
        }
    }
}

@Composable
private fun ServiceCard(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBgColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconBgColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// endregion

// region 次功能网格

@Composable
private fun SecondaryServiceGrid(isDark: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallServiceCard(
            icon = Icons.Filled.LocalLibrary,
            iconBgColor = Color(0xFFD97706),
            title = stringResource(R.string.campus_service_library),
            modifier = Modifier.weight(1f),
            isDark = isDark
        )
        SmallServiceCard(
            icon = Icons.Filled.DirectionsBus,
            iconBgColor = Color(0xFF3B82F6),
            title = stringResource(R.string.campus_service_transport),
            modifier = Modifier.weight(1f),
            isDark = isDark
        )
        SmallServiceCard(
            icon = Icons.Filled.Campaign,
            iconBgColor = Color(0xFF8B5CF6),
            title = stringResource(R.string.campus_service_channel),
            modifier = Modifier.weight(1f),
            isDark = isDark
        )
    }
}

@Composable
private fun SmallServiceCard(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
    /**
     * 点击行为。
     *
     * 为 null 时卡片保持纯展示（现有「图书馆资源 / 校园交通 / 校园渠道」即为此状态，
     * 本次新增选课模块**不改变它们的交互**）。
     * 非 null 时整卡可点，但仍沿用完全相同的视觉规格。
     */
    onClick: (() -> Unit)? = null
) {
    val containerColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color.White
    }

    // 按需在可点击/不可点击两种 Card 重载之间切换，
    // 避免给不可点击的卡片凭空加一个无意义的 onClick 语义。
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.height(96.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            SmallServiceCardContent(icon, iconBgColor, title, isDark)
        }
    } else {
        Card(
            modifier = modifier.height(96.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = containerColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            SmallServiceCardContent(icon, iconBgColor, title, isDark)
        }
    }
}

/** [SmallServiceCard] 的内容体，抽出来供可点击/不可点击两种卡片复用 */
@Composable
private fun SmallServiceCardContent(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconBgColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = iconBgColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDark) MaterialTheme.colorScheme.onSurface else TextPrimary,
            maxLines = 1
        )
    }
}

// endregion

// region 三级功能网格（选课等后续模块）

/**
 * 第三行小卡网格：选课入口。
 *
 * ## 为什么这样排版
 *
 * 「选课」必须与「图书馆资源」**尺寸一致且位于其正下方**。实现方式是让两张卡
 * 都占用 1/3 栅格（`weight(1f)` + 相同的 12dp 间距），并让两行紧邻 ——
 * 于是列宽天然对齐，选课卡正对图书馆卡。
 *
 * 右侧两格刻意留空，不填假卡：后续模块可直接占用，无需重排已有卡片。
 */
@Composable
private fun TertiaryServiceGrid(
    isDark: Boolean,
    onCourseSelectionClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallServiceCard(
            icon = Icons.Filled.EditNote,
            iconBgColor = Color(0xFF0EA5E9),
            title = stringResource(R.string.campus_course_selection),
            modifier = Modifier.weight(1f),
            isDark = isDark,
            onClick = onCourseSelectionClick
        )
        // 预留位：与图书馆卡同列宽，保持网格对称
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.weight(1f))
    }
}

// endregion
