package com.xingheyuzhuan.shiguangschedule.data.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.CharBuffer
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 教务凭据的**唯一持久化入口**。
 *
 * ## 双密钥设计（重要，别简化成一把）
 *
 * | 密钥 | 保护对象 | 是否要求用户认证 | 理由 |
 * |---|---|---|---|
 * | `scnu_account_key_v1` | 学号 | ❌ 不要求 | 学号要用于「脱敏显示」「重输密码时预填」，若也要求指纹，用户每次看状态都得先验一次指纹，体验不可接受 |
 * | `scnu_credential_key_v1` | 密码 | ✅ 要求（`CryptoObject` 模式） | 密码是最高敏感项，必须逐次认证 |
 *
 * 两把密钥都**不出 Android Keystore**（TEE / StrongBox），因此
 * 「root 后直接拷走 DataStore 文件」拿到的只是密文。
 *
 * > ⚠️ **已记录的残余风险**：root / 定制 ROM / 备份提取仍可能拿到密文；
 * > 极端情况下（已 root 且能注入本进程）也可能在运行时拿到明文。
 * > 这一条在账号页对用户明示，见 [com.xingheyuzhuan.shiguangschedule.ui.account.AccountScreen]。
 *
 * ## 密钥参数
 *
 * - `AES/GCM/NoPadding`，256 位
 * - 密码密钥：`setUserAuthenticationRequired(true)` +
 *   `setInvalidatedByBiometricEnrollment(true)`
 *   → 用户新增指纹时**旧密钥立即作废**，需要重新输入密码。
 *   这是安全上的正确行为（指纹变更可能意味着设备易主），代价是用户体验上要多输一次。
 * - 学号密钥：不设认证要求，仅靠 Keystore 的「密钥不出安全硬件」特性保护
 *
 * ## 存储形态
 *
 * 每条密文 = `Base64(IV(12 字节) ‖ ciphertext)`，**每次加密都重新生成随机 IV**
 * （绝不复用 IV —— GCM 下复用 IV 会直接毁掉机密性）。
 *
 * ## 与旧版 `campus_account` 的关系
 *
 * v1.6.0 及以前，学号明文存在 `app_settings` 的 `campus_account` 键里。
 * 本类提供 [readLegacyAccount] / [clearLegacyAccount] 用于**一次性迁移读取**，
 * 迁移完成后由 [ScnuAuthManager] 负责把旧键删掉。
 *
 * ## v1.8.0 变更（P-AI）
 *
 * 加解密实现已抽到 [KeystoreCipher]，与 AI 的 API Key 存储
 * （`data/ai/AiKeyStore`）共用同一份代码。
 * **本类的对外接口与密文格式一字未改**，设备上的存量凭据照常可解。
 */
