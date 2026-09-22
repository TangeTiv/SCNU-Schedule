package com.xingheyuzhuan.shiguangschedule.data.auth

import com.xingheyuzhuan.shiguangschedule.data.network.ScnuCookieJar
import com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuInvalidCredentialException
import com.xingheyuzhuan.shiguangschedule.data.network.selection.ScnuSsoLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.CookieJar

/**
 * 教务会话的**唯一编排者**。
 *
 * ## 它解决的三个问题
 *
 * 1. **两套登录并存** → 只调用 [ScnuSsoLogin]（双授权路径），
 *    原 `ScnuScraper.login()` 已删除。
 * 2. **每次进教务模块都要重输密码** → 凭据加密落盘 + 生物识别解锁 + 15 分钟会话缓存。
 * 3. **密码可能被改、账号可能被锁** → 凭据失效流程 + 失败冷却。
 *
 * ## 生物识别与静默重登的矛盾（方案文档 §4.3）
 *
 * `setUserAuthenticationRequired(true)` 使密码密钥**只能在用户认证后的短暂窗口内使用**，
 * 因此"后台无人值守自动重登"在技术上做不到。折中是：
 *
 * ```
 * 会话有效期内（15 分钟）→ 直接用内存中的凭据，不碰 Keystore、不弹指纹
 * 会话过期            → BiometricPrompt + CryptoObject 解密 → 再开 15 分钟
 * ```
 *
 * 效果：进教务功能验一次指纹，随后连续操作不再反复验证。
 *
 * ## 会话共享（本次改造利用的关键事实）
 *
 * 教务同步（[com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper]）与选课
 * （`ScnuCourseSelector`）本来就共用同一个 `@Named("scnu")` OkHttpClient 与
 * [ScnuCookieJar]，所以**登录一次两边都可用**。本类只需维护一份会话，
 * 不需要为两个模块各登一次。
 *
 * ## 线程约束
 *
 * 所有 `suspend` 方法内部都切到 [Dispatchers.IO]；`Cipher` 的创建与使用
 * 一律不在主线程做（Keystore 操作是阻塞的，且可能触发硬件交互）。
 */
