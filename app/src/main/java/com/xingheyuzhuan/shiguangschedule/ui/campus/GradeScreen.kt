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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Grading
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeEntity
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

// ═══════════════════════════════════════════════════════════════════════════
// 配色：浅色模式沿用原有视觉，深色模式切换到主题语义色
//
// 本页早期是纯浅色硬编码，深色模式下会白底黑字。适配时**保留浅色模式原貌**
// （需求：浅色下恢复之前的样子），仅在深色模式下改用 MaterialTheme 色。
// 做法与 CampusScreen 的 isDark 分支一致，避免同一 App 内两套风格。
// ═══════════════════════════════════════════════════════════════════════════

/** 页面底色：浅色下为原暖白，深色下为主题 surface */
@Composable
private fun gradePageBackground(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFCF9F8)

/** 卡片底色：浅色下为原纯白，深色下为浅黑容器色（surface 在深色下近纯黑，卡片无法区分） */
@Composable
private fun gradeCardBackground(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White

/** 主文字：浅色下为原深灰 */
@Composable
private fun gradePrimaryText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF333333)

/** 次要文字：浅色下为原中灰 */
@Composable
private fun gradeSecondaryText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF757575)

/** 更浅的提示文字：浅色下为原浅灰 */
@Composable
private fun gradeHintText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.outline else Color(0xFF9E9E9E)

/**
 * 成绩查询主页面。
 *
 * 布局结构（LazyColumn）：
 * 1. GPA 统计卡片（渐变背景 ElevatedCard）
 * 2. 学期过滤器（ScrollableTabRow）
 * 3. 成绩列表（每门课程一张 Card）
 * 4. 空数据时展示友好空状态提示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeScreen(
    onBack: () -> Unit,
    viewModel: GradeViewModel = hiltViewModel()
) {
    val allGrades by viewModel.allGrades.collectAsStateWithLifecycle()
    val availableTerms by viewModel.availableTerms.collectAsStateWithLifecycle()
    val selectedTerm by viewModel.selectedTerm.collectAsStateWithLifecycle()
    val displayedGrades by viewModel.displayedGrades.collectAsStateWithLifecycle()
    val totalGpa by viewModel.totalGpa.collectAsStateWithLifecycle()
    val termGpa by viewModel.termGpa.collectAsStateWithLifecycle()
    val isDark = LocalIsDarkTheme.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.grade_title),
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = gradePageBackground(isDark)
                )
            )
        },
        containerColor = gradePageBackground(isDark)
    ) { innerPadding ->
        if (allGrades.isEmpty()) {
            EmptyGradeState(isDark = isDark, modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. GPA 统计卡片
                item { GpaStatsCard(totalGpa = totalGpa, termGpa = termGpa) }

                // 2. 学期过滤器
                item {
                    TermFilterRow(
                        terms = availableTerms,
                        selectedTerm = selectedTerm,
                        onTermSelected = { viewModel.selectTerm(it) }
                    )
                }

                // 3. 成绩列表
                items(
                    items = displayedGrades,
                    key = { it.id }
                ) { grade ->
                    GradeCard(grade = grade, isDark = isDark)
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 空状态
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 数据库中无成绩数据时展示的友好空状态。
 */
@Composable
private fun EmptyGradeState(isDark: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.Grading,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isDark) MaterialTheme.colorScheme.outline else Color(0xFFBDBDBD)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.grade_no_data),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = gradeSecondaryText(isDark)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.grade_no_data_hint),
            fontSize = 14.sp,
            color = gradeHintText(isDark)
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// GPA 统计卡片
// ──────────────────────────────────────────────────────────────────────────────

/**
 * GPA 概览卡片：左侧“总平均绩点”、右侧“本学期绩点”。
 *
 * 使用主题色渐变背景，左右分区布局。
 */
@Composable
private fun GpaStatsCard(totalGpa: Float, termGpa: Float) {
    val gradient = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        )
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient, RoundedCornerShape(20.dp))
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：总平均绩点
                GpaBlock(
                    label = stringResource(R.string.grade_total_gpa_label),
                    gpa = totalGpa
                )

                // 分隔线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(56.dp)
                        .background(Color.White.copy(alpha = 0.5f))
                )

                // 右侧：本学期绩点
                GpaBlock(
                    label = stringResource(R.string.grade_term_gpa_label),
                    gpa = termGpa
                )
            }
        }
    }
}

/**
 * GPA 数值 + 标签块（用于卡片内部左右分区）。
 */
@Composable
private fun GpaBlock(label: String, gpa: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = String.format("%.2f", gpa),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 学期过滤器
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 横向可滚动的学期 Tab 行。
 *
 * 使用 ScrollableTabRow 实现，当前选中 Tab 高亮显示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TermFilterRow(
    terms: List<String>,
    selectedTerm: String,
    onTermSelected: (String) -> Unit
) {
    if (terms.isEmpty()) return

    val selectedIndex = terms.indexOf(selectedTerm).coerceAtLeast(0)

    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth(),
        edgePadding = 0.dp,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = {}
    ) {
        terms.forEachIndexed { index, term ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onTermSelected(term) },
                text = {
                    Text(
                        text = term,
                        fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// 单门课程成绩卡片
// ──────────────────────────────────────────────────────────────────────────────

/**
 * 单门课程的成绩卡片。
 *
 * 布局：
 * - 左侧：课程名称（主标题）、学分 + 绩点（副标题）
 * - 右侧：成绩（醒目大字）
 */
@Composable
private fun GradeCard(grade: GradeEntity, isDark: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = gradeCardBackground(isDark)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：文字信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = grade.kcmc.ifBlank { "—" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = gradePrimaryText(isDark),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "${stringResource(R.string.grade_xf_label)}: ${grade.xf.ifBlank { "—" }}",
                        fontSize = 13.sp,
                        color = gradeSecondaryText(isDark)
                    )
                    Text(
                        text = "${stringResource(R.string.grade_jd_label)}: ${grade.jd.ifBlank { "—" }}",
                        fontSize = 13.sp,
                        color = gradeSecondaryText(isDark)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 右侧：成绩醒目展示
            // 浅色下沿用原绿色（0xFF10B981），深色下换主题第三色容器，避免刺眼
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isDark) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            Color(0xFF10B981).copy(alpha = 0.1f)
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = grade.cj.ifBlank { "—" },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        Color(0xFF10B981)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
