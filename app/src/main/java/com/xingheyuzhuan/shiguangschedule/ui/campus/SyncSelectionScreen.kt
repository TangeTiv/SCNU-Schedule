package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.auth.SessionResult
import com.xingheyuzhuan.shiguangschedule.ui.account.rememberBiometricUnlocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 同步选项数据类。
 *
 * 承载用户对于「课程 / 成绩 / 考试 / 学业情况」四项同步内容的选择状态，
 * 并提供 [hasSelection] 与 [allSelected] 两个派生属性，
 * 供上层（MainActivity）在触发同步前读取使用。
 */
data class SyncOptions(
    val courses: Boolean = false,
    val grades: Boolean = false,
    val exams: Boolean = false,
    /**
     * 学业情况（培养计划 + 学分完成度 + 非正式学时）。
     *
     * 数据来自两个独立教务模块（N105515 培养计划 / N305012 第二类课），
     * 但面向用户是同一个功能，故合并为一个选项。
     */
    val academic: Boolean = false
) {
    /** 是否至少选中一项 */
    val hasSelection: Boolean get() = courses || grades || exams || academic

    /** 是否四项全选中 */
    val allSelected: Boolean get() = courses && grades && exams && academic
}

/**
 * 同步内容选择页面（二级页面，无底部导航栏）。
 *
 * ## v1.7.0：不再手输凭据
 *
 * 改造前本页有两个输入框让用户输学号密码，登录成功后由 ViewModel 落盘学号。
 * 现在凭据统一由【我的 → 账号】管理，本页只做三件事：
 *
 * 1. 进入时调用 `refreshAuthSession()` 检查会话
 * 2. 需要验证身份时**自动弹一次生物识别**（方案文档 §4.3：
 *    "进教务功能验一次，随后连续操作不再反复验证"）
 * 3. 无凭据 / 凭据过期 / 冷却中 → 引导去账号页，并说明原因
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSelectionScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: CampusSyncViewModel = hiltViewModel()
    val syncState by viewModel.syncUiState.collectAsStateWithLifecycle()
    val authSession by viewModel.authSession.collectAsStateWithLifecycle()
    val maskedAccount by viewModel.maskedAccount.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometricUnlocker = rememberBiometricUnlocker()

    var options by remember { mutableStateOf(SyncOptions()) }

    // 进入页面时检查一次会话状态
    LaunchedEffect(Unit) {
        viewModel.refreshAuthSession()
    }

    // 需要解锁时自动弹一次指纹。
    // `autoUnlockAttempted` 保证**每次进入页面只自动弹一次** —— 用户主动取消后
    // 不应该立刻又弹一遍，那会变成骚扰；取消后改成显示"验证身份"按钮。
    var autoUnlockAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(authSession) {
        if (autoUnlockAttempted || authSession !is SessionResult.NeedsUnlock) return@LaunchedEffect
        autoUnlockAttempted = true

        val cipher = viewModel.createUnlockCipher()
        if (cipher == null) {
            // 密钥已作废（用户换了指纹）→ 密码已被清掉，重新查一次状态
            viewModel.refreshAuthSession()
            return@LaunchedEffect
        }
        biometricUnlocker.authenticate(
            cipher = cipher,
            onSucceeded = { authenticated -> scope.launch { viewModel.completeUnlock(authenticated) } },
            onFailed = { /* 取消后由状态卡片提供"验证身份"按钮 */ }
        )
    }

    // 冷却结束后自动复查一次。
    // 卡片上的倒计时是**快照值**（不刷新），若不自查，用户等完 1 分钟回来
    // 会发现按钮还是灰的、卡片还写着"请 N 秒后重试" —— 死路。
    LaunchedEffect(authSession) {
        val locked = authSession as? SessionResult.Locked ?: return@LaunchedEffect
        delay(locked.remainingSeconds * 1_000L + 500L)
        viewModel.refreshAuthSession()
    }

    val toggleOption: (String) -> Unit = { key ->
        options = when (key) {
            "courses" -> options.copy(courses = !options.courses)
            "grades" -> options.copy(grades = !options.grades)
            "exams" -> options.copy(exams = !options.exams)
            "academic" -> options.copy(academic = !options.academic)
            else -> options
        }
    }

    val toggleAll = {
        options = SyncOptions(
            courses = !options.allSelected,
            grades = !options.allSelected,
            exams = !options.allSelected,
            academic = !options.allSelected
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
        Box(modifier = Modifier.fillMaxSize()) {
            // ── 主内容区域（可滚动） ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Spacer(modifier = Modifier.height(16.dp))

                    // 说明文字
                    Text(
                        text = stringResource(R.string.campus_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── 凭据状态（替代原来的学号/密码输入框） ──
                    CredentialStatusCard(
                        session = authSession,
                        maskedAccount = maskedAccount,
                        onUnlock = {
                            scope.launch {
                                val cipher = viewModel.createUnlockCipher()
                                if (cipher == null) {
                                    viewModel.refreshAuthSession()
                                } else {
                                    biometricUnlocker.authenticate(
                                        cipher = cipher,
                                        onSucceeded = { authenticated ->
                                            scope.launch { viewModel.completeUnlock(authenticated) }
                                        },
                                        onFailed = { /* 用户取消，保持现状 */ }
                                    )
                                }
                            }
                        },
                        onGoToAccount = { onNavigate(Destination.Account) }
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

                    Spacer(modifier = Modifier.height(12.dp))

                    // 选项 4: 学业情况（培养计划 + 学分完成度 + 非正式学时）
                    SyncOptionCard(
                        titleRes = R.string.campus_sync_academic,
                        descRes = R.string.campus_sync_academic_desc,
                        selected = options.academic,
                        onClick = { toggleOption("academic") }
                    )
                }

                // 底部按钮区域
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    val sessionReady = authSession is SessionResult.Active
                    val isSyncing = syncState is SyncUiState.Loading

                    Button(
                        onClick = {
                            viewModel.startSync(
                                syncCourses = options.courses,
                                syncGrades = options.grades,
                                syncExams = options.exams,
                                syncAcademic = options.academic
                            )
                        },
                        enabled = options.hasSelection && sessionReady && !isSyncing,
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

            // ── Loading 遮罩层 ──
            if (syncState is SyncUiState.Loading) {
                val message = (syncState as SyncUiState.Loading).message

                // 拦截系统返回键，防止用户在同步过程中退出
                BackHandler(enabled = true) { /* 不做任何操作 —— 同步过程中禁止返回 */ }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .zIndex(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }

        // ── 副作用处理：Error / Success 的 Toast + 导航 ──
        LaunchedEffect(syncState) {
            when (val state = syncState) {
                is SyncUiState.Error -> {
                    // 先重置再弹 Toast，防止重组导致重复触发
                    viewModel.resetToIdle()
                    Toast.makeText(context, state.errorMsg, Toast.LENGTH_LONG).show()
                }
                is SyncUiState.Success -> {
                    // 先重置再弹 Toast 并退出，防止重组导致重复触发
                    viewModel.resetToIdle()
                    Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                    onBack()
                }
                else -> { /* no-op */ }
            }
        }
    }
}

// region 凭据状态卡片

/**
 * 凭据状态卡片 —— 本页唯一的"能不能同步"说明处。
 *
 * 按 [SessionResult] 的每个分支给出**不同且准确**的文案与操作，
 * 不让用户对着一个灰掉的按钮猜原因。
 */
@Composable
private fun CredentialStatusCard(
    session: SessionResult?,
    maskedAccount: String?,
    onUnlock: () -> Unit,
    onGoToAccount: () -> Unit
) {
    val accountText = maskedAccount ?: stringResource(R.string.account_label_student_id_unset)

    val (title, description) = when (session) {
        null -> stringResource(R.string.campus_sync_credential_checking) to
                stringResource(R.string.campus_sync_credential_checking_desc)

        is SessionResult.Active -> stringResource(R.string.campus_sync_credential_ready, accountText) to
                stringResource(R.string.campus_sync_credential_ready_desc)

        is SessionResult.NeedsUnlock -> stringResource(R.string.campus_sync_credential_need_unlock) to
                stringResource(R.string.campus_sync_credential_need_unlock_desc)

        is SessionResult.NeedsPassword -> stringResource(R.string.campus_sync_credential_need_password) to
                stringResource(R.string.campus_sync_credential_need_password_desc)

        is SessionResult.Expired -> stringResource(R.string.campus_sync_credential_expired) to
                stringResource(R.string.campus_sync_credential_expired_desc)

        is SessionResult.Locked -> stringResource(
            R.string.campus_sync_credential_locked,
            session.remainingSeconds
        ) to stringResource(R.string.campus_sync_credential_locked_desc)

        is SessionResult.Failed -> stringResource(R.string.campus_sync_credential_failed) to
                session.message
    }

    val ready = session is SessionResult.Active

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (ready) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (ready) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (ready) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (session) {
                is SessionResult.NeedsUnlock -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.campus_sync_action_unlock))
                    }
                }

                is SessionResult.NeedsPassword,
                is SessionResult.Expired,
                // 自动重登失败（如教务密码已改）也是死路 —— 保存的密码刚被清掉，
                // 不给出路的话用户只能对着一条错误信息发呆。
                is SessionResult.Failed -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = onGoToAccount, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.campus_sync_action_go_account))
                    }
                }

                else -> Unit
            }
        }
    }
}

// endregion

// region 同步选项卡片

/**
 * 单个同步选项卡片。
 * 选中时显示蓝色边框与浅蓝底，未选中为白底灰边框 —— 对齐 React 原型的视觉语义。
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
