package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 同步选项数据类。
 *
 * 承载用户对于「课程 / 成绩 / 考试」三项同步内容的选择状态，
 * 并提供 [hasSelection] 与 [allSelected] 两个派生属性，
 * 供上层（MainActivity）在触发同步前读取使用。
 */
data class SyncOptions(
    val courses: Boolean = false,
    val grades: Boolean = false,
    val exams: Boolean = false
) {
    /** 是否至少选中一项 */
    val hasSelection: Boolean get() = courses || grades || exams

    /** 是否三项全选中 */
    val allSelected: Boolean get() = courses && grades && exams
}

/**
 * 同步内容选择页面（二级页面，无底部导航栏）。
 *
 * 遵循状态提升（State Hoisting）模式：本组件不直接执行同步操作，
 * 而是通过 [onSyncStart] 回调将用户选择向上传递给调用方。
 * 调用方负责执行 DataStore 持久化与华师 WebView 直达跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSelectionScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    onSyncStart: (SyncOptions) -> Unit = {}
) {
    var options by remember { mutableStateOf(SyncOptions()) }

    val toggleOption: (String) -> Unit = { key ->
        options = when (key) {
            "courses" -> options.copy(courses = !options.courses)
            "grades" -> options.copy(grades = !options.grades)
            "exams" -> options.copy(exams = !options.exams)
            else -> options
        }
    }

    val toggleAll = {
        options = SyncOptions(
            courses = !options.allSelected,
            grades = !options.allSelected,
            exams = !options.allSelected
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.campus_sync_title),
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
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // 内容区域（占据剩余空间）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                // 说明文字
                Text(
                    text = stringResource(R.string.campus_sync_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 一键全选控制栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.campus_sync_options),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.clickable { toggleAll() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (options.allSelected)
                                Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                            contentDescription = if (options.allSelected)
                                stringResource(R.string.campus_sync_deselect_all)
                            else
                                stringResource(R.string.campus_sync_select_all),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (options.allSelected)
                                stringResource(R.string.campus_sync_deselect_all)
                            else
                                stringResource(R.string.campus_sync_select_all),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 选项 1: 学期课程表
                SyncOptionCard(
                    titleRes = R.string.campus_sync_courses,
                    descRes = R.string.campus_sync_courses_desc,
                    selected = options.courses,
                    onClick = { toggleOption("courses") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 选项 2: 历年成绩单
                SyncOptionCard(
                    titleRes = R.string.campus_sync_grades,
                    descRes = R.string.campus_sync_grades_desc,
                    selected = options.grades,
                    onClick = { toggleOption("grades") }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 选项 3: 期中期末考试
                SyncOptionCard(
                    titleRes = R.string.campus_sync_exams,
                    descRes = R.string.campus_sync_exams_desc,
                    selected = options.exams,
                    onClick = { toggleOption("exams") }
                )
            }

            // 底部按钮区域
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Button(
                    onClick = { onSyncStart(options) },
                    enabled = options.hasSelection,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.campus_sync_start),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// region 同步选项卡片

/**
 * 单个同步选项卡片。
 * 选中时显示蓝色边框与浅蓝底色，未选中为白底灰边框 —— 对齐 React 原型的视觉语义。
 */
@Composable
private fun SyncOptionCard(
    titleRes: Int,
    descRes: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.outlineVariant

    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    else
        MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(2.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(descRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                imageVector = if (selected)
                    Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected)
                    stringResource(R.string.a11y_state_selected)
                else
                    stringResource(R.string.a11y_state_not_selected),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// endregion

@Preview(showBackground = true)
@Composable
private fun SyncSelectionScreenPreview() {
    MaterialTheme {
        SyncSelectionScreen(
            onNavigate = {},
            onBack = {}
        )
    }
}
