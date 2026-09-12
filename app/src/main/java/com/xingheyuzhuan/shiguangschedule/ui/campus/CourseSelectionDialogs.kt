package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.R
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
 *   以 `do_jxb_id` 作为 `jxb_ids` 提交
 * - **多子课程教学班**（`jxbzls > 1`）：点「选课」不直接提交，而是先加载子课程
 *   让用户勾选，再把多个 `do_jxb_id` 逗号拼接提交。
 *   **这一步不可省略** —— 只提交单个 ID 会导致选课失败或只选上部分子课程。
 *
 * ## 数据来源
 *
 * 列表接口已带上 `jxb_id`，但选课真正需要的是**详情接口**的 `do_jxb_id`。
 * 因此即使用户点的是单个教学班，也会先拉一次详情把 `do_jxb_id` 补齐
 * （对应脚本 `select()` 在缺少 `do_jxb_id` 时自动调 `classes_of()` 的行为）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClassSelectionSheet(
    group: CourseGroup,
    viewModel: CourseSelectionViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 教学班详情（必须每次都请求）──
    //
    // **这是选课能否成功的关键。** 列表接口给的是 `jxb_id`，而选课提交需要的是
    // 详情接口的 `do_jxb_id`，两者**不是同一个值**（脚本在字段旁标注"选课必需"）；
    // 容量 `jxbrl` 也只有详情接口才有。
    //
    // 早期实现写过一个"优化"：`if (classes.any { it.classId.isNotBlank() }) return`，
    // 想复用列表数据。但列表项的 `jxb_id` 本来就非空，于是详情请求被**永久跳过**，
    // 导致两个症状同时出现：
    //   1. 提交了列表的 `jxb_id` → 教务参数校验失败 → flag=0
    //   2. 拿不到 `jxbrl` → 不显示容量、无法判定已满
    // 现在改为无条件请求，绝不复用列表数据当作详情。
    var detailGroups by remember(group.key) { mutableStateOf<List<CourseGroup>>(emptyList()) }
    var isLoadingClasses by remember(group.key) { mutableStateOf(true) }
    var hasDetailError by remember(group.key) { mutableStateOf(false) }

    // 当前正在查看子课程的教学班；非 null 时面板切换到子课程勾选视图
    var subCourseTarget by remember { mutableStateOf<SelectableCourse?>(null) }
    var subCourses by remember { mutableStateOf<List<SubCourse>>(emptyList()) }
    var isLoadingSub by remember { mutableStateOf(false) }
    // 勾选的子课程 do_jxb_id
    val pickedSubIds = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(group.key) {
        isLoadingClasses = true
        hasDetailError = false
        viewModel.loadClasses(
            group = group,
            onLoaded = { groups ->
                // 详情接口同样按 kch_id 分组；取与当前课程对应的那组
                detailGroups = groups
                isLoadingClasses = false
            },
            onError = {
                isLoadingClasses = false
                hasDetailError = true
            }
        )
    }

    // 只用详情数据；详情未到达时保持为空 → UI 显示加载中，不会误用列表数据提交
    val classes: List<SelectableCourse> = remember(detailGroups, group.key) {
        (detailGroups.firstOrNull { it.key == group.key } ?: detailGroups.firstOrNull())
            ?.classes
            .orEmpty()
    }

    // 加载子课程
    LaunchedEffect(subCourseTarget) {
        val target = subCourseTarget ?: return@LaunchedEffect
        isLoadingSub = true
        viewModel.loadSubCourses(
            course = target,
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
        sheetState = sheetState,
        // 弹层容器用 surface：与页面表面形成明确边界，便于分辨窗口范围
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            val target = subCourseTarget
            when {
                target != null -> {
                    // ── 视图 2：勾选子课程 ──
                    Text(
                        text = stringResource(R.string.campus_course_selection_pick_sub_course),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = target.className.ifBlank { group.course.courseName },
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
                        LoadingHint()
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
                                        pickedSubIds[sub.doJxbId] =
                                            !(pickedSubIds[sub.doJxbId] ?: false)
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
                                        course = target,
                                        doJxbId = target.classId,
                                        pickedSubCourses = picked
                                    )
                                    onDismiss()
                                },
                                enabled = picked.isNotEmpty() && !viewModel.isSubmitting(target.classId),
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

                isLoadingClasses -> {
                    Text(
                        text = group.course.courseName.ifBlank { "—" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LoadingHint()
                }

                else -> {
                    // ── 视图 1：选择教学班 ──
                    Text(
                        text = group.course.courseName.ifBlank { "—" },
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

                    if (classes.isEmpty()) {
                        // 详情返回空数组是**正常**的：教务对「已选/未开放」的课程
                        // 就是这个行为（HTTP 200，不是错误），故给友好说明而非报错
                        Text(
                            text = if (hasDetailError) {
                                stringResource(R.string.campus_course_selection_classes_failed)
                            } else {
                                stringResource(R.string.campus_course_selection_no_classes)
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 460.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(classes, key = { it.classId.ifBlank { it.courseId + it.className } }) { clazz ->
                                ClassRow(
                                    clazz = clazz,
                                    isSubmitting = viewModel.isSubmitting(clazz.classId),
                                    onSelect = {
                                        if (clazz.hasSubCourses) {
                                            subCourseTarget = clazz
                                        } else {
                                            // clazz.classId 已是详情接口的 do_jxb_id
                                            // （由 CourseClass.toSelectableCourse 映射）
                                            viewModel.submitSelection(
                                                course = clazz,
                                                doJxbId = clazz.classId,
                                                pickedSubCourses = emptyList()
                                            )
                                            onDismiss()
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingHint() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.campus_course_selection_loading),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 单个教学班行。
 *
 * ## 已满处理（需求 8）
 *
 * 用详情接口的 `jxbrl`（容量）与 `yxzrs`（已选人数）精确比较
 * （[SelectableCourse.isFull] 在 ViewModel 映射时已算好容量信息）：
 * - 卡片整体 `alpha(0.6f)` 变暗，视觉上立刻可辨
 * - 右上角显示红色「已满」Badge
 * - 按钮文案改为「已满」并**禁用**，从根本上阻止提交
 *
 * 含子课程（`jxbzls > 1`）时按钮文案为「选子课程」，
 * 明确告知用户还需一步，而不是让他点了"选课"却发现没提交。
 */
@Composable
private fun ClassRow(
    clazz: SelectableCourse,
    isSubmitting: Boolean,
    onSelect: () -> Unit
) {
    val isFull = clazz.isFull
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        // 用描边而非投影区分卡片：无阴影需求下，边框是最可靠的层次手段
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = clazz.className.ifBlank { clazz.courseName.ifBlank { "—" } },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // 已满标注。全屏范围内只此一处（列表卡片已不再显示"已满"），
                    // 不会再出现"同一屏两个已满标识"
                    if (isFull) {
                        Spacer(modifier = Modifier.width(6.dp))
                        InfoBadge(
                            text = stringResource(R.string.campus_course_selection_full_badge),
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // 已选数量 / 课程容量（来自详情接口 jxbrs / jxbrl）
                if (clazz.occupancyText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoLine(
                        text = stringResource(
                            R.string.campus_course_selection_capacity,
                            clazz.occupancyText
                        ),
                        color = if (isFull) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onSelect,
                // 已满禁止选课（需求 8）；提交中亦禁用，避免连点重复提交
                enabled = !isSubmitting && !isFull,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (clazz.hasSubCourses) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (clazz.hasSubCourses) {
                        MaterialTheme.colorScheme.onTertiary
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                ),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = when {
                        // 已满时按钮保持"选课"文案但禁用：配合标题旁的「已满」Badge，
                        // 全屏只有一个"已满"字样，不会重复标注
                        clazz.hasSubCourses ->
                            stringResource(R.string.campus_course_selection_pick_sub_course_action)
                        else -> stringResource(R.string.campus_course_selection_select_action)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** 已满 Badge 复用课程卡的小角标视觉 */
@Composable
private fun InfoBadge(
    text: String,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1
        )
    }
}

@Composable
private fun InfoLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 子课程勾选行 */
@Composable
private fun SubCourseRow(sub: SubCourse, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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

// ═══════════════════════════════════════════════════════════════════════════
// 校园页登录对话框
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 【校园】页的选课登录对话框。
 *
 * 点【选课】卡片时**先弹此框**，而不是直接进页面 —— 登录成功后才导航。
 * 学号预填（与教务同步共用 `campus_account`），密码每次重新输入且不落盘。
 *
 * @param viewModel 与选课页共用同一个 ViewModel 实例，因此登录态、类别、
 *                  课程缓存会在导航后无缝延续，无需二次登录或重复拉取
 * @param onSuccess 登录成功后的回调（用于关闭对话框并导航进选课页）
 */
@Composable
internal fun CourseSelectionLoginDialog(
    viewModel: CourseSelectionViewModel,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val savedAccount by viewModel.savedAccount.collectAsStateWithLifecycle()

    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedAccount) {
        if (account.isEmpty() && savedAccount.isNotEmpty()) account = savedAccount
    }

    AlertDialog(
        // 登录期间禁止点外部关闭，避免请求已在途却丢失后续导航
        onDismissRequest = { if (!uiState.isLoggingIn) onDismiss() },
        title = {
            Text(
                text = stringResource(R.string.campus_course_selection),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.campus_course_selection_login_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                AccountPasswordFields(
                    account = account,
                    onAccountChange = { account = it },
                    password = password,
                    onPasswordChange = { password = it },
                    passwordVisible = passwordVisible,
                    onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                    enabled = !uiState.isLoggingIn
                )

                inlineError?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.campus_course_selection_password_notice),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    inlineError = null
                    // 走校园页专用入口：会先清掉"浏览中途失效"标记，
                    // 避免弹窗自己显示"会话过期，重新登录"
                    viewModel.loginFromCampusDialog(
                        account = account.trim(),
                        password = password,
                        onSuccess = onSuccess,
                        onError = { inlineError = it }
                    )
                },
                enabled = !uiState.isLoggingIn && account.isNotBlank() && password.isNotBlank()
            ) {
                if (uiState.isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.campus_course_selection_logging_in))
                } else {
                    Text(stringResource(R.string.campus_course_selection_login_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !uiState.isLoggingIn) {
                Text(stringResource(R.string.campus_course_selection_cancel))
            }
        }
    )
}
