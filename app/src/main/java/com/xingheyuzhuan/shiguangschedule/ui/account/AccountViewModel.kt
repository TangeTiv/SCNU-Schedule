package com.xingheyuzhuan.shiguangschedule.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.auth.AuthStateRepository
import com.xingheyuzhuan.shiguangschedule.data.auth.BiometricAvailability
import com.xingheyuzhuan.shiguangschedule.data.auth.ScnuAuthManager
import com.xingheyuzhuan.shiguangschedule.data.auth.SessionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject

/**
 * 需要本地化的提示。
 *
 * 用枚举而不是直接拼中文串：本项目有 4 份 `strings.xml`
 * （`values` / `values-zh-rCN` / `values-zh-rTW` / `values-en`），
 * ViewModel 里写死中文会让另外三份失效。
 */
enum class AccountMessage {
    /** 学号或密码为空 */
    EmptyFields,

    /** 登录成功 */
    LoginSucceeded,

    /** 登录成功且密码已加密保存 */
    CredentialSaved,

    /** 登录成功，但设备没有可用的强生物识别，只保存了学号 */
    SavedAccountOnly,

    /** 凭据保存失败（Keystore 异常） */
    CredentialSaveFailed,

    /** 用户取消了保存凭据的指纹验证 */
    SaveCancelled,

    /** 用户取消了解锁指纹 */
    UnlockCancelled,

    /** 解锁失败 */
    UnlockFailed,

    /** 凭据已过期并被清除，请重新输入密码 */
    CredentialExpired,

    /** 已退出登录（保留凭据） */
    LoggedOut,

    /** 已退出登录并清除凭据 */
    LoggedOutAndCleared,

    /** 已清除全部凭据 */
    CredentialCleared
}

/**
 * 账号页表单状态。
 *
 * 只包含**用户正在编辑的内容**与一次性反馈。凭据、登录态、锁定倒计时等
 * 都在 [AuthStateRepository] 里各自独立成流（性能红线 5），不塞进这里。
 */
data class AccountFormState(
    val account: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val isBusy: Boolean = false,
    /** 来自教务/网络的原文错误，直接展示（教务返回的就是中文） */
    val rawError: String? = null,
    /** 需要本地化的提示 */
    val message: AccountMessage? = null
)

/**
 * 一次待执行的生物识别请求。
 *
 * `Cipher` 由 ViewModel 在 IO 线程创建好后放进流里，UI 只负责"弹窗 + 回传"，
 * 不承担任何 Keystore 操作（那些是阻塞的，不该在主线程做）。
 */
sealed interface BiometricRequest {
    val id: Long
    val cipher: Cipher

    /** 保存密码前确认身份（ENCRYPT_MODE） */
    data class Save(override val id: Long, override val cipher: Cipher) : BiometricRequest

    /** 取用已保存密码前确认身份（DECRYPT_MODE） */
    data class Unlock(override val id: Long, override val cipher: Cipher) : BiometricRequest
}