@Singleton
class ScnuAuthManager @Inject constructor(
    private val ssoLogin: ScnuSsoLogin,
    private val credentialStore: CredentialStore,
    @Named("scnu") private val cookieJar: CookieJar
) {

    companion object {
        /** 解锁后的凭据在内存中的有效期（方案文档 §11.1 决策 32） */
        const val UNLOCK_TTL_MILLIS = 15 * 60 * 1000L

        /** 连续失败阈值（方案文档 §4.4 + 用户 2026-09-22 拍板：5 次） */
        const val FAILURE_THRESHOLD = 5

        /** 触发锁定后的冷却时长（用户 2026-09-22 拍板：1 分钟） */
        const val LOCK_COOLDOWN_MILLIS = 60 * 1000L

        /** 凭据总有效期（方案文档 §11.1 决策 33：30 天） */
        const val CREDENTIAL_TTL_MILLIS = 30L * 24 * 60 * 60 * 1000
    }

    /** 解锁后的凭据 + 它的到期时刻 */
    private class UnlockEntry(val credential: StoredCredential, val expiresAt: Long)

    private val _sessionActive = MutableStateFlow(false)

    /**
     * 教务会话当前是否可用。
     *
     * 这是**内存态**：进程重启即 false。它表达的是"我们这边认为会话还能用"，
     * 服务器端的真实有效性由下一次实际请求去证伪（选课侧有 `refreshContext()` 兜底）。
     */
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    @Volatile
    private var unlockEntry: UnlockEntry? = null

    // ═══════════════════════════════════════════════════════════════════════
    // 对外主接口
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 显式登录并建立会话（**用户主动输入凭据**的路径）。
     *
     * 这一条会**计入失败锁定** —— 连续输错是用户行为，必须限速，
     * 否则用户会把自己在学校的 SSO 账号试锁。
     *
     * @param password 调用方持有所有权；本方法内部会**拷贝**一份用于缓存，
     *                 因此调用方可以立即 `fill('\u0000')` 自己的数组。
     * @return 失败时携带原始异常（[ScnuInvalidCredentialException] 表示凭据被拒）
     */
    suspend fun login(account: String, password: CharArray): Result<Unit> =
        loginInternal(account, password, countFailure = true)

    /**
     * 登录实现。
     *
     * @param countFailure 是否把「凭据被拒」计入失败锁定。
     *   **自动重登（用已保存的密码）必须传 false** —— 用户什么都没输入，
     *   把「密码已过期」记成「用户连续输错」会把他自己的 SSO 账号锁掉。
     *   自动重登失败的正确处理是清掉那份已失效的密码，见 [loginWithUnlockedCredential]。
     */
    private suspend fun loginInternal(
        account: String,
        password: CharArray,
        countFailure: Boolean
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            val trimmed = account.trim()
            if (trimmed.isEmpty() || password.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("请输入学号与密码"))
            }

            runCatching {
                // 底层 OkHttp 的 FormBody 只接受 String，此处不可避免产生一份
                // 不可擦除的临时副本；它的生命周期仅限本次请求。
                ssoLogin.login(trimmed, password.concatToString())
            }.map { }.onSuccess {
                credentialStore.clearFailures()
                setUnlocked(trimmed, password)
                _sessionActive.value = true
            }.onFailure { e ->
                if (countFailure && e is ScnuInvalidCredentialException) {
                    credentialStore.recordFailure(FAILURE_THRESHOLD, LOCK_COOLDOWN_MILLIS)
                }
            }
        }

    /**
     * 确保有一个可用会话；不可用时**如实说明原因**，由调用方决定怎么引导用户。
     *
     * 判定顺序（顺序本身是设计的一部分）：
     * 1. 冷却中 → [SessionResult.Locked]（**最先判**，否则冷却形同虚设）
     * 2. 凭据超过 30 天 → 清密码，返回 [SessionResult.Expired]
     * 3. 会话已建立且解锁未过期 → [SessionResult.Active]
     * 4. 有解锁凭据 → 自动登录
     * 5. 有密码密文但未解锁 → [SessionResult.NeedsUnlock]（调用方弹指纹）
     * 6. 其余 → [SessionResult.NeedsPassword]（调用方引导去账号页）
     */
    suspend fun ensureSession(): SessionResult = withContext(Dispatchers.IO) {
        val lockRemaining = credentialStore.lockRemainingMillis()
        if (lockRemaining > 0) {
            return@withContext SessionResult.Locked(lockRemaining / 1000 + 1)
        }

        val snapshot = credentialStore.snapshot.first()
        if (isExpired(snapshot)) {
            clearUnlocked()
            credentialStore.clearPassword()
            _sessionActive.value = false
            return@withContext SessionResult.Expired
        }

        if (_sessionActive.value && isUnlockValid()) {
            return@withContext SessionResult.Active
        }

        if (validUnlockEntry() != null) {
            return@withContext loginWithUnlockedCredential()
        }

        if (snapshot.hasPassword) SessionResult.NeedsUnlock else SessionResult.NeedsPassword
    }

    /**
     * 用内存中的解锁凭据重新登录（不弹指纹）。
     *
     * 与 [ensureSession] 的区别：本方法**不判断**是否需要解锁，
     * 调用方应先确认解锁缓存有效。
     */
    suspend fun loginWithUnlockedCredential(): SessionResult = withContext(Dispatchers.IO) {
        val entry = validUnlockEntry() ?: return@withContext SessionResult.NeedsUnlock
        loginInternal(entry.credential.account, entry.credential.password, countFailure = false).fold(
            onSuccess = { SessionResult.Active },
            onFailure = { e ->
                // 保存的密码被教务拒绝 —— 它已经没用了，继续留着只会每次进页面
                // 都失败一次。这里**清掉密码但不清学号**，并把失败原因交给 UI，
                // 让用户去账号页重输密码（学号已预填）。
                //
                // 注意：这一条**不计入失败锁定**。用户什么都没输入，
                // 把"密码过期"记成"用户连续输错"会把他自己的 SSO 账号锁掉。
                if (e is ScnuInvalidCredentialException) {
                    clearUnlocked()
                    credentialStore.clearPassword()
                    _sessionActive.value = false
                }
                SessionResult.Failed(e.message ?: "登录失败，请稍后重试")
            }
        )
    }

    /**
     * 登出：清内存会话 + 清 CookieJar。
     *
     * **不动持久化凭据** —— 是否连凭据一起清由调用方决定
     * （账号页的"退出登录"会额外问用户要不要清凭据）。
     */
    fun logout() {
        clearUnlocked()
        (cookieJar as? ScnuCookieJar)?.clear()
        _sessionActive.value = false
    }

    /**
     * 一键清除凭据：Keystore 密钥 + DataStore 密文 + 内存会话 + CookieJar。
     *
     * 这是账号页"一键清除凭据"的实现。四项缺一不可 ——
     * 只清 DataStore 而留着 Keystore 密钥的话，下次保存会复用这把旧密钥；
     * 只清内存而留着密文的话，下次进教务模块又会自动登录，用户会以为没清掉。
     */
    suspend fun clearStoredCredentials() = withContext(Dispatchers.IO) {
        clearUnlocked()
        credentialStore.clearAll()
        (cookieJar as? ScnuCookieJar)?.clear()
        _sessionActive.value = false
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 生物识别支持（Cipher 由 UI 层的 BiometricPrompt 消费）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 创建解密密码用的 cipher，交给 `BiometricPrompt.CryptoObject`。
     *
     * ## 为什么返回 null 时顺手清掉密码
     *
     * 返回 null 只有两种可能：没有已保存的密码，或**密钥已被作废**
     * （用户新增/更换了指纹，`setInvalidatedByBiometricEnrollment(true)` 生效）。
     * 后者如果只是返回 null，状态会一直卡在 [SessionResult.NeedsUnlock]，
     * 用户点"验证身份"永远失败 —— 死循环。
     *
     * 所以这里主动把失效的密码密文清掉，状态自然落到 [SessionResult.NeedsPassword]，
     * UI 就能正确地引导用户去账号页重输密码。
     *
     * @return null 表示无法解锁，调用方应重新查询会话状态
     */
    suspend fun createUnlockCipher(): Cipher? {
        val cipher = credentialStore.createPasswordDecryptCipher()
        if (cipher == null) discardUnusablePassword()
        return cipher
    }

    /** 清掉已不可用的密码密文（密钥作废 / 密文损坏）。 */
    private suspend fun discardUnusablePassword() = withContext(Dispatchers.IO) {
        clearUnlocked()
        credentialStore.clearPassword()
        _sessionActive.value = false
    }

    /**
     * 创建加密密码用的 cipher，交给 `BiometricPrompt.CryptoObject`。
     *
     * @return null 表示 Keystore 不可用（此时应降级为只保存学号）
     */
    fun createSaveCipher(): Cipher? = credentialStore.createPasswordEncryptCipher()

    /**
     * 生物识别通过后：解密密码 → 建立解锁缓存 → 立即尝试登录。
     *
     * @param authenticatedCipher 已由 `BiometricPrompt` 授权的 cipher
     */
    suspend fun completeUnlock(authenticatedCipher: Cipher): SessionResult =
        withContext(Dispatchers.IO) {
            val password = credentialStore.unlockPassword(authenticatedCipher)
            if (password == null) {
                // 密钥被作废 / 密文损坏 → 清掉密码，让用户重新输入
                credentialStore.clearPassword()
                return@withContext SessionResult.NeedsPassword
            }
            val account = credentialStore.accountFlow.first()
            if (account.isNullOrBlank()) {
                password.fill('\u0000')
                credentialStore.clearPassword()
                return@withContext SessionResult.NeedsPassword
            }
            setUnlocked(account, password)
            // setUnlocked 内部已拷贝，这里的临时数组可以立刻擦除
            password.fill('\u0000')
            loginWithUnlockedCredential()
        }

    /**
     * 生物识别通过后：把当前会话的凭据加密保存。
     *
     * 只有登录成功（解锁缓存里有凭据）后调用才有意义。
     *
     * @return true 表示保存成功，同时**顺带清掉 v1.6.0 的明文学号遗留键**
     */
    suspend fun saveCredentialFromSession(cipher: Cipher): Boolean =
        withContext(Dispatchers.IO) {
            val entry = validUnlockEntry() ?: return@withContext false
            runCatching {
                credentialStore.saveCredential(
                    account = entry.credential.account,
                    password = entry.credential.password,
                    passwordCipher = cipher
                )
                credentialStore.clearLegacyAccount()
            }.isSuccess
        }

    /**
     * 降级路径：设备没有可用的强生物识别，只保存学号（**绝不保存密码**）。
     *
     * @return true 表示保存成功
     */
    suspend fun saveAccountOnlyFromSession(): Boolean = withContext(Dispatchers.IO) {
        val account = currentAccount() ?: return@withContext false
        runCatching {
            credentialStore.saveAccountOnly(account)
            credentialStore.clearLegacyAccount()
        }.isSuccess
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 当前学号。
     *
     * 优先取解锁缓存里的（一定是刚用过的那个），否则用学号密钥解密
     * （学号密钥不要求认证，所以这条路随时可用）。
     */
    suspend fun currentAccount(): String? =
        validUnlockEntry()?.credential?.account ?: credentialStore.accountFlow.first()

    /**
     * 一次性迁移 v1.6.0 的明文学号。
     *
     * 老用户升级后不该被要求重输学号 —— 读旧的 `campus_account`，
     * 用学号密钥加密存好，然后把明文键删掉。
     *
     * @return true 表示本次确实发生了迁移
     */
    suspend fun migrateLegacyAccount(): Boolean = withContext(Dispatchers.IO) {
        val snapshot = credentialStore.snapshot.first()
        if (snapshot.hasAccount) {
            // 已有加密学号 → 旧键纯属残留，直接清掉
            credentialStore.clearLegacyAccount()
            return@withContext false
        }
        val legacy = credentialStore.readLegacyAccount() ?: return@withContext false
        credentialStore.saveAccountOnly(legacy)
        credentialStore.clearLegacyAccount()
        true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 写入解锁缓存。
     *
     * **必须先拷贝再清旧的** —— 否则当调用方传入的正是旧缓存里的那个数组时
     * （[loginWithUnlockedCredential] 的自动重登路径就是如此），
     * `clearUnlocked()` 会把参数擦成 `'\u0000'`，缓存里就只剩一串空密码。
     */
    private fun setUnlocked(account: String, password: CharArray) {
        val copy = password.copyOf()
        clearUnlocked()
        unlockEntry = UnlockEntry(
            credential = StoredCredential(account, copy),
            expiresAt = System.currentTimeMillis() + UNLOCK_TTL_MILLIS
        )
    }

    private fun clearUnlocked() {
        unlockEntry?.credential?.clear()
        unlockEntry = null
    }

    /** 取解锁缓存；已过期则顺手清掉并返回 null。 */
    private fun validUnlockEntry(): UnlockEntry? {
        val entry = unlockEntry ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            clearUnlocked()
            return null
        }
        return entry
    }

    private fun isUnlockValid(): Boolean = validUnlockEntry() != null

    private fun isExpired(snapshot: CredentialSnapshot): Boolean =
        snapshot.hasPassword &&
                snapshot.savedAt != null &&
                System.currentTimeMillis() - snapshot.savedAt > CREDENTIAL_TTL_MILLIS
}
