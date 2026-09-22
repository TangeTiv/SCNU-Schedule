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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.xingheyuzhuan.shiguangschedule.ui.account.rememberBiometricUnlocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * ## 已满处理
 *
 * 用详情接口的 `jxbrl`（容量）与 `yxzrs`（已选人数）精确比较
 * （[SelectableCourse.isFull] 在 ViewModel 映射时已算好容量信息）：
 * - 人数行用错误色显示"人数 78/80"
 * - 按钮文案直接由「选课」变为「**已满**」并禁用
 *
 * **不在标题旁另加「已满」角标** —— 否则同一张卡上会同时出现
 * 「已满」角标与灰色的「选课」按钮，两个元素在表达同一件事，显得冗余。
 * 用按钮文案本身承载"已满"状态，一个元素说清一件事。
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
                Text(
                    text = clazz.className.ifBlank { clazz.courseName.ifBlank { "—" } },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 已选数量 / 课程容量（详情接口的 jxbrl 与 yxzrs）
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
                // 已满禁止选课；提交中亦禁用，避免连点重复提交
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
                        // 已满时按钮文案直接变为「已满」——用按钮本身承载状态，
                        // 不再另加角标，避免同一张卡上两个元素表达同一件事。
                        // 优先于「选子课程」：已满时无论如何都提交不了。
                        isFull -> stringResource(R.string.campus_course_selection_full_badge)
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
 * ## v1.7.0：优先用已保存凭据，用户多数情况下看不到这个框
 *
 * 打开时先调 `prepareCampusLogin()`，按结果分流：
 *
 * | 状态 | 表现 |
 * |---|---|
 * | 会话可直接恢复 | **立刻关闭并导航**，对话框一闪而过甚至看不见 |
 * | 需要生物识别 | 自动弹一次指纹；取消则落到"改用密码登录" |
 * | 无凭据 / 凭据过期 | 引导去【我的 → 账号】，同时保留手输兜底 |
 * | 冷却中 | 只显示倒计时，**不给重试按钮**（避免把 SSO 账号锁死） |
 *
 * @param viewModel 与选课页共用同一个 ViewModel 实例，因此登录态、类别、
 *                  课程缓存会在导航后无缝延续，无需二次登录或重复拉取
 * @param onSuccess 会话就绪后的回调（关闭对话框并导航进选课页）
 * @param onGoToAccount 去【我的 → 账号】设置凭据
 */
@Composable
internal fun CourseSelectionLoginDialog(
    viewModel: CourseSelectionViewModel,
    onSuccess: () -> Unit,
    onGoToAccount: () -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val campusState by viewModel.campusLoginState.collectAsStateWithLifecycle()
    val maskedAccount by viewModel.maskedAccount.collectAsStateWithLifecycle()

    val biometricUnlocker = rememberBiometricUnlocker()
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var inlineError by remember { mutableStateOf<String?>(null) }

    /** 用户放弃生物识别、改走手输密码 */
    var manualInput by remember { mutableStateOf(false) }

    /** 每次进入只自动弹一次指纹，取消后不再骚扰 */
    var autoUnlockAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.prepareCampusLogin()
    }

    LaunchedEffect(campusState) {
        when (val state = campusState) {
            is CampusLoginState.Ready -> onSuccess()

            is CampusLoginState.NeedsUnlock -> {
                if (autoUnlockAttempted) return@LaunchedEffect
                autoUnlockAttempted = true
                val cipher = viewModel.createUnlockCipher()
                if (cipher == null) {
                    // 密钥已作废（用户换了指纹）→ 退回手输
                    manualInput = true
                    viewModel.prepareCampusLogin()
                } else {
                    biometricUnlocker.authenticate(
                        cipher = cipher,
                        onSucceeded = { authenticated -> viewModel.unlockCampusLogin(authenticated) },
                        onFailed = { manualInput = true }
                    )
                }
            }

            // 冷却结束后自动复查一次。
            // 对话框里的倒计时是**快照值**（不刷新），若不自查，
            // 用户等完 1 分钟也只能关掉对话框重开 —— 死路。
            is CampusLoginState.Locked -> {
                delay(state.remainingSeconds * 1_000L + 500L)
                viewModel.prepareCampusLogin()
            }

            else -> Unit
        }
    }

    // 有学号可复用时不必让用户再输一遍学号
    val useSavedAccount = !maskedAccount.isNullOrBlank()
    val showAccountField = !useSavedAccount

    val doManualLogin: () -> Unit = {
        inlineError = null
        if (useSavedAccount) {
            viewModel.loginWithSavedAccount(
                password = password,
                onSuccess = onSuccess,
                onError = { inlineError = it }
            )
        } else {
            viewModel.login(
                account = account.trim(),
                password = password,
                onSuccess = onSuccess,
                onError = { inlineError = it }
            )
        }
    }

    val dismissDialog: () -> Unit = {
        viewModel.resetCampusLoginState()
        onDismiss()
    }

    AlertDialog(
        // 登录期间禁止点外部关闭，避免请求已在途却丢失后续导航
        onDismissRequest = { if (!uiState.isLoggingIn) dismissDialog() },
        title = {
            Text(
                text = stringResource(R.string.campus_course_selection),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                when (val state = campusState) {
                    is CampusLoginState.Checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = stringResource(R.string.campus_course_selection_checking_session),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is CampusLoginState.Locked -> {
                        Text(
                            text = stringResource(
                                R.string.campus_course_selection_login_locked,
                                state.remainingSeconds
                            ),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.campus_course_selection_login_locked_desc),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is CampusLoginState.NeedsUnlock -> {
                        if (!manualInput) {
                            Text(
                                text = stringResource(R.string.campus_course_selection_unlock_desc),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            ManualCredentialFields(
                                showAccountField = showAccountField,
                                maskedAccount = maskedAccount,
                                account = account,
                                onAccountChange = { account = it },
                                password = password,
                                onPasswordChange = { password = it },
                                passwordVisible = passwordVisible,
                                onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                                enabled = !uiState.isLoggingIn
                            )
                        }
                    }

                    is CampusLoginState.NeedsCredential,
                    is CampusLoginState.Failed -> {
                        Text(
                            text = when (state) {
                                is CampusLoginState.Failed -> state.message
                                else -> stringResource(R.string.campus_course_selection_need_credential)
                            },
                            fontSize = 13.sp,
                            color = if (state is CampusLoginState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        ManualCredentialFields(
                            showAccountField = showAccountField,
                            maskedAccount = maskedAccount,
                            account = account,
                            onAccountChange = { account = it },
                            password = password,
                            onPasswordChange = { password = it },
                            passwordVisible = passwordVisible,
                            onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                            enabled = !uiState.isLoggingIn
                        )
                    }

                    is CampusLoginState.Ready -> Unit // 正在关闭
                }

                inlineError?.let { message ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                if (campusState !is CampusLoginState.Locked) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.campus_course_selection_password_notice),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when (campusState) {
                is CampusLoginState.Checking,
                is CampusLoginState.Locked,
                is CampusLoginState.Ready -> Unit

                is CampusLoginState.NeedsUnlock -> {
                    if (manualInput) {
                        LoginButton(
                            isLoggingIn = uiState.isLoggingIn,
                            enabled = password.isNotBlank(),
                            onClick = doManualLogin
                        )
                    } else {
                        // 生物识别弹窗正在前台；这里提供主动重试（用户误触取消后可再来一次）
                        TextButton(
                            onClick = {
                                scope.launch {
                                    val cipher = viewModel.createUnlockCipher()
                                    if (cipher == null) {
                                        manualInput = true
                                    } else {
                                        biometricUnlocker.authenticate(
                                            cipher = cipher,
                                            onSucceeded = { authenticated ->
                                                viewModel.unlockCampusLogin(authenticated)
                                            },
                                            onFailed = { manualInput = true }
                                        )
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.campus_course_selection_unlock_action))
                        }
                    }
                }

                is CampusLoginState.NeedsCredential,
                is CampusLoginState.Failed -> {
                    LoginButton(
                        isLoggingIn = uiState.isLoggingIn,
                        enabled = password.isNotBlank() && (useSavedAccount || account.isNotBlank()),
                        onClick = doManualLogin
                    )
                }
            }
        },
        dismissButton = {
            if (campusState is CampusLoginState.NeedsCredential ||
                campusState is CampusLoginState.Failed
            ) {
                TextButton(onClick = onGoToAccount, enabled = !uiState.isLoggingIn) {
                    Text(stringResource(R.string.campus_course_selection_go_account))
                }
            } else {
                TextButton(onClick = dismissDialog, enabled = !uiState.isLoggingIn) {
                    Text(stringResource(R.string.campus_course_selection_cancel))
                }
            }
        }
    )
}

/** 手输凭据区：有已保存学号时只显示密码框（学号以脱敏形式说明）。 */
@Composable
private fun ManualCredentialFields(
    showAccountField: Boolean,
    maskedAccount: String?,
    account: String,
    onAccountChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePasswordVisible: () -> Unit,
    enabled: Boolean
) {
    if (!showAccountField && maskedAccount != null) {
        Text(
            text = stringResource(R.string.campus_course_selection_login_for_account, maskedAccount),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
    AccountPasswordFields(
        account = account,
        onAccountChange = onAccountChange,
        password = password,
        onPasswordChange = onPasswordChange,
        passwordVisible = passwordVisible,
        onTogglePasswordVisible = onTogglePasswordVisible,
        enabled = enabled,
        showAccountField = showAccountField
    )
}

/** 登录按钮（带在途 loading）。 */
@Composable
private fun LoginButton(
    isLoggingIn: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = !isLoggingIn && enabled) {
        if (isLoggingIn) {
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
}
