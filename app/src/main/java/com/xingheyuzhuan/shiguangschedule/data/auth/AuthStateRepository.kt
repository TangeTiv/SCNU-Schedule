package com.xingheyuzhuan.shiguangschedule.data.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 账号状态的**只读投影**，供 UI 直接订阅。
 *
 * ## 为什么是"一堆细粒度 StateFlow"而不是一个 `AuthUiState`
 *
 * 性能红线 5：状态粒度决定重组范围。账号页上"剩余锁定秒数"每秒都在变，
 * 若把它和学号、生物识别可用性塞进同一个 data class，**整个页面会每秒重组一次**。
 * 拆开之后，每秒重组的只有那一行倒计时文本。
 *
 * 仓库内部所有 `map` / `combine` 都显式 `.flowOn(Dispatchers.Default)`（性能红线 2）。
 *
 * ## 生命周期
 *
 * 本类是 `@Singleton`，持有一个进程级 `scope`。时间相关的流用
 * `SharingStarted.WhileSubscribed`，**没有订阅者时自动停掉**，不会空转。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AuthStateRepository @Inject constructor(
    private val credentialStore: CredentialStore,
    private val authManager: ScnuAuthManager,
    private val biometricChecker: BiometricAvailabilityChecker
) {
    companion object {
        private const val TICK_MILLIS = 1_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 凭据元数据快照（热流，只订阅一次 DataStore） */
    private val snapshot: StateFlow<CredentialSnapshot> = credentialStore.snapshot.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = CredentialSnapshot(
            hasAccount = false,
            hasPassword = false,
            savedAt = null,
            failCount = 0,
            lockUntil = 0L
        )
    )

    /** 1 秒一跳的时钟；`replay = 1` 避免新订阅者空等一秒 */
    private val ticker: SharedFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MILLIS)
        }
    }.flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), replay = 1)

    // ═══════════════════════════════════════════════════════════════════════
    // 状态
    // ═══════════════════════════════════════════════════════════════════════

    /** 教务会话是否可用（内存态，进程重启即 false） */
    val isLoggedIn: StateFlow<Boolean> = authManager.sessionActive

    /** 是否已保存过凭据（学号或密码任一） */
    val hasStoredCredential: StateFlow<Boolean> = snapshot
        .map { it.hasAccount || it.hasPassword }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 是否已保存**密码**（决定账号页显示"已保存凭据"还是"仅记住学号"） */
    val hasStoredPassword: StateFlow<Boolean> = snapshot
        .map { it.hasPassword }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** 密码的保存时间戳；null 表示未保存密码 */
    val credentialSavedAt: StateFlow<Long?> = snapshot
        .map { it.savedAt }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 脱敏学号，可直接上屏。
     *
     * ## 刻意**不**暴露完整学号
     *
     * 本类只提供脱敏版本。完整学号由 [ScnuAuthManager.currentAccount] 在
     * 「确实要拿去调教务接口」时现取 —— 这样 UI 层从类型上就不可能把
     * 完整学号渲染出来、或写进日志（方案文档 §5.7：日志中不得打印学号）。
     */
    val maskedAccount: StateFlow<String?> = credentialStore.maskedAccountFlow
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 凭据是否已超过 30 天总有效期。
     *
     * 不在流里跑时钟：30 天粒度的判断不需要秒级精度，
     * 真正的判定以 [ScnuAuthManager.ensureSession] 为准（它每次都会重算）。
     */
    val credentialExpired: StateFlow<Boolean> = snapshot
        .map { snap ->
            snap.hasPassword && snap.savedAt != null &&
                    System.currentTimeMillis() - snap.savedAt > ScnuAuthManager.CREDENTIAL_TTL_MILLIS
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * 冷却剩余秒数；0 表示未锁定。
     *
     * 只在**确实处于冷却中**时才挂上秒级时钟 —— 未锁定时 `flatMapLatest`
     * 直接发一个 0 就结束，不会有任何后台空转。
     */
    val lockRemainingSeconds: StateFlow<Int> = snapshot
        .map { it.lockUntil }
        .distinctUntilChanged()
        .flatMapLatest { lockUntil ->
            if (lockUntil <= System.currentTimeMillis()) {
                flowOf(0)
            } else {
                ticker.map { now ->
                    if (lockUntil <= now) 0 else ((lockUntil - now) / 1000).toInt() + 1
                }
            }
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val _biometricAvailability = MutableStateFlow(biometricChecker.check())

    /** 设备生物识别可用性（用于决定"能不能保存密码"） */
    val biometricAvailability: StateFlow<BiometricAvailability> = _biometricAvailability.asStateFlow()

    init {
        // 老用户升级迁移：把 v1.6.0 的明文 campus_account 换成加密存储并删掉旧键。
        // 放在 init 而不是账号页，是为了让"从没打开过账号页"的用户也能在
        // 教务同步页直接看到自己的学号。
        scope.launch {
            runCatching { authManager.migrateLegacyAccount() }
        }
    }

    /**
     * 重新探测生物识别可用性。
     *
     * 用户可能在系统设置里刚录完指纹再切回来，账号页每次进入时调一次即可。
     */
    fun refreshBiometricAvailability() {
        _biometricAvailability.value = biometricChecker.check()
    }
}