/**
 * 账号页 ViewModel。
 *
 * ## 三条主流程
 *
 * | 场景 | 入口 | 生物识别 |
 * |---|---|---|
 * | 首次登录（无凭据） | [login] | 登录成功后弹一次，用于加密保存密码 |
 * | 已保存凭据、需要恢复会话 | [unlockWithSavedCredential] | 弹一次，解密后自动登录 |
 * | 密码已变 / 已过期 | [loginWithSavedAccount] | 登录成功后弹一次，重新加密保存 |
 *
 * ## 降级
 *
 * 设备没有可用的强生物识别时，**绝不退化为"明文保存密码"**，
 * 而是只保存学号，并明确告诉用户"密码无法安全保存，每次需手动输入"。
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authManager: ScnuAuthManager,
    private val authState: AuthStateRepository
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════
    // 只读状态（直接转发仓库的细粒度流，不做二次包装）
    // ═══════════════════════════════════════════════════════════════════════

    val isLoggedIn: StateFlow<Boolean> = authState.isLoggedIn
    val maskedAccount: StateFlow<String?> = authState.maskedAccount
    val hasStoredCredential: StateFlow<Boolean> = authState.hasStoredCredential
    val hasStoredPassword: StateFlow<Boolean> = authState.hasStoredPassword
    val credentialSavedAt: StateFlow<Long?> = authState.credentialSavedAt
    val credentialExpired: StateFlow<Boolean> = authState.credentialExpired
    val lockRemainingSeconds: StateFlow<Int> = authState.lockRemainingSeconds
    val biometricAvailability: StateFlow<BiometricAvailability> = authState.biometricAvailability

    // ═══════════════════════════════════════════════════════════════════════
    // 可变状态
    // ═══════════════════════════════════════════════════════════════════════

    private val _form = MutableStateFlow(AccountFormState())
    val form: StateFlow<AccountFormState> = _form.asStateFlow()

    private val _biometricRequest = MutableStateFlow<BiometricRequest?>(null)
    val biometricRequest: StateFlow<BiometricRequest?> = _biometricRequest.asStateFlow()

    private var requestSeq = 0L

    init {
        // 用户可能刚在系统设置里录了指纹再切回来，每次进页面重新探测一次
        authState.refreshBiometricAvailability()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 表单交互
    // ═══════════════════════════════════════════════════════════════════════

    fun onAccountChange(value: String) {
        _form.update { it.copy(account = value, rawError = null) }
    }

    fun onPasswordChange(value: String) {
        _form.update { it.copy(password = value, rawError = null) }
    }

    fun togglePasswordVisible() {
        _form.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    /** UI 消费完一次性提示后调用，避免重组时重复弹 Toast */
    fun consumeMessage() {
        _form.update { it.copy(message = null, rawError = null) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 登录
    // ═══════════════════════════════════════════════════════════════════════

    /** 首次登录：需要学号 + 密码。 */
    fun login() {
        val state = _form.value
        val account = state.account.trim()
        val password = state.password
        if (account.isEmpty() || password.isEmpty()) {
            _form.update { it.copy(message = AccountMessage.EmptyFields) }
            return
        }
        submitLogin(account, password)
    }

    /**
     * 已保存学号时重新登录：只需要密码。
     *
     * 学号通过 [ScnuAuthManager.currentAccount] 现取（学号密钥不要求生物识别，
     * 随时可读），因此 UI 上只呈现一个密码框 —— 用户不必重新输学号，
     * 也不必看到完整学号。
     *
     * 刻意不用 `authState.account.value`：那是个 `stateIn` 流，
     * 极端时序下可能还没发射出首值，读 `currentAccount()` 更稳。
     */
    fun loginWithSavedAccount() {
        val password = _form.value.password
        if (password.isEmpty()) {
            _form.update { it.copy(message = AccountMessage.EmptyFields) }
            return
        }
        viewModelScope.launch {
            val account = authManager.currentAccount()
            if (account.isNullOrBlank()) {
                _form.update { it.copy(message = AccountMessage.CredentialExpired) }
                return@launch
            }
            submitLogin(account, password)
        }
    }

    private fun submitLogin(account: String, password: String) {
        if (_form.value.isBusy) return
        _form.update { it.copy(isBusy = true, rawError = null, message = null) }

        viewModelScope.launch {
            // CharArray 用于缓存；login 内部会拷贝一份，这里用完立即擦除
            val passwordChars = password.toCharArray()
            val result = try {
                authManager.login(account, passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }

            result.fold(
                onSuccess = {
                    _form.update {
                        it.copy(
                            isBusy = false,
                            password = "",
                            account = "",
                            message = AccountMessage.LoginSucceeded
                        )
                    }
                    requestCredentialSave()
                },
                onFailure = { e ->
                    _form.update {
                        it.copy(
                            isBusy = false,
                            rawError = e.message ?: "登录失败，请稍后重试"
                        )
                    }
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 已保存凭据：解锁
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 用已保存的密码恢复会话。
     *
     * 若解锁缓存（15 分钟）仍然有效，[ScnuAuthManager.ensureSession] 会直接返回
     * [SessionResult.Active]，**不会弹指纹** —— 这正是"进教务功能验一次、
     * 随后连续操作不再反复验证"的落点。
     */
    fun unlockWithSavedCredential() {
        if (_form.value.isBusy) return
        _form.update { it.copy(isBusy = true, rawError = null, message = null) }

        viewModelScope.launch {
            when (val result = authManager.ensureSession()) {
                is SessionResult.Active -> {
                    _form.update { it.copy(isBusy = false, message = AccountMessage.LoginSucceeded) }
                }
                is SessionResult.NeedsUnlock -> {
                    val cipher = authManager.createUnlockCipher()
                    if (cipher == null) {
                        // 密钥已作废（用户换了指纹）或密文损坏 → 密码已被清掉
                        _form.update {
                            it.copy(isBusy = false, message = AccountMessage.CredentialExpired)
                        }
                    } else {
                        _form.update { it.copy(isBusy = false) }
                        _biometricRequest.value = BiometricRequest.Unlock(++requestSeq, cipher)
                    }
                }
                is SessionResult.Expired -> {
                    _form.update { it.copy(isBusy = false, message = AccountMessage.CredentialExpired) }
                }
                is SessionResult.Locked -> {
                    _form.update { it.copy(isBusy = false) }
                }
                is SessionResult.NeedsPassword -> {
                    _form.update { it.copy(isBusy = false) }
                }
                is SessionResult.Failed -> {
                    _form.update { it.copy(isBusy = false, rawError = result.message) }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 生物识别回调（由 UI 层转发）
    // ═══════════════════════════════════════════════════════════════════════

    /** 指纹验证通过 */
    fun onBiometricSucceeded(request: BiometricRequest, authenticatedCipher: Cipher) {
        _biometricRequest.value = null
        when (request) {
            is BiometricRequest.Save -> completeSave(authenticatedCipher)
            is BiometricRequest.Unlock -> completeUnlock(authenticatedCipher)
        }
    }

    /** 指纹被取消或失败 */
    fun onBiometricFailed(request: BiometricRequest) {
        _biometricRequest.value = null
        when (request) {
            is BiometricRequest.Save -> {
                _form.update { it.copy(message = AccountMessage.SaveCancelled) }
            }
            is BiometricRequest.Unlock -> {
                _form.update { it.copy(message = AccountMessage.UnlockCancelled) }
            }
        }
    }

    private fun completeSave(authenticatedCipher: Cipher) {
        viewModelScope.launch {
            val saved = authManager.saveCredentialFromSession(authenticatedCipher)
            _form.update {
                it.copy(
                    message = if (saved) AccountMessage.CredentialSaved
                    else AccountMessage.CredentialSaveFailed
                )
            }
        }
    }

    private fun completeUnlock(authenticatedCipher: Cipher) {
        viewModelScope.launch {
            _form.update { it.copy(isBusy = true) }
            when (val result = authManager.completeUnlock(authenticatedCipher)) {
                is SessionResult.Active ->
                    _form.update { it.copy(isBusy = false, message = AccountMessage.LoginSucceeded) }
                is SessionResult.NeedsPassword ->
                    _form.update { it.copy(isBusy = false, message = AccountMessage.CredentialExpired) }
                is SessionResult.Locked ->
                    _form.update { it.copy(isBusy = false) }
                is SessionResult.Failed ->
                    _form.update { it.copy(isBusy = false, rawError = result.message) }
                else ->
                    _form.update { it.copy(isBusy = false, message = AccountMessage.UnlockFailed) }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 退出与清除
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 退出登录。
     *
     * @param clearCredential 是否连已保存的凭据一起清除
     */
    fun logout(clearCredential: Boolean) {
        viewModelScope.launch {
            if (clearCredential) {
                authManager.clearStoredCredentials()
                _form.update {
                    it.copy(message = AccountMessage.LoggedOutAndCleared, password = "")
                }
            } else {
                authManager.logout()
                _form.update { it.copy(message = AccountMessage.LoggedOut) }
            }
        }
    }

    /** 一键清除凭据（不要求先登录）。 */
    fun clearAllCredentials() {
        viewModelScope.launch {
            authManager.clearStoredCredentials()
            _form.update {
                it.copy(
                    message = AccountMessage.CredentialCleared,
                    password = "",
                    account = ""
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 登录成功后请求保存凭据。
     *
     * - 有强生物识别 → 生成 ENCRYPT_MODE cipher，交 UI 弹指纹
     * - 没有 → 只保存学号（**绝不保存密码**），并明确提示原因
     */
    private fun requestCredentialSave() {
        if (authState.biometricAvailability.value != BiometricAvailability.Available) {
            saveAccountOnly()
            return
        }
        viewModelScope.launch {
            // Keystore 操作是阻塞的，必须在 IO 上做
            val cipher = withContext(Dispatchers.IO) { authManager.createSaveCipher() }
            if (cipher == null) {
                saveAccountOnly()
            } else {
                _biometricRequest.value = BiometricRequest.Save(++requestSeq, cipher)
            }
        }
    }

    private fun saveAccountOnly() {
        viewModelScope.launch {
            val saved = authManager.saveAccountOnlyFromSession()
            _form.update {
                it.copy(
                    message = if (saved) AccountMessage.SavedAccountOnly
                    else AccountMessage.CredentialSaveFailed
                )
            }
        }
    }
}
