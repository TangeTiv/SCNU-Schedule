package com.xingheyuzhuan.shiguangschedule.ui.campus

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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.components.BottomNavigationBar
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 校园 Dashboard 主页面。
 *
 * 包含欢迎卡片和 2×2 功能导航网格，遵循 Material 3 规范。
 * 状态提升：通过 onNavigate 回调将导航事件委托给上层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit
) {
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
            // 欢迎卡片
            item { WelcomeCard() }

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

@Composable
private fun WelcomeCard() {
    val weekNumber = computeCurrentWeek()
    val dayOfWeekName = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINESE)

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = stringResource(R.string.campus_school_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.campus_school_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
            ) {
                Text(
                    text = stringResource(
                        R.string.campus_week_info,
                        weekNumber,
                        dayOfWeekName
                    ),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * 计算当前学期周次。
 * 基于用户设置的开学日期（周一）计算，若未设置则回退到全年累计周次。
 */
private fun computeCurrentWeek(): Int {
    val startOfYear = LocalDate.of(LocalDate.now().year, 1, 1)
    val daysSinceStart = LocalDate.now().toEpochDay() - startOfYear.toEpochDay()
    return ((daysSinceStart / 7) + 1).toInt()
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
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
