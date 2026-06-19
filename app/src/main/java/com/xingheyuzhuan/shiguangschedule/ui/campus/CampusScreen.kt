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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// color palette for the warm-toned campus section
private val SurfaceBackgroundColor = Color(0xFFFCF9F8)
private val CardBackgroundColor = Color(0xFFEFE8E4)
private val TextPrimary = Color(0xFF333333)
private val TextSecondary = Color(0xFF666666)

/**
 * 校园 Dashboard 主页面。
 *
 * 全新暖色调视觉设计：
 * - 页面底色 #FCF9F8，凸显卡片阴影层次
 * - WelcomeCard 全宽居中排版，Elevation = 8.dp
 * - 支持有课程时显示「今日速览」胶囊行（CircleShape Pill）
 * - 两级功能网格：主功能区（2列水平卡片）+ 次功能区（3列垂直卡片）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    campusViewModel: CampusViewModel = hiltViewModel()
) {
    val campusState by campusViewModel.campusState.collectAsState()

    Scaffold(
        containerColor = SurfaceBackgroundColor,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.campus_title_discover),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = TextPrimary
                    )
                },
                actions = {
                    IconButton(onClick = { onNavigate(Destination.Settings) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "设置",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = SurfaceBackgroundColor
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
            // 1. 欢迎卡片（含今日速览胶囊行）
            item { WelcomeCard(state = campusState) }

            // 2. 教务与学业主功能区
            item {
                Text(
                    text = stringResource(R.string.campus_section_academic),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
                )
                PrimaryServiceGrid(onNavigate = onNavigate)
            }

            // 3. 校园服务次功能区
            item { SecondaryServiceGrid() }
        }
    }
}

// region 欢迎卡片

/**
 * 全宽横向欢迎卡片（暖色调）。
 *
 * 布局（全部居中对齐）：
 * 1. 周次 + 星期（13sp，浅色）
 * 2. 学校名称（26sp，ExtraBold）
 * 3. 副标题（14sp）
 * 4. 有课程时：24dp 间距 → 今日速览胶囊行（LazyRow，CircleShape）
 */
@Composable
private fun WelcomeCard(state: CampusUiState) {
    val weekNumber = state.weekNumber
    val dayOfWeekName = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackgroundColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶层：周次信息（次要、轻量）
            Text(
                text = stringResource(R.string.campus_week_info, weekNumber ?: 1, dayOfWeekName),
                fontSize = 13.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 视觉中心：学校名称（最大、最粗）
            Text(
                text = stringResource(R.string.campus_school_name),
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 副标题
            Text(
                text = stringResource(R.string.campus_school_subtitle),
                fontSize = 14.sp,
                color = TextSecondary
            )

            // 今日速览胶囊（仅在有课程数据时展示）
            if (state.todayCourses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                CourseCapsuleRow(courses = state.todayCourses)
            }
        }
    }
}

// endregion

// region 今日速览胶囊行

/**
 * 横向滚动的课程胶囊行（位于 WelcomeCard 内部）。
 *
 * 胶囊使用 CircleShape（两端完美半圆），白色半透明背景，
 * 文本格式：「08:30 【课程名 - 地点】」。
 */
@Composable
private fun CourseCapsuleRow(courses: List<TodayCourseDisplay>) {
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
                color = Color.White.copy(alpha = 0.5f),
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
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "【${course.courseName} - ${course.location}】",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// endregion

// region 主功能网格（2列水平卡片）

/**
 * 主功能区网格（2 列 × 2 行）。
 *
 * 水平排版 Card：40.dp 圆角图标 + 右侧标题与副标题。
 */
@Composable
private fun PrimaryServiceGrid(onNavigate: (Destination) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceCard(
                icon = Icons.Filled.Sync,
                iconBgColor = Color(0xFF6366F1),
                title = stringResource(R.string.campus_card_sync),
                subtitle = stringResource(R.string.campus_card_sync_desc),
                onClick = { onNavigate(Destination.SyncSelection) },
                modifier = Modifier.weight(1f)
            )
            ServiceCard(
                icon = Icons.Filled.Grading,
                iconBgColor = Color(0xFF10B981),
                title = stringResource(R.string.campus_card_grades),
                subtitle = stringResource(R.string.campus_card_grades_desc),
                onClick = { onNavigate(Destination.Grades) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServiceCard(
                icon = Icons.Filled.CalendarMonth,
                iconBgColor = Color(0xFFF97316),
                title = stringResource(R.string.campus_card_exams),
                subtitle = stringResource(R.string.campus_card_exams_desc),
                onClick = { onNavigate(Destination.Exams) },
                modifier = Modifier.weight(1f)
            )
            ServiceCard(
                icon = Icons.Filled.Map,
                iconBgColor = Color(0xFFF43F5E),
                title = stringResource(R.string.campus_card_map),
                subtitle = stringResource(R.string.campus_card_map_desc),
                onClick = { WeChatMiniProgramLauncher.launchMap(context) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 单张主功能卡片（水平排版）。
 * 左侧：40.dp 圆角图标背景盒 → 右侧：标题 + 副标题。
 */
@Composable
private fun ServiceCard(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(84.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 圆角图标背景
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
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// endregion

// region 次功能网格（3列垂直卡片）

/**
 * 次功能区网格（3 列横向排列）。
 *
 * 垂直排版 Card：36.dp 圆角图标 + 下方单行文字。
 */
@Composable
private fun SecondaryServiceGrid() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SmallServiceCard(
            icon = Icons.Filled.LocalLibrary,
            iconBgColor = Color(0xFFD97706),
            title = stringResource(R.string.campus_service_library),
            modifier = Modifier.weight(1f)
        )
        SmallServiceCard(
            icon = Icons.Filled.DirectionsBus,
            iconBgColor = Color(0xFF3B82F6),
            title = stringResource(R.string.campus_service_transport),
            modifier = Modifier.weight(1f)
        )
        SmallServiceCard(
            icon = Icons.Filled.Campaign,
            iconBgColor = Color(0xFF8B5CF6),
            title = stringResource(R.string.campus_service_channel),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * 单张次功能卡片（垂直排版）。
 * 上方：36.dp 圆角图标 → 下方：标题文字。
 */
@Composable
private fun SmallServiceCard(
    icon: ImageVector,
    iconBgColor: Color,
    title: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                color = TextPrimary,
                maxLines = 1
            )
        }
    }
}

// endregion