@Singleton
class CredentialStore @Inject constructor(
    @Named("AuthCredentials") private val dataStore: DataStore<Preferences>,
    /**
     * 仅用于读取/清除 v1.6.0 遗留的明文键 `campus_account`。
     *
     * 之所以让凭据仓库直接持有设置仓库的 DataStore，而不是另开一个迁移类：
     * 迁移是**一次性的**，且迁移对象本身就是凭据数据，放在这里最不容易漏。
     */
    @Named("AppSettings") private val appSettingsDataStore: DataStore<Preferences>,
    /** 共享的 Keystore 加解密实现（全项目唯一一份）。 */
    private val keystoreCipher: KeystoreCipher
) {

    companion object {
        private const val ACCOUNT_KEY_ALIAS = "scnu_account_key_v1"
        private const val CREDENTIAL_KEY_ALIAS = "scnu_credential_key_v1"

        /** v1.6.0 遗留的明文键（在 `app_settings` 里） */
        private const val LEGACY_ACCOUNT_KEY = "campus_account"

        // ── auth_credentials 的键 ──
        private val K_ACCOUNT_ENC = stringPreferencesKey("cred_account_enc")
        private val K_PASSWORD_ENC = stringPreferencesKey("cred_password_enc")
        private val K_SAVED_AT = longPreferencesKey("cred_saved_at")
        private val K_FAIL_COUNT = intPreferencesKey("cred_fail_count")
        private val K_LOCK_UNTIL = longPreferencesKey("cred_lock_until")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 只读视图（供 AuthStateRepository 派生细粒度状态）
    // ═══════════════════════════════════════════════════════════════════════

    /** 凭据元数据快照（不含任何密文/明文） */
    val snapshot: Flow<CredentialSnapshot> = dataStore.data
        .map { prefs ->
            CredentialSnapshot(
                hasAccount = prefs[K_ACCOUNT_ENC] != null,
                hasPassword = prefs[K_PASSWORD_ENC] != null,
                savedAt = prefs[K_SAVED_AT],
                failCount = prefs[K_FAIL_COUNT] ?: 0,
                lockUntil = prefs[K_LOCK_UNTIL] ?: 0L
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /**
     * 解密后的学号。
     *
     * 学号密钥不要求用户认证，因此这条流**无需生物识别**即可发射。
     * 密文变化时自动重算（`distinctUntilChanged` 去重，避免无谓解密）。
     */
    val accountFlow: Flow<String?> = dataStore.data
        .map { it[K_ACCOUNT_ENC] }
        .distinctUntilChanged()
        .map { encoded -> encoded?.let { runCatching { decryptWithAccountKey(it) }.getOrNull() } }
        .flowOn(Dispatchers.IO)

    /** 脱敏学号，可直接上屏。完整学号**绝不**进 UI 层。 */
    val maskedAccountFlow: Flow<String?> = accountFlow
        .map { it?.let(::maskStudentId) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 保存学号 + 密码。
     *
     * @param passwordCipher **已经通过生物识别授权**的 ENCRYPT_MODE cipher。
     *                       由 `BiometricPrompt` 的 `CryptoObject` 回调提供 ——
     *                       密码密钥要求逐次认证，未认证的 cipher 在 `doFinal` 时会抛
     *                       `UserNotAuthenticatedException`。
     */
    suspend fun saveCredential(
        account: String,
        password: CharArray,
        passwordCipher: Cipher
    ) = withContext(Dispatchers.IO) {
        val accountEnc = encryptWithAccountKey(account)
        val passwordEnc = run {
            val plain = password.toUtf8Bytes()
            try {
                keystoreCipher.encryptWithCipher(passwordCipher, plain)
            } finally {
                plain.fill(0)
            }
        }
        dataStore.edit { prefs ->
            prefs[K_ACCOUNT_ENC] = accountEnc
            prefs[K_PASSWORD_ENC] = passwordEnc
            prefs[K_SAVED_AT] = System.currentTimeMillis()
            prefs[K_FAIL_COUNT] = 0
            prefs.remove(K_LOCK_UNTIL)
        }
    }

    /**
     * 只保存学号（**降级路径**）。
     *
     * 设备没有可用的强生物识别时使用：此时密码密钥根本无法使用，
     * 唯一安全的做法是**不保存密码**，只把学号留下来用于预填与脱敏显示。
     * 绝不允许「为了能用就把密码明文存下来」。
     */
    suspend fun saveAccountOnly(account: String) = withContext(Dispatchers.IO) {
        val accountEnc = encryptWithAccountKey(account)
        dataStore.edit { prefs ->
            prefs[K_ACCOUNT_ENC] = accountEnc
            prefs.remove(K_PASSWORD_ENC)
            prefs.remove(K_SAVED_AT)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 生物识别用的 Cipher
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 创建加密密码用的 cipher（交给 `BiometricPrompt.CryptoObject`）。
     *
     * 密钥不存在时会先创建；`init(ENCRYPT_MODE, key)` 不需要 IV ——
     * AndroidKeyStore 会在这一步生成随机 IV，授权后由 [encryptWithAuthenticatedCipher]
     * 从 `cipher.iv` 取回。
     *
     * @return 失败（Keystore 异常）时返回 null
     */
    fun createPasswordEncryptCipher(): Cipher? =
        keystoreCipher.newEncryptCipher(CREDENTIAL_KEY_ALIAS, requireUserAuth = true)

    /**
     * 创建解密密码用的 cipher。
     *
     * 与加密不同，解密**必须**在 `init` 时带上加密时用过的 IV，
     * 所以这里要先读一次 DataStore 取出密文头部的 IV。
     *
     * @return 没有已保存密码、或密钥已作废（如用户新增了指纹）时返回 null
     */
    suspend fun createPasswordDecryptCipher(): Cipher? = withContext(Dispatchers.IO) {
        val encoded = dataStore.data.first()[K_PASSWORD_ENC] ?: return@withContext null
        keystoreCipher.newDecryptCipher(CREDENTIAL_KEY_ALIAS, requireUserAuth = true, encoded)
    }

    /**
     * 用已授权的 cipher 解出密码。
     *
     * @return 解密失败（密钥作废 / 密文损坏）时返回 null；调用方拿到非 null 结果后
     *         **有责任**在不再需要时调用 [StoredCredential.clear]。
     */
    suspend fun unlockPassword(authenticatedCipher: Cipher): CharArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = dataStore.data.first()[K_PASSWORD_ENC]
                    ?: return@runCatching null
                keystoreCipher.decryptWithCipher(authenticatedCipher, encoded).toUtf8Chars()
            }.onFailure { logKeyFailure("unlockPassword", it) }.getOrNull()
        }

    // ═══════════════════════════════════════════════════════════════════════
    // 清除
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 一键清除：密文、失败计数、以及**两把 Keystore 密钥**。
     *
     * 密钥必须一并删除 —— 只删密文的话密钥会一直留在 Keystore 里，
     * 下一次保存时 `getOrCreateKey` 会复用它，而它的作废状态会继承下来。
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dataStore.edit { it.clear() }
        keystoreCipher.deleteKey(ACCOUNT_KEY_ALIAS)
        keystoreCipher.deleteKey(CREDENTIAL_KEY_ALIAS)
    }

    /**
     * 只清密码，保留学号（凭据满 30 天时的处理）。
     *
     * 用户下次只需重新输入密码，学号仍能预填。
     */
    suspend fun clearPassword() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            prefs.remove(K_PASSWORD_ENC)
            prefs.remove(K_SAVED_AT)
        }
        keystoreCipher.deleteKey(CREDENTIAL_KEY_ALIAS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 失败计数与冷却
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 记一次登录失败。
     *
     * 达到 [threshold] 次即进入冷却，并把计数清零 —— 冷却结束后用户
     * 重新获得完整的 [threshold] 次机会。
     *
     * @return 本次是否触发了锁定；触发时附带锁定截止时间戳
     */
    suspend fun recordFailure(threshold: Int, cooldownMillis: Long): Long =
        withContext(Dispatchers.IO) {
            var lockUntil = 0L
            dataStore.edit { prefs ->
                val count = (prefs[K_FAIL_COUNT] ?: 0) + 1
                if (count >= threshold) {
                    lockUntil = System.currentTimeMillis() + cooldownMillis
                    prefs[K_LOCK_UNTIL] = lockUntil
                    prefs[K_FAIL_COUNT] = 0
                } else {
                    prefs[K_FAIL_COUNT] = count
                }
            }
            lockUntil
        }

    /** 登录成功后清除失败计数与冷却。 */
    suspend fun clearFailures() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs ->
            prefs[K_FAIL_COUNT] = 0
            prefs.remove(K_LOCK_UNTIL)
        }
    }

    /** 冷却剩余毫秒数；0 表示未处于冷却中。 */
    suspend fun lockRemainingMillis(): Long = withContext(Dispatchers.IO) {
        val lockUntil = dataStore.data.first()[K_LOCK_UNTIL] ?: 0L
        (lockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v1.6.0 遗留键迁移
    // ═══════════════════════════════════════════════════════════════════════

    /** 读取 v1.6.0 遗留的明文学号；不存在时返回 null。 */
    suspend fun readLegacyAccount(): String? = withContext(Dispatchers.IO) {
        appSettingsDataStore.data.first()[stringPreferencesKey(LEGACY_ACCOUNT_KEY)]
            ?.takeIf { it.isNotBlank() }
    }

    /** 删除遗留的明文学号键（迁移完成后调用）。 */
    suspend fun clearLegacyAccount() = withContext(Dispatchers.IO) {
        appSettingsDataStore.edit { it.remove(stringPreferencesKey(LEGACY_ACCOUNT_KEY)) }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部：加解密
    //
    // 实现全部委托给 [KeystoreCipher]。保留这两个私有包装方法而不是直接在
    // 调用点写 `keystoreCipher.encrypt(...)`，是为了让「学号用哪把密钥、
    // 要不要认证」这件事在**一个地方**说清楚，调用点读起来仍是原来的语义。
    // ═══════════════════════════════════════════════════════════════════════

    /** 用不要求认证的密钥加密学号。 */
    private fun encryptWithAccountKey(plain: String): String =
        keystoreCipher.encrypt(
            alias = ACCOUNT_KEY_ALIAS,
            requireUserAuth = false,
            plain = plain.toByteArray(Charsets.UTF_8)
        )

    /** 用不要求认证的密钥解密学号。 */
    private fun decryptWithAccountKey(encoded: String): String =
        String(
            keystoreCipher.decrypt(
                alias = ACCOUNT_KEY_ALIAS,
                requireUserAuth = false,
                encoded = encoded
            ),
            Charsets.UTF_8
        )

    /**
     * 只记录异常**类型**，绝不记录任何与学号/密码相关的值。
     * 这是硬约束（方案文档 §5.7：日志中不得打印学号）。
     */
    private fun logKeyFailure(where: String, e: Throwable) =
        keystoreCipher.logKeyFailure(where, e)
}

/**
 * 凭据的元数据快照。
 *
 * 刻意**不含**学号与密码的任何形态（明文或密文）——
 * 让上层能安全地做状态派生，而不会不小心把密文带进日志或 UI。
 */
data class CredentialSnapshot(
    val hasAccount: Boolean,
    val hasPassword: Boolean,
    val savedAt: Long?,
    val failCount: Int,
    val lockUntil: Long
)

// ═══════════════════════════════════════════════════════════════════════════
// 工具函数
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 学号脱敏：`202421315041` → `2024****41`。
 *
 * 保留前 4 位（入学年，本身不是敏感信息）与后 2 位，便于用户确认"是不是我的号"，
 * 中间全部打码。过短的输入退化为只保留首位。
 */
fun maskStudentId(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.length >= 7 -> trimmed.take(4) + "****" + trimmed.takeLast(2)
        trimmed.length >= 3 -> trimmed.take(1) + "****" + trimmed.takeLast(1)
        else -> "****"
    }
}

/**
 * `CharArray` → UTF-8 字节，**不产生中间 String**。
 *
 * 若写成 `chars.concatToString().toByteArray()`，中间那个 String 是不可擦除的，
 * 等于把「用 CharArray 缩短明文停留时间」的努力白费掉。
 */
private fun CharArray.toUtf8Bytes(): ByteArray {
    val buffer: ByteBuffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(this))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

/** UTF-8 字节 → `CharArray`，同样不产生中间 String。 */
private fun ByteArray.toUtf8Chars(): CharArray {
    val buffer: CharBuffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(this))
    return CharArray(buffer.remaining()).also { buffer.get(it) }
}
