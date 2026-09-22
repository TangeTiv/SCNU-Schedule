package com.xingheyuzhuan.shiguangschedule.ui.account

import android.widget.Toast
import androidx.annotation.StringRes
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.auth.BiometricAvailability
import com.xingheyuzhuan.shiguangschedule.data.auth.ScnuAuthManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PAGE_PADDING = 16.dp
private val CARD_SPACING = 16.dp
private val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * 【我的 → 账号】页面。
 *
 * ## 这是全 App 唯一的"输入教务凭据"的地方
 *
 * 教务同步页、选课页、校园页选课卡都已改为**读取已保存凭据**，
 * 无凭据时一律引导到这里。这样做的好处是凭据的输入、保存、清除、
 * 风险告知集中在同一处，不会出现"某个入口偷偷把密码存下来"的情况。
 *
 * ## 降级说明
 *
 * 设备没有可用的强生物识别时，本页**只保存学号**，并明确说明原因，
 * 绝不为了"能用"而把密码明文落盘。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val maskedAccount by viewModel.maskedAccount.collectAsStateWithLifecycle()
    val hasStoredCredential by viewModel.hasStoredCredential.collectAsStateWithLifecycle()
    val hasStoredPassword by viewModel.hasStoredPassword.collectAsStateWithLifecycle()
    val credentialExpired by viewModel.credentialExpired.collectAsStateWithLifecycle()
    val credentialSavedAt by viewModel.credentialSavedAt.collectAsStateWithLifecycle()
    val biometricAvailability by viewModel.biometricAvailability.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val biometricUnlocker = rememberBiometricUnlocker()

    var showClearConfirm by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // ── 一次性提示 ──
    LaunchedEffect(form.message) {
        val message = form.message ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(message.resId()), Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }
    LaunchedEffect(form.rawError) {
        val error = form.rawError ?: return@LaunchedEffect
        Toast.makeText(context, error, Toast.LENGTH_LONG).show()
        viewModel.consumeMessage()
    }

    // ── 生物识别请求：由 ViewModel 在 IO 线程建好 cipher，这里只负责弹窗 ──
    val biometricRequest by viewModel.biometricRequest.collectAsStateWithLifecycle()
    LaunchedEffect(biometricRequest?.id) {
        val request = biometricRequest ?: return@LaunchedEffect
        biometricUnlocker.authenticate(
            cipher = request.cipher,
            onSucceeded = { authenticated -> viewModel.onBiometricSucceeded(request, authenticated) },
            onFailed = { viewModel.onBiometricFailed(request) }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.account_title),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(CARD_SPACING)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            item {
                AccountStatusCard(
                    isLoggedIn = isLoggedIn,
                    maskedAccount = maskedAccount,
                    hasStoredPassword = hasStoredPassword,
                    hasStoredCredential = hasStoredCredential,
                    credentialExpired = credentialExpired,
                    credentialSavedAt = credentialSavedAt,
                    viewModel = viewModel
                )
            }

            item {
                CredentialFormCard(
                    form = form,
                    hasStoredCredential = hasStoredCredential,
                    hasStoredPassword = hasStoredPassword,
                    maskedAccount = maskedAccount,
                    onAccountChange = viewModel::onAccountChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onTogglePasswordVisible = viewModel::togglePasswordVisible,
                    onLogin = viewModel::login,
                    onLoginWithSavedAccount = viewModel::loginWithSavedAccount,
                    onUnlock = viewModel::unlockWithSavedCredential
                )
            }

            item {
                NoticeCard(
                    biometricAvailability = biometricAvailability,
                    credentialExpired = credentialExpired,
                    viewModel = viewModel
                )
            }

            item {
                DangerZoneCard(
                    isLoggedIn = isLoggedIn,
                    hasStoredCredential = hasStoredCredential,
                    onLogout = { showLogoutConfirm = true },
                    onClearCredential = { showClearConfirm = true }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.account_confirm_clear_title)) },
            text = { Text(stringResource(R.string.account_confirm_clear_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearAllCredentials()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.account_confirm_clear_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showLogoutConfirm) {
        LogoutConfirmDialog(
            onDismiss = { showLogoutConfirm = false },
            onConfirm = { clearCredential ->
                showLogoutConfirm = false
                viewModel.logout(clearCredential)
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 登录状态
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun AccountStatusCard(
    isLoggedIn: Boolean,
    maskedAccount: String?,
    hasStoredPassword: Boolean,
    hasStoredCredential: Boolean,
    credentialExpired: Boolean,
    credentialSavedAt: Long?,
    viewModel: AccountViewModel
) {
    SettingsCard(title = stringResource(R.string.account_section_account_info)) {
        // 状态行：锁定状态单独成行并带倒计时，见 LockNoticeRow
        StatusRow(
            label = stringResource(R.string.account_label_login_state),
            value = when {
                isLoggedIn -> stringResource(R.string.account_status_logged_in)
                else -> stringResource(R.string.account_status_not_logged_in)
            },
            highlight = isLoggedIn
        )

        LockNoticeRow(viewModel = viewModel)

        StatusRow(
            label = stringResource(R.string.account_label_student_id),
            value = maskedAccount ?: stringResource(R.string.account_label_student_id_unset)
        )

        val credential = credentialText(
            hasStoredCredential = hasStoredCredential,
            hasStoredPassword = hasStoredPassword,
            credentialExpired = credentialExpired,
            credentialSavedAt = credentialSavedAt
        )
        StatusRow(
            label = stringResource(R.string.account_label_credential),
            value = credential.primary,
            secondary = credential.secondary
        )
    }
}

/** 凭据状态的两行文案：主值 + 可选补充说明。 */
private data class CredentialText(val primary: String, val secondary: String?)

/**
 * 凭据状态描述。
 *
 * 时间格式化放在 [remember] 里（性能红线 3：不得在 Composable 函数体里
 * new `SimpleDateFormat`），只在 [credentialSavedAt] 变化时重算。
 *
 * 「密码已加密保存」与「保存于 X · 剩余 N 天」**刻意拆成两行**：
 * 合成一句会让标签被挤成两行（见 [StatusRow] 的注释），拆开后两边都清爽。
 */
@Composable
private fun credentialText(
    hasStoredCredential: Boolean,
    hasStoredPassword: Boolean,
    credentialExpired: Boolean,
    credentialSavedAt: Long?
): CredentialText {
    val savedDateText = remember(credentialSavedAt) {
        credentialSavedAt?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it))
        }
    }
    val remainingDays = remember(credentialSavedAt) {
        credentialSavedAt?.let {
            val elapsed = System.currentTimeMillis() - it
            (((ScnuAuthManager.CREDENTIAL_TTL_MILLIS - elapsed) / MILLIS_PER_DAY).coerceAtLeast(0L))
        }
    }

    return when {
        credentialExpired ->
            CredentialText(stringResource(R.string.account_label_credential_expired), null)

        hasStoredPassword && savedDateText != null && remainingDays != null ->
            CredentialText(
                primary = stringResource(R.string.account_label_credential_password_saved),
                secondary = stringResource(
                    R.string.account_label_credential_password_saved_detail,
                    savedDateText,
                    remainingDays
                )
            )

        hasStoredPassword ->
            CredentialText(stringResource(R.string.account_label_credential_password_saved), null)

        hasStoredCredential ->
            CredentialText(stringResource(R.string.account_label_credential_account_only), null)

        else ->
            CredentialText(stringResource(R.string.account_label_credential_none), null)
    }
}

/**
 * 「标签 —— 值」状态行。
 *
 * ## 为什么用 weight 而不是 SpaceBetween
 *
 * 最初用的是 `Arrangement.SpaceBetween` + 两个不定宽的 `Text`。
 * 当值比较长（例如「密码已加密保存（2026-09-22，剩余 29 天）」）时，
 * 两边会**互相挤**：标签被压成两行（「登录凭 / 据」），值也换行，很难看。
 *
 * 改成固定比例分配：标签占 1 份（且 `maxLines = 1`，永不断行），
 * 值占 2 份、右对齐、允许换行。
 *
 * @param secondary 值的补充说明（如保存日期），小字号右对齐显示在值下方。
 *                  长文案拆成两行比挤在一行里可读性好得多。
 */
@Composable
private fun StatusRow(
    label: String,
    value: String,
    secondary: String? = null,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Column(
            modifier = Modifier.weight(2f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                color = if (highlight) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End
            )
            secondary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/**
 * 锁定倒计时。
 *
 * ## 为什么单独成一个 Composable
 *
 * `lockRemainingSeconds` 每秒发射一次。若在 [AccountScreen] 顶层收集，
 * **整页会每秒重组一次**（性能红线 5）。放在这里，每秒重组的只有这一行。
 *
 * ## 为什么不用 `StatusRow`
 *
 * 上面已经有一行「登录状态」了，再加一行同名的标签会让用户以为是两个不同的东西。
 * 这里直接渲染成一行错误色的说明文字。
 */
@Composable
private fun LockNoticeRow(viewModel: AccountViewModel) {
    val remaining by viewModel.lockRemainingSeconds.collectAsStateWithLifecycle()
    if (remaining <= 0) return

    Text(
        text = stringResource(R.string.account_status_locked_countdown, remaining),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 登录表单
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CredentialFormCard(
    form: AccountFormState,
    hasStoredCredential: Boolean,
    hasStoredPassword: Boolean,
    maskedAccount: String?,
    onAccountChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onLogin: () -> Unit,
    onLoginWithSavedAccount: () -> Unit,
    onUnlock: () -> Unit
) {
    SettingsCard(title = stringResource(R.string.account_section_credential)) {

        // 没有保存过学号时才需要用户自己填学号
        if (!hasStoredCredential) {
            OutlinedTextField(
                value = form.account,
                onValueChange = onAccountChange,
                label = { Text(stringResource(R.string.account_field_student_id)) },
                placeholder = { Text(stringResource(R.string.account_field_student_id_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !form.isBusy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = form.password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.account_field_password)) },
            placeholder = { Text(stringResource(R.string.account_field_password_placeholder)) },
            singleLine = true,
            visualTransformation = if (form.passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !form.isBusy,
            trailingIcon = {
                IconButton(onClick = onTogglePasswordVisible) {
                    Icon(
                        imageVector = if (form.passwordVisible) {
                            Icons.Filled.Visibility
                        } else {
                            Icons.Filled.VisibilityOff
                        },
                        contentDescription = if (form.passwordVisible) {
                            stringResource(R.string.a11y_hide_password)
                        } else {
                            stringResource(R.string.a11y_show_password)
                        }
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // 已保存密码时，优先给"用已保存凭据登录"（会弹指纹）
        if (hasStoredPassword) {
            Button(
                onClick = onUnlock,
                enabled = !form.isBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.account_action_login_with_saved))
            }
            Spacer(Modifier.height(8.dp))
        }

        val loginAction: () -> Unit = if (hasStoredCredential) onLoginWithSavedAccount else onLogin
        val loginLabel = when {
            hasStoredCredential && maskedAccount != null ->
                stringResource(R.string.account_action_login_for_account, maskedAccount)
            else -> stringResource(R.string.account_action_login)
        }

        if (hasStoredPassword) {
            // ── 有已保存密码：生物识别是主路径，输密码是备用路径 ──

            // 主按钮在密码框上方（见上），这里只剩"输密码登录"的备用按钮。
            // ⚠️ 只在密码框**有内容**时才显示：密码为空时它必然处于禁用态，
            // 一个灰掉的大胶囊纯属占地方，还让人以为功能坏了。
            // 用户一开始输入，按钮自然出现。
            if (form.password.isNotEmpty()) {
                OutlinedButton(
                    onClick = loginAction,
                    enabled = !form.isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LoginButtonLabel(isBusy = form.isBusy, label = loginLabel)
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = stringResource(R.string.account_hint_relogin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // ── 没有已保存密码：输密码就是主路径 ──
            Button(
                onClick = loginAction,
                enabled = !form.isBusy && form.password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                LoginButtonLabel(isBusy = form.isBusy, label = loginLabel)
            }
        }
    }
}

/** 登录按钮的内容（两种按钮形态共用，避免 loading 分支写两遍）。 */
@Composable
private fun LoginButtonLabel(isBusy: Boolean, label: String) {
    if (isBusy) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.account_action_logging_in))
    } else {
        Text(label)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 提示区
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun NoticeCard(
    biometricAvailability: BiometricAvailability,
    credentialExpired: Boolean,
    viewModel: AccountViewModel
) {
    val biometricNotice = when (biometricAvailability) {
        BiometricAvailability.Available -> null
        BiometricAvailability.NotEnrolled -> R.string.account_notice_biometric_not_enrolled
        BiometricAvailability.Unsupported -> R.string.account_notice_biometric_unsupported
    }

    if (biometricNotice == null && !credentialExpired) return

    SettingsCard(title = stringResource(R.string.account_section_notice)) {
        biometricNotice?.let {
            Text(
                text = stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (biometricNotice != null && credentialExpired) {
            Spacer(Modifier.height(8.dp))
        }
        if (credentialExpired) {
            Text(
                text = stringResource(R.string.account_notice_credential_expired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(8.dp))
        // 未录入生物识别时给一个直达系统设置的入口（仅开发者关心的可忽略）
        if (biometricAvailability == BiometricAvailability.NotEnrolled) {
            Text(
                text = stringResource(R.string.account_notice_biometric_enroll_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 危险区：退出与清除
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DangerZoneCard(
    isLoggedIn: Boolean,
    hasStoredCredential: Boolean,
    onLogout: () -> Unit,
    onClearCredential: () -> Unit
) {
    SettingsCard(title = stringResource(R.string.account_section_danger)) {
        Text(
            text = stringResource(R.string.account_risk_notice_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isLoggedIn || hasStoredCredential) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        if (isLoggedIn) {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.account_action_logout))
            }
        }

        if (hasStoredCredential) {
            if (isLoggedIn) Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClearCredential,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.account_action_clear_credential),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LogoutConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: (clearCredential: Boolean) -> Unit
) {
    var clearCredential by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_confirm_logout_title)) },
        text = {
            Column {
                Text(stringResource(R.string.account_confirm_logout_message))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = clearCredential,
                        onCheckedChange = { clearCredential = it }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.account_confirm_logout_also_clear),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clearCredential) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// 通用卡片外壳
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(PAGE_PADDING),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** [AccountMessage] → 字符串资源。 */
@StringRes
private fun AccountMessage.resId(): Int = when (this) {
    AccountMessage.EmptyFields -> R.string.account_msg_empty_fields
    AccountMessage.LoginSucceeded -> R.string.account_msg_login_succeeded
    AccountMessage.CredentialSaved -> R.string.account_msg_credential_saved
    AccountMessage.SavedAccountOnly -> R.string.account_msg_saved_account_only
    AccountMessage.CredentialSaveFailed -> R.string.account_msg_credential_save_failed
    AccountMessage.SaveCancelled -> R.string.account_msg_save_cancelled
    AccountMessage.UnlockCancelled -> R.string.account_msg_unlock_cancelled
    AccountMessage.UnlockFailed -> R.string.account_msg_unlock_failed
    AccountMessage.CredentialExpired -> R.string.account_msg_credential_expired
    AccountMessage.LoggedOut -> R.string.account_msg_logged_out
    AccountMessage.LoggedOutAndCleared -> R.string.account_msg_logged_out_and_cleared
    AccountMessage.CredentialCleared -> R.string.account_msg_credential_cleared
}
