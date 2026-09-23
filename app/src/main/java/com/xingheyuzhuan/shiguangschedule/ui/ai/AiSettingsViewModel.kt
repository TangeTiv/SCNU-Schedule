package com.xingheyuzhuan.shiguangschedule.ui.ai

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.ai.AiConnectionTestResult
import com.xingheyuzhuan.shiguangschedule.data.ai.AiFailure
import com.xingheyuzhuan.shiguangschedule.data.ai.AiKeyStore
import com.xingheyuzhuan.shiguangschedule.data.ai.AiProvider
import com.xingheyuzhuan.shiguangschedule.data.ai.AiProviderConfig
import com.xingheyuzhuan.shiguangschedule.data.ai.AiProviderPresets
import com.xingheyuzhuan.shiguangschedule.data.ai.AiSettingsSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页表单状态。 */
@Immutable
data class AiSettingsForm(
    val providerId: String = AiProviderPresets.DEEPSEEK_ID,
    val baseUrl: String = "",
    val model: String = "",
    /** 用户本次输入的 Key；空串表示"沿用已保存的" */
    val apiKeyInput: String = "",
    val keyVisible: Boolean = false
)

/** 连通性测试状态。 */
sealed interface AiTestState {
    data object Idle : AiTestState
    data object Testing : AiTestState
    data class Ok(val modelEcho: String?, val latencyMillis: Long) : AiTestState
    data class Failed(val failure: AiFailure) : AiTestState
}

