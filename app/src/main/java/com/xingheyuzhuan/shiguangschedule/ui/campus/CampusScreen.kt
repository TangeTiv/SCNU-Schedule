package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.ScheduleGridStyle
import com.xingheyuzhuan.shiguangschedule.ui.components.BottomNavigationBar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 校园 Dashboard 主页面。
 *
 * 包含全宽欢迎卡片（居中排版 + 今日速览胶囊行）和 2×2 功能导航网格，
 * 遵循 Material 3 规范。状态提升：通过 onNavigate 回调将导航事件委托给上层。
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
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.campus_title_discover),
                        fontWeight = FontWeight.Bold
                    )
                }
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
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // 欢迎卡片（含今日速览胶囊行）
            item { WelcomeCard(campusState) }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 分区标题
            item {
                Text(
                    text = stringResource(R.string.campus_section_academic),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
                )
            }

            // 双列功能网格
            item { FeatureGrid(onNavigate = onNavigate) }
        }
    }
}

// region 欢迎卡片

/**
 * 全宽横向欢迎卡片。
 *
 * 信息层级（居中对称排版）：
 * 1. 顶层：周次 + 星期（小号、浅色）
 * 2. 视觉中心：学校名称（最大、加粗）
 * 3. 副标题：服务描述
 * 4. 底部（有课时）：分割线 + 今日速览胶囊行
 */
@Composable
private fun WelcomeCard(state: CampusUiState) {
    val weekNumber = state.weekNumber
    val dayOfWeekName = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶层：周次信息（次要、轻量）
            Text(
                text = stringResource(R.string.campus_week_info, weekNumber ?: 1, dayOfWeekName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 视觉中心：学校名称
            Text(
                text = stringResource(R.string.campus_school_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 副标题
            Text(
                text = stringResource(R.string.campus_school_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            // 今日速览（仅在有课程数据时展示）
            if (state.todayCourses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                TodayPreviewRow(courses = state.todayCourses)
            }
        }
    }
}

// endregion

// region 今日速览胶囊行

/**
 * 今日速览横向滚动行。
 *
 * 展示当日课程的精简胶囊列表，用户无需进入课表页面即可快速查看
 * 下节课的时间、名称与地点。
 */
@Composable
private fun TodayPreviewRow(courses: List<TodayCourseDisplay>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.campus_today_overview),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(
                items = courses,
                key = { "${it.courseName}_${it.startTime}" }
            ) { course ->
                TodayCapsule(course)
            }
        }
    }
}

/**
 * 单个胶囊组件。
 *
 * Pill-shape（CircleShape）半透明背景，展示时间、课程名、地点。
 * 颜色来源于课程原色，支持深色/浅色模式自动适配。
 */
@Composable
private fun TodayCapsule(course: TodayCourseDisplay) {
    val isDark = isSystemInDarkTheme()
    val colorMaps = ScheduleGridStyle.DEFAULT_COLOR_MAPS
    val dualColor = colorMaps[course.colorIndex % colorMaps.size]
    val bgColor = (if (isDark) dualColor.dark else dualColor.light).copy(alpha = 0.25f)
    val contentColor = if (isDark) dualColor.dark else dualColor.light

    Surface(
        shape = CircleShape,
        color = bgColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = course.startTime,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = course.courseName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = course.location,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

// endregion

// region 功能网格

private data class FeatureCardData(
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector,
    val iconBgColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun FeatureGrid(onNavigate: (Destination) -> Unit) {
    val cards = listOf(
        FeatureCardData(
            titleRes = R.string.campus_card_sync,
            descRes = R.string.campus_card_sync_desc,
            icon = Icons.Filled.Sync,
            iconBgColor = Color(0xFF6366F1),
            onClick = { onNavigate(Destination.SyncSelection) }
        ),
        FeatureCardData(
            titleRes = R.string.campus_card_grades,
            descRes = R.string.campus_card_grades_desc,
            icon = Icons.Filled.Grading,
            iconBgColor = Color(0xFF10B981),
            onClick = { /* TODO: 成绩查询 */ }
        ),
        FeatureCardData(
            titleRes = R.string.campus_card_exams,
            descRes = R.string.campus_card_exams_desc,
            icon = Icons.Filled.CalendarMonth,
            iconBgColor = Color(0xFFF97316),
            onClick = { /* TODO: 考试安排 */ }
        ),
        FeatureCardData(
            titleRes = R.string.campus_card_map,
            descRes = R.string.campus_card_map_desc,
            icon = Icons.Filled.Map,
            iconBgColor = Color(0xFFF43F5E),
            onClick = { /* TODO: 校园地图 */ }
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (row in cards.chunked(2)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                for (card in row) {
                    FeatureCard(
                        data = card,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    data: FeatureCardData,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = data.onClick,
        modifier = modifier.aspectRatio(1f),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = data.iconBgColor.copy(alpha = 0.1f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = data.icon,
                        contentDescription = stringResource(data.titleRes),
                        tint = data.iconBgColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(data.titleRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(data.descRes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

// endregion
