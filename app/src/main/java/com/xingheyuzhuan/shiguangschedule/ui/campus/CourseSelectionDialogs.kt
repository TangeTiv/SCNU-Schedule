package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.network.selection.CourseClass
import com.xingheyuzhuan.shiguangschedule.data.network.selection.EnrolledCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SelectableCourse
import com.xingheyuzhuan.shiguangschedule.data.network.selection.SubCourse

// ═══════════════════════════════════════════════════════════════════════════
// 教学班选择 / 子课程勾选
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 教学班选择底部面板。
 *
 * ## 两条提交路径
 *
 * - **普通教学班**（`jxbzls <= 1`）：直接点「选课」，
 *   以 [CourseClass.doJxbId] 作为 `jxb_ids` 提交
 * - **多子课程教学班**（`jxbzls > 1`）：点「选课」不直接提交，而是先加载子课程
 *   让用户勾选，再把多个 `do_jxb_id` 逗号拼接提交。
 *   **这一步不可省略** —— 只提交单个 ID 会导致选课失败或只选上部分子课程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClassSelectionSheet(
    course: SelectableCourse,
    classes: List<CourseClass>,
    viewModel: CourseSelectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 当前正在查看子课程的教学班；非 null 时面板切换到子课程勾选视图
    var subCourseTarget by remember { mutableStateOf<CourseClass?>(null) }
    var subCourses by remember { mutableStateOf<List<SubCourse>>(emptyList()) }
    var isLoadingSub by remember { mutableStateOf(false) }
    // 勾选的子课程 do_jxb_id
    val pickedSubIds = remember { mutableStateMapOf<String, Boolean>() }

    // 加载子课程（仅在需要时触发一次）
    LaunchedEffect(subCourseTarget) {
        val target = subCourseTarget ?: return@LaunchedEffect
        isLoadingSub = true
        viewModel.loadSubCourses(
            course = course,
            onLoaded = { list ->
                subCourses = list
                // 默认全选：多数场景下整组子课程都要选
                list.forEach { pickedSubIds[it.doJxbId] = true }
                isLoadingSub = false
            },
            onError = { isLoadingSub = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            val target = subCourseTarget
            if (target == null) {
                // ── 视图 1：选择教学班 ──
                Text(
                    text = course.courseName.ifBlank { "—" },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.campus_course_selection_pick_class),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(classes, key = { it.doJxbId.ifBlank { it.classId } }) { clazz ->
                        ClassRow(
                            clazz = clazz,
                            isSubmitting = viewModel.isSubmitting(
                                course.classId.ifBlank { course.courseId }
                            ),
                            onSelect = {
                                // 已满或不在选课时间的教学班不可提交
                                if (clazz.hasSubCoursesInternal) {
                                    subCourseTarget = clazz
                                } else {
                                    viewModel.submitSelection(
                                        course = course,
                                        doJxbId = clazz.doJxbId,
                                        pickedSubCourses = emptyList()
                                    )
                                    onDismiss()
                                }
                            }
                        )
                    }
                }
            } else {
                // ── 视图 2：勾选子课程 ──
                Text(
                    text = stringResource(R.string.campus_course_selection_pick_sub_course),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = target.className.ifBlank { course.courseName },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.campus_course_selection_sub_course_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                if (isLoadingSub) {
                    Text(
                        text = stringResource(R.string.campus_course_selection_loading),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(subCourses, key = { it.doJxbId }) { sub ->
                            SubCourseRow(
                                sub = sub,
                                checked = pickedSubIds[sub.doJxbId] == true,
                                onToggle = {
                                    pickedSubIds[sub.doJxbId] = !(pickedSubIds[sub.doJxbId] ?: false)
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val picked = subCourses.filter { pickedSubIds[it.doJxbId] == true }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TextButton(
                            onClick = { subCourseTarget = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.campus_course_selection_back_to_classes))
                        }
                        Button(
                            onClick = {
                                viewModel.submitSelection(
                                    course = course,
                                    doJxbId = target.doJxbId,
                                    pickedSubCourses = picked
                                )
                                onDismiss()
                            },
                            enabled = picked.isNotEmpty() &&
                                    !viewModel.isSubmitting(course.classId.ifBlank { course.courseId }),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = stringResource(R.string.campus_course_selection_select_action),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个教学班行。
 *
 * `jxbzls > 1`（[CourseClass.subCourseCount]）时按钮文案变为「选子课程」，
 * 明确告知用户还需一步，而不是让他点了"选课"却发现没提交。
 */
@Composable
private fun ClassRow(
    clazz: CourseClass,
    isSubmitting: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = clazz.className.ifBlank { clazz.courseName.ifBlank { "—" } },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (clazz.teacherName.isNotBlank()) {
                    InfoLine(stringResource(R.string.campus_course_selection_teacher, clazz.teacherName))
                }
                if (clazz.classTime.isNotBlank()) {
                    InfoLine(stringResource(R.string.campus_course_selection_time, clazz.classTime))
                }
                if (clazz.classLocation.isNotBlank()) {
                    InfoLine(stringResource(R.string.campus_course_selection_location, clazz.classLocation))
                }
                if (clazz.occupancyText.isNotBlank()) {
                    InfoLine(
                        stringResource(
                            R.string.campus_course_selection_capacity,
                            clazz.occupancyText
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onSelect,
                    enabled = !isSubmitting && !clazz.isFull,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp,
                        vertical = 6.dp
                    )
                ) {
                    Text(
                        text = if (clazz.isFull) {
                            stringResource(R.string.campus_course_selection_class_full)
                        } else if (clazz.hasSubCoursesInternal) {
                            stringResource(R.string.campus_course_selection_pick_sub_course_action)
                        } else {
                            stringResource(R.string.campus_course_selection_select_action)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 子课程勾选行 */
@Composable
private fun SubCourseRow(sub: SubCourse, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (checked) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sub.subCourseName.ifBlank { sub.className.ifBlank { "—" } },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (sub.classTime.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = sub.classTime,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (sub.occupancyText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(
                            R.string.campus_course_selection_capacity,
                            sub.occupancyText
                        ),
                        fontSize = 11.sp,
                        color = if (sub.isFull) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/** `jxbzls > 1` 判定，供 UI 决定是否需要走子课程流程 */
private val CourseClass.hasSubCoursesInternal: Boolean
    get() = (subCourseCount.toIntOrNull() ?: 1) > 1

// ═══════════════════════════════════════════════════════════════════════════
// 退选二次确认
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 退选二次确认对话框。
 *
 * **必须展示完整教学班标识信息**（课程名 + 教学班名 + 教师 + 上课时间）：
 * 教务里同名课程可能有多个教学班（不同教师/时间），只显示课程名等于让用户盲选。
 *
 * 确认按钮在提交期间保持禁用，防止连点造成重复提交。
 */
@Composable
internal fun DropConfirmDialog(
    course: EnrolledCourse,
    isDropping: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDropping) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.campus_course_selection_drop_confirm_title),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                // 完整标识信息，避免同名课程误退
                ConfirmLine(
                    label = stringResource(R.string.campus_course_selection_confirm_course),
                    value = course.courseName.ifBlank { "—" }
                )
                if (course.className.isNotBlank()) {
                    ConfirmLine(
                        label = stringResource(R.string.campus_course_selection_confirm_class),
                        value = course.className
                    )
                }
                if (course.teacherName.isNotBlank()) {
                    ConfirmLine(
                        label = stringResource(R.string.campus_course_selection_confirm_teacher),
                        value = course.teacherName
                    )
                }
                if (course.classTime.isNotBlank()) {
                    ConfirmLine(
                        label = stringResource(R.string.campus_course_selection_confirm_time),
                        value = course.classTime
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.campus_course_selection_drop_warning),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDropping,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(R.string.campus_course_selection_drop_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDropping) {
                Text(stringResource(R.string.campus_course_selection_cancel))
            }
        }
    )
}

@Composable
private fun ConfirmLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}
