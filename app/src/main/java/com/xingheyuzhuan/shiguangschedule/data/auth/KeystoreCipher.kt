package com.xingheyuzhuan.shiguangschedule.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore 的 AES-GCM 加解密工具，**全项目唯一的加密实现**。
 *
 * ## 为什么单独抽一个类
 *
 * 本类在 v1.8.0（P-AI）从 [CredentialStore] 的私有实现中提取出来，
 * 供两处共用：
 *
 * | 使用方 | 密钥别名 | 保护对象 |
 * |---|---|---|
 * | [CredentialStore] | `scnu_account_key_v1` / `scnu_credential_key_v1` | 学号 / 密码 |
 * | [com.xingheyuzhuan.shiguangschedule.data.ai.AiKeyStore] | `ai_key_v1` | 用户自带的 LLM API Key |
 *
 * 抽取的动机是「加密实现只允许存在一份」：如果让 `AiKeyStore` 自己再写一套，
 * 将来换算法或修 bug 就要改两处，必然漏一处。
 *
 * ## ⚠️ 抽取时的兼容性约束（**已遵守**）
 *
 * `CredentialStore` 在 v1.7.0 已发布，设备上存在**存量密文**。
 * 因此本类必须保证：
 *
 * 1. **密钥别名不变** —— 别名是密文的解钥依据，改名等于用户凭据作废。
 * 2. **密钥生成参数不变** —— `AES/GCM/NoPadding`、256 位、`setUserAuthenticationRequired`
 *    的取值规则逐字保留（含「仅在要求认证时才调用 `setInvalidatedByBiometricEnrollment`」）。
 * 3. **密文格式不变** —— `Base64(IV(12 字节) ‖ ciphertext)`，每次加密重新生成随机 IV。
 * 4. **异常语义不变** —— [getOrCreateKey] 不吞异常：密钥被指纹变更作废时，
 *    必须让调用方拿到 `UnrecoverableKeyException`，否则会用废密钥去解密。
 *
 * ## 残余风险（与 P0 一致，不夸大防护效果）
 *
 * 密钥不出 TEE / StrongBox，但 root / 定制 ROM / 备份提取仍可能拿到**密文**；
 * 极端情况下（已 root 且能注入本进程）也可能在运行时拿到明文。
 */
@Singleton
class KeystoreCipher @Inject constructor() {

    companion object {
        private const val TAG = "KeystoreCipher"

        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES

        /** 加密变换。GCM 同时提供机密性与完整性校验。 */
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** 密钥长度（位）。 */
        private const val KEY_SIZE_BITS = 256

        /** GCM 推荐 96 位（12 字节）IV。 */
        const val IV_BYTES = 12

        /** GCM 认证标签长度。 */
        const val TAG_BITS = 128
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 密钥管理
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 取密钥；不存在则创建。
     *
     * @param alias 密钥别名。**已发布的别名不得修改**。
     * @param requireUserAuth true = 每次使用都要求生物识别（`CryptoObject` 模式），
     *                        false = 直接可用
     * @throws GeneralSecurityException 密钥已因指纹变更被作废（`UnrecoverableKeyException`）
     */
    fun getOrCreateKey(alias: String, requireUserAuth: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        // 注意：不吞异常 —— 密钥被作废时必须让调用方感知，否则会用废密钥去解密
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, KEYSTORE_PROVIDER)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setUserAuthenticationRequired(requireUserAuth)

        if (requireUserAuth) {
            // 仅在要求认证时才可调用（否则抛 IllegalArgumentException）：
            // 用户新增/更换生物识别 → 旧密钥作废 → 必须重新输入密码
            builder.setInvalidatedByBiometricEnrollment(true)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }

    /**
     * 删除密钥。
     *
     * 清除密文时**必须一并删除密钥** —— 只删密文的话密钥会一直留在 Keystore 里，
     * 下一次保存时 [getOrCreateKey] 会复用它，而它的作废状态会继承下来。
     */
    fun deleteKey(alias: String) {
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(alias)
        }
    }

    /** Keystore 中是否已存在该别名（用于「是否有已保存的 Key」判断，不读取任何明文）。 */
    fun containsKey(alias: String): Boolean = runCatching {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.containsAlias(alias)
    }.getOrDefault(false)