/**
 * AI 设置页 ViewModel。
 *
 * ## 表单与已保存配置的关系
 *
 * 表单初值来自 [AiKeyStore.snapshot]（**只在 init 里读一次**）。
 * 之后不再跟随快照变化，否则用户正在输入时会被自动回填打断。
 *
 * ## 为什么"测试连通性"成功就自动保存
 *
 * 方案文档 §9.1 把「填完立刻验证可用/不可用」列为必须配套的第 4 件事。
 * 若测试用的是"当前输入"而保存要另外点一次，用户很容易测通了却没保存，
 * 回到对话页发现还是"未配置"。因此：**测试成功即落盘**，并在界面提示。
 */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val keyStore: AiKeyStore,
    private val provider: AiProvider
) : ViewModel() {

    /** 已保存的配置快照（不含任何密钥形态）。 */
    val snapshot: StateFlow<AiSettingsSnapshot> = keyStore.snapshot
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AiSettingsSnapshot()
        )

    /** 脱敏后的 Key，可直接上屏。 */
    val maskedKey: StateFlow<String?> = keyStore.maskedKeyFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _form = MutableStateFlow(AiSettingsForm())
    val form: StateFlow<AiSettingsForm> = _form.asStateFlow()

    private val _testState = MutableStateFlow<AiTestState>(AiTestState.Idle)
    val testState: StateFlow<AiTestState> = _testState.asStateFlow()

    /** 一次性提示（`@StringRes`），UI 消费后调用 [consumeToast]。 */
    private val _toast = MutableStateFlow<Int?>(null)
    val toast: StateFlow<Int?> = _toast.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = keyStore.snapshot.first()
            _form.value = AiSettingsForm(
                providerId = saved.providerId,
                baseUrl = saved.baseUrl.ifBlank {
                    AiProviderPresets.byId(saved.providerId).baseUrl
                },
                model = saved.model.ifBlank {
                    AiProviderPresets.byId(saved.providerId).defaultModel
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 表单编辑
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 切换厂商。
     *
     * 同时把 base_url / model 重置为新预设的默认值 ——
     * 否则从「自定义」切回 DeepSeek 时会残留上一家的地址，
     * 用户点保存后请求会发到错误的域名。
     */
    fun selectProvider(providerId: String) {
        val preset = AiProviderPresets.byId(providerId)
        _form.update {
            it.copy(
                providerId = providerId,
                baseUrl = preset.baseUrl,
                model = preset.defaultModel,
                apiKeyInput = ""
            )
        }
        _testState.value = AiTestState.Idle
    }

    fun updateBaseUrl(value: String) = _form.update { it.copy(baseUrl = value) }

    fun updateModel(value: String) = _form.update { it.copy(model = value) }

    fun updateApiKeyInput(value: String) = _form.update { it.copy(apiKeyInput = value) }

    fun toggleKeyVisible() = _form.update { it.copy(keyVisible = !it.keyVisible) }

    fun consumeToast() {
        _toast.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 保存 / 测试 / 清除
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 保存表单。
     *
     * Key 输入为空时**保留原有 Key** —— 用户只改模型或地址时不必重填 Key。
     */
    fun save() {
        val form = _form.value
        val hasSavedKey = snapshot.value.hasKey

        val validationError = validate(form, hasSavedKey)
        if (validationError != null) {
            _toast.value = validationError
            return
        }

        viewModelScope.launch {
            keyStore.save(
                providerId = form.providerId,
                baseUrl = form.baseUrl,
                model = form.model,
                apiKey = form.apiKeyInput.takeIf { it.isNotBlank() }
            )
            _form.update { it.copy(apiKeyInput = "") }
            _toast.value = R.string.ai_settings_saved
        }
    }

    /**
     * 一键测试连通性。
     *
     * 用**当前表单**的值发起一次最小请求（而非已保存的配置），
     * 这样用户改完地址可以立刻验证，不必先保存。
     * 成功即自动保存，见类注释。
     */
    fun testConnection() {
        val form = _form.value
        val hasSavedKey = snapshot.value.hasKey

        val validationError = validate(form, hasSavedKey)
        if (validationError != null) {
            _toast.value = validationError
            return
        }

        _testState.value = AiTestState.Testing
        viewModelScope.launch {
            val preset = AiProviderPresets.byId(form.providerId)

            // 表单没填 Key 时，用已保存的那把
            val effectiveKey = form.apiKeyInput.takeIf { it.isNotBlank() }
                ?: keyStore.loadRequestConfig()?.apiKey

            if (effectiveKey.isNullOrBlank()) {
                _testState.value = AiTestState.Failed(AiFailure.NotConfigured("api_key"))
                return@launch
            }

            val config = AiProviderConfig(
                providerId = form.providerId,
                baseUrl = form.baseUrl,
                model = form.model,
                apiKey = effectiveKey,
                supportsToolCalls = preset.declaredToolCalls,
                supportsThinkingToggle = preset.supportsThinkingToggle
            )

            when (val result = provider.testConnection(config)) {
                is AiConnectionTestResult.Ok -> {
                    _testState.value = AiTestState.Ok(result.modelEcho, result.latencyMillis)
                    // 测试通过即落盘，避免"测通了却没保存"
                    keyStore.save(
                        providerId = form.providerId,
                        baseUrl = form.baseUrl,
                        model = form.model,
                        apiKey = form.apiKeyInput.takeIf { it.isNotBlank() }
                    )
                    _form.update { it.copy(apiKeyInput = "") }
                }

                is AiConnectionTestResult.Failed -> {
                    _testState.value = AiTestState.Failed(result.failure)
                }
            }
        }
    }

    /** 清除 API Key（不动课表等本地数据）。 */
    fun clearKey() {
        viewModelScope.launch {
            keyStore.clearKey()
            _testState.value = AiTestState.Idle
            _form.update { it.copy(apiKeyInput = "") }
        }
    }

    /**
     * 重新检测工具调用能力。
     *
     * 清掉「实测不支持」的标记，下次对话会再带一次工具清单去试。
     * 用途：用户换了模型、或厂商升级了模型能力之后，不必重装 App。
     */
    fun recheckToolSupport() {
        viewModelScope.launch {
            keyStore.setToolUseUnsupported(false)
            _testState.value = AiTestState.Idle
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 校验
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 表单校验。
     *
     * @param hasSavedKey 是否已经保存过 Key。已保存过时允许留空（沿用旧的），
     *        否则用户每次改模型都会被要求重填一遍 Key。
     * @return 出错的提示资源 id；null = 通过
     */
    private fun validate(form: AiSettingsForm, hasSavedKey: Boolean): Int? {
        if (form.baseUrl.isBlank() || AiProviderPresets.buildChatCompletionsUrl(form.baseUrl) == null) {
            return R.string.ai_settings_base_url_required
        }
        if (form.model.isBlank()) {
            return R.string.ai_settings_model_required
        }
        if (form.apiKeyInput.isBlank() && !hasSavedKey) {
            return R.string.ai_settings_key_required
        }
        return null
    }
}
