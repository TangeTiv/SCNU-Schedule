package com.xingheyuzhuan.shiguangschedule.data.ai

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.xingheyuzhuan.shiguangschedule.data.auth.KeystoreCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * 用户在设置页看到的 AI 配置快照。
 *
 * ## 刻意不含 API Key 的任何形态
 *
 * 与 `CredentialSnapshot` 同一原则：让上层能安全地做状态派生
 * （"配好了没""要不要显示引导"），而不会不小心把密钥带进日志或 UI。
 * 需要显示时走 [AiKeyStore.maskedKeyFlow]（脱敏），需要发请求时走
 * [AiKeyStore.loadRequestConfig]（现取，用完即弃）。
 */
@Immutable
data class AiSettingsSnapshot(
    val providerId: String = AiProviderPresets.DEEPSEEK_ID,
    val baseUrl: String = "",
    val model: String = "",
    val hasKey: Boolean = false,
    /** 首次隐私弹窗是否已同意 */
    val privacyAccepted: Boolean = false,
    /**
     * 运行期探测到的「该模型不支持工具调用」标记。
     *
     * 一旦置位，AgentLoop 直接走降级路径（规则直查 + 摘要注入），
     * 不再每轮都拿一次 400 去试 —— 那既慢又浪费用户的 token。
     * 设置页提供「重新检测」把它清掉。
     */
    val toolUseUnsupported: Boolean = false
) {
    /** base_url / model / key 三样齐了才算配好。 */
    val isConfigured: Boolean
        get() = hasKey && baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * AI 的 API Key 与设置的**唯一持久化入口**。
 *
 * ## 加密方式（复用 P0 的机制，不另起一套）
 *
 * - 密钥别名 **`ai_key_v1`**，`requireUserAuth = false`
 * - `AES/GCM/NoPadding`，256 位，密文 = `Base64(IV(12 字节) ‖ ciphertext)`
 * - 加解密实现来自 [KeystoreCipher]（与教务凭据同一份代码）
 *
 * ### 为什么**不**要求生物识别
 *
 * 教务密码要求逐次指纹（[com.xingheyuzhuan.shiguangschedule.data.auth.CredentialStore]），
 * 但 API Key 不要求，理由是二者的**泄露后果不同**：
 * 密码泄露 = 别人能登你的教务账号（你改不了别人的操作记录）；
 * API Key 泄露 = 别人花你的钱，而用户**可以随时在厂商后台重置**。
 * 若也要求指纹，则**每一次对话都要验一次指纹**，体验不可接受。
 *
 * ## 存储隔离
 *
 * 存在独立的 `ai_settings` DataStore（见 `DataStoreModule`），与 `auth_credentials` 分开：
 * 双向避免"一键清除"误伤对方。
 *
 * ## 安全硬约束（方案文档 §4.2）
 *
 * - Key **绝不**打进 APK、**绝不**上传任何服务器 —— 只用于直连用户自选的模型厂商。
 * - **日志中绝不打印 Key**：本类只记录异常**类型**，不记录任何值。
 */
@Singleton
class AiKeyStore @Inject constructor(
    @Named("AiSettings") private val dataStore: DataStore<Preferences>,
    private val keystoreCipher: KeystoreCipher
) {

    companion object {
        private const val TAG = "AiKeyStore"

        /** Keystore 别名。**一旦发布不得修改**，改了等于用户已保存的 Key 全部作废。 */
        private const val AI_KEY_ALIAS = "ai_key_v1"

        private val K_PROVIDER_ID = stringPreferencesKey("ai_provider_id")
        private val K_BASE_URL = stringPreferencesKey("ai_base_url")
        private val K_MODEL = stringPreferencesKey("ai_model")
        private val K_KEY_ENC = stringPreferencesKey("ai_key_enc")
        private val K_PRIVACY_ACCEPTED = booleanPreferencesKey("ai_privacy_accepted")
        private val K_TOOL_UNSUPPORTED = booleanPreferencesKey("ai_tool_use_unsupported")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 只读视图
    // ═══════════════════════════════════════════════════════════════════════

    /** 配置快照流（不含任何密钥形态），供设置页与对话页派生 UI 状态。 */
    val snapshot: Flow<AiSettingsSnapshot> = dataStore.data
        .map { prefs ->
            AiSettingsSnapshot(
                providerId = prefs[K_PROVIDER_ID] ?: AiProviderPresets.DEEPSEEK_ID,
                baseUrl = prefs[K_BASE_URL].orEmpty(),
                model = prefs[K_MODEL].orEmpty(),
                hasKey = !prefs[K_KEY_ENC].isNullOrBlank(),
                privacyAccepted = prefs[K_PRIVACY_ACCEPTED] ?: false,
                toolUseUnsupported = prefs[K_TOOL_UNSUPPORTED] ?: false
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)

    /**
     * 脱敏后的 Key，可直接上屏。
     *
     * 完整 Key **绝不**进 UI 层 —— 需要它时只有 [loadRequestConfig] 一条路，
     * 且那条路只在发请求的瞬间调用。这与 `AuthStateRepository` 只暴露脱敏学号同一思路：
     * **从类型上堵住"完整密钥进 UI/日志"**。
     */
    val maskedKeyFlow: Flow<String?> = dataStore.data
        .map { it[K_KEY_ENC] }
        .distinctUntilChanged()
        .map { encoded -> encoded?.let { runCatching { decryptKey(it) }.getOrNull() } }
        .map { key -> key?.let(::maskApiKey) }
        .flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════════════
    // 读取完整配置（仅限发请求前调用）
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 读出可直接用于发起请求的完整配置（**含明文 API Key**）。
     *
     * 命名刻意啰嗦：调用点一眼能看出这里会取出明文，避免被顺手拿去上屏或打日志。
     *
     * @return null = 还没配好（缺 base_url / model / key），或 Key 已无法解密
     */
    suspend fun loadRequestConfig(): AiProviderConfig? = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        val providerId = prefs[K_PROVIDER_ID] ?: AiProviderPresets.DEEPSEEK_ID
        val baseUrl = prefs[K_BASE_URL].orEmpty()
        val model = prefs[K_MODEL].orEmpty()
        val encoded = prefs[K_KEY_ENC]

        if (baseUrl.isBlank() || model.isBlank() || encoded.isNullOrBlank()) return@withContext null

        val key = decryptKey(encoded)
        if (key.isNullOrBlank()) {
            // 解不开（Keystore 被重置 / 用户清了应用数据的一部分）：
            // 密文永远不可能再解出来了，留着只会让"已配置"状态永远为真。
            // 清掉它，让界面如实显示"请重新填写 API Key"。
            Log.w(TAG, "loadRequestConfig: key undecryptable, clearing stale ciphertext")
            clearKey()
            return@withContext null
        }

        val preset = AiProviderPresets.byId(providerId)
        AiProviderConfig(
            providerId = providerId,
            baseUrl = baseUrl,
            model = model,
            apiKey = key,
            supportsToolCalls = resolveToolCallSupport(preset, prefs),
            supportsThinkingToggle = preset.supportsThinkingToggle
        )
    }

    /**
     * 预设声明的工具能力，叠加运行期探测结果。
     *
     * `自定义` 厂商的预设声明是 false（"未知"），但**不能据此就直接降级** ——
     * 用户接的很可能是支持 tool calls 的模型。因此：
     * 预设声明支持 → 直接支持；否则看 `ai_tool_use_unsupported`
     * 这个"实测不支持"的标记。
     */
    private fun resolveToolCallSupport(preset: AiProviderPreset, prefs: Preferences): Boolean {
        if (preset.declaredToolCalls) return true
        return prefs[K_TOOL_UNSUPPORTED] != true
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 保存配置。
     *
     * @param apiKey null 或空串 = **保持原有 Key 不变**（用户只改模型时不必重填 Key）；
     *               非空 = 用新 Key 覆盖。想清除请显式调用 [clearKey]。
     */
    suspend fun save(
        providerId: String,
        baseUrl: String,
        model: String,
        apiKey: String?
    ) = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey?.trim().orEmpty()
        dataStore.edit { prefs ->
            prefs[K_PROVIDER_ID] = providerId
            prefs[K_BASE_URL] = baseUrl.trim().trimEnd('/')
            prefs[K_MODEL] = model.trim()
            if (trimmedKey.isNotEmpty()) {
                prefs[K_KEY_ENC] = encryptKey(trimmedKey)
            }
        }
    }

    /** 清除 API Key，并**一并删除 Keystore 密钥**（理由同 CredentialStore.clearAll）。 */
    suspend fun clearKey() = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs.remove(K_KEY_ENC) }
        keystoreCipher.deleteKey(AI_KEY_ALIAS)
    }

    /** 记录用户是否同意过首次隐私弹窗。 */
    suspend fun setPrivacyAccepted(accepted: Boolean) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs[K_PRIVACY_ACCEPTED] = accepted }
    }

    /**
     * 记录运行期探测结果：该模型不支持工具调用。
     *
     * 置位后 AgentLoop 直接走降级路径；设置页的「重新检测」会把它清掉。
     */
    suspend fun setToolUseUnsupported(unsupported: Boolean) = withContext(Dispatchers.IO) {
        dataStore.edit { prefs -> prefs[K_TOOL_UNSUPPORTED] = unsupported }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部：加解密（全部委托 KeystoreCipher，不要求用户认证）
    // ═══════════════════════════════════════════════════════════════════════

    private fun encryptKey(plain: String): String = keystoreCipher.encrypt(
        alias = AI_KEY_ALIAS,
        requireUserAuth = false,
        plain = plain.toByteArray(Charsets.UTF_8)
    )

    /** @return 解密失败返回 null（只记异常类型，绝不记 Key） */
    private fun decryptKey(encoded: String): String? = runCatching {
        String(
            keystoreCipher.decrypt(
                alias = AI_KEY_ALIAS,
                requireUserAuth = false,
                encoded = encoded
            ),
            Charsets.UTF_8
        )
    }.onFailure {
        keystoreCipher.logKeyFailure("AiKeyStore.decryptKey", it)
    }.getOrNull()
}

/**
 * API Key 脱敏：`sk-abcdefghijklmn` → `sk-****klmn`。
 *
 * 保留前 3 位（厂商前缀，本身不是秘密，且能帮用户确认"填的是不是同一家的 Key"）
 * 与后 4 位（用户可据此对上自己复制的那一串）。过短的输入全部打码。
 */
fun maskApiKey(raw: String): String {
    val trimmed = raw.trim()
    return when {
        trimmed.length >= 12 -> trimmed.take(3) + "****" + trimmed.takeLast(4)
        trimmed.length >= 8 -> "****" + trimmed.takeLast(4)
        else -> "****"
    }
}