    // ═══════════════════════════════════════════════════════════════════════
    // Cipher 构造（供 BiometricPrompt 的 CryptoObject 使用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 创建加密用的 cipher（交给 `BiometricPrompt.CryptoObject`）。
     *
     * 密钥不存在时会先创建；`init(ENCRYPT_MODE, key)` 不需要 IV ——
     * AndroidKeyStore 会在这一步生成随机 IV，授权后由 [encryptWithCipher] 从
     * `cipher.iv` 取回。
     *
     * @return 失败（Keystore 异常）时返回 null
     */
    fun newEncryptCipher(alias: String, requireUserAuth: Boolean): Cipher? = runCatching {
        val key = getOrCreateKey(alias, requireUserAuth)
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }.onFailure { logKeyFailure("newEncryptCipher($alias)", it) }.getOrNull()

    /**
     * 创建解密用的 cipher。
     *
     * 与加密不同，解密**必须**在 `init` 时带上加密时用过的 IV，
     * 所以入参是密文本体 [encoded]（IV 在它的头部）。
     *
     * @return 密钥已作废（如用户新增了指纹）或密文损坏时返回 null
     */
    fun newDecryptCipher(alias: String, requireUserAuth: Boolean, encoded: String): Cipher? =
        runCatching {
            val iv = decodeIv(encoded)
            val key = getOrCreateKey(alias, requireUserAuth)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            }
        }.onFailure { logKeyFailure("newDecryptCipher($alias)", it) }.getOrNull()

    // ═══════════════════════════════════════════════════════════════════════
    // 一步式加解密（不要求认证的密钥走这条）
    // ═══════════════════════════════════════════════════════════════════════

    /** 用指定别名的一次性密钥加密。每次调用都会生成新的随机 IV。 */
    fun encrypt(alias: String, requireUserAuth: Boolean, plain: ByteArray): String {
        val key = getOrCreateKey(alias, requireUserAuth)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return encode(cipher.iv, cipher.doFinal(plain))
    }

    /** 用指定别名的一次性密钥解密。 */
    fun decrypt(alias: String, requireUserAuth: Boolean, encoded: String): ByteArray {
        val key = getOrCreateKey(alias, requireUserAuth)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, decodeIv(encoded)))
        }
        return cipher.doFinal(decodeCiphertext(encoded))
    }

    /**
     * 用**已授权**的 cipher 加密（IV 由 AndroidKeyStore 在 `init` 时生成）。
     *
     * 密码密钥要求逐次认证，未认证的 cipher 在 `doFinal` 时会抛
     * `UserNotAuthenticatedException`。
     */
    fun encryptWithCipher(cipher: Cipher, plain: ByteArray): String {
        val ciphertext = cipher.doFinal(plain)
        return encode(cipher.iv, ciphertext)
    }

    /** 用**已授权**的 cipher 解出明文。 */
    fun decryptWithCipher(cipher: Cipher, encoded: String): ByteArray =
        cipher.doFinal(decodeCiphertext(encoded))

    // ═══════════════════════════════════════════════════════════════════════
    // 密文编解码：Base64(IV ‖ ciphertext)
    // ═══════════════════════════════════════════════════════════════════════

    /** `IV ‖ ciphertext` → Base64 */
    fun encode(iv: ByteArray, ciphertext: ByteArray): String {
        val packed = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, packed, 0, iv.size)
        System.arraycopy(ciphertext, 0, packed, iv.size, ciphertext.size)
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    fun decodeRaw(encoded: String): ByteArray = Base64.decode(encoded, Base64.NO_WRAP)

    fun decodeIv(encoded: String): ByteArray = decodeRaw(encoded).copyOfRange(0, IV_BYTES)

    fun decodeCiphertext(encoded: String): ByteArray {
        val raw = decodeRaw(encoded)
        return raw.copyOfRange(IV_BYTES, raw.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 日志
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 只记录异常**类型**，绝不记录任何与学号/密码/API Key 相关的值。
     *
     * 这是硬约束（方案文档 §5.7 与 §4.2：日志中不得打印学号、密码、API Key）。
     */
    fun logKeyFailure(where: String, e: Throwable) {
        Log.w(TAG, "$where failed: ${e.javaClass.simpleName}")
    }
}
