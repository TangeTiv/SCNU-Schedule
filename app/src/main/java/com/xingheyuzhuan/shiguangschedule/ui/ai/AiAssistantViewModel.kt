package com.xingheyuzhuan.shiguangschedule.ui.ai

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.data.ai.AgentLoop
import com.xingheyuzhuan.shiguangschedule.data.ai.AiAgentEvent
import com.xingheyuzhuan.shiguangschedule.data.ai.AiChatMessage
import com.xingheyuzhuan.shiguangschedule.data.ai.AiFailure
import com.xingheyuzhuan.shiguangschedule.data.ai.AiKeyStore
import com.xingheyuzhuan.shiguangschedule.data.ai.AiRole
import com.xingheyuzhuan.shiguangschedule.data.ai.DegradeReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 一条工具调用的过程反馈（界面上显示「正在查询你的课表…」）。
 *
 * @param done 是否已结束；false 时显示进度指示
 * @param ok 仅 [done] 为 true 时有意义：查到了 / 没查到
 */
@Immutable
data class AiToolTraceItem(
    val id: Long,
    @StringRes val hintRes: Int,
    val done: Boolean = false,
    val ok: Boolean = false
)

/**
 * AI 对话页 ViewModel。
 *
 * ## 状态拆分（性能红线 5）
 *
 * 刻意**不用一个巨型 UiState**，而是各拆独立 `StateFlow`：
 *
 * | StateFlow | 变化频率 |
 * |---|---|
 * | [messages] | 每轮问答结束一次 |
 * | [streamingText] | **每个 token 一次**（最高频，必须独立，否则整页重组） |
 * | [toolTrace] | 每次工具调用两次 |
 * | [running] | 每轮问答两次 |
 * | [error] / [degraded] | 偶发 |
 *
 * 这样流式输出的每个 token 只会让「正在生成的那条气泡」重组，
 * 不会波及已经完成的历史消息、引导卡片与输入框。
 *
 * ## 会话只在内存里
 *
 * 已定（2026-09-23）：**不持久化对话历史**，退出页面即清空。
 * 因此不新增 Room 表与 Migration；代价是退出后回看不到历史。
 * 这也顺带避免了把成绩明细等问答内容落盘。
 */
@HiltViewModel
class AiAssistantViewModel @Inject constructor(
    private val agentLoop: AgentLoop,
    private val keyStore: AiKeyStore
) : ViewModel() {

    // ── 已完成的对话消息 ──

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    // ── 正在流式生成的内容（最高频，独立） ──

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // ── 工具调用过程反馈 ──

    private val _toolTrace = MutableStateFlow<List<AiToolTraceItem>>(emptyList())
    val toolTrace: StateFlow<List<AiToolTraceItem>> = _toolTrace.asStateFlow()

    // ── 运行状态 ──

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _error = MutableStateFlow<AiFailure?>(null)
    val error: StateFlow<AiFailure?> = _error.asStateFlow()

    private val _degraded = MutableStateFlow<DegradeReason?>(null)
    val degraded: StateFlow<DegradeReason?> = _degraded.asStateFlow()

    /** 是否还没有任何对话（用于显示引导卡片）。 */
    val showGreeting: StateFlow<Boolean> = _messages
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 首次隐私弹窗是否已同意。 */
    val privacyAccepted: StateFlow<Boolean> = keyStore.snapshot
        .map { it.privacyAccepted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** 是否已经配好 API Key（没配好时页面顶部显示引导）。 */
    val configured: StateFlow<Boolean> = keyStore.snapshot
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private var job: Job? = null
    private var nextMessageId = 0L
    private var nextTraceId = 0L

    // ═══════════════════════════════════════════════════════════════════════
    // 用户操作
    // ═══════════════════════════════════════════════════════════════════════

    /** 同意首次隐私弹窗（已定：同意后方可使用）。 */
    fun acceptPrivacy() {
        viewModelScope.launch { keyStore.setPrivacyAccepted(true) }
    }

    /** 开新对话：清空全部内存中的会话状态。 */
    fun newChat() {
        stop()
        _messages.value = emptyList()
        _streamingText.value = ""
        _toolTrace.value = emptyList()
        _error.value = null
        _degraded.value = null
    }

    fun consumeError() {
        _error.value = null
    }

    fun consumeDegraded() {
        _degraded.value = null
    }

    /** 停止当前生成。已生成的部分会保留下来，不会凭空消失。 */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * 发送一个问题。
     *
     * @param question 原始输入；空白输入直接忽略（界面也禁用发送按钮）
     */
    fun send(question: String) {
        val text = question.trim()
        if (text.isEmpty() || _running.value) return

        // 快照当前历史：本轮提问**之前**的消息才是"历史"，
        // 不能把本轮提问自己也塞进 history（否则会重复一遍）。
        val history = _messages.value

        _messages.update { it + AiChatMessage(id = nextMessageId++, role = AiRole.USER, text = text) }
        _streamingText.value = ""
        _toolTrace.value = emptyList()
        _error.value = null
        _degraded.value = null
        _running.value = true

        job = viewModelScope.launch {
            val buffer = StringBuilder()
            val noteRes = mutableListOf<Int>()

            try {
                agentLoop.run(text, history).collect { event ->
                    when (event) {
                        is AiAgentEvent.TextDelta -> {
                            buffer.append(event.text)
                            _streamingText.value = buffer.toString()
                        }

                        is AiAgentEvent.ToolStarted -> {
                            _toolTrace.update {
                                it + AiToolTraceItem(id = nextTraceId++, hintRes = event.hintRes)
                            }
                            // 记下"这条回答查过什么"，供回看时显示。
                            // 在**开始**时记录而不是结束时：结束事件里没有 hintRes，
                            // 而且用户中途点"停止"时，已经发生的查询也应该被记住。
                            if (noteRes.lastOrNull() != event.hintRes) noteRes += event.hintRes
                        }

                        is AiAgentEvent.ToolFinished -> {
                            _toolTrace.update { items ->
                                val index = items.indexOfLast { !it.done }
                                if (index < 0) {
                                    items
                                } else {
                                    items.toMutableList().also {
                                        it[index] = it[index].copy(done = true, ok = event.ok)
                                    }
                                }
                            }
                        }

                        is AiAgentEvent.Degraded -> _degraded.value = event.reason

                        is AiAgentEvent.Failed -> _error.value = event.failure

                        is AiAgentEvent.Completed -> Unit
                    }
                }
            } finally {
                // 无论是正常结束还是被用户停止，都把手里的内容落成一条消息 ——
                // 已经生成的字不能凭空消失。
                if (buffer.isNotBlank()) {
                    val answer = buffer.toString()
                    _messages.update {
                        it + AiChatMessage(
                            id = nextMessageId++,
                            role = AiRole.ASSISTANT,
                            text = answer,
                            toolNoteRes = noteRes.toList()
                        )
                    }
                }
                _streamingText.value = ""
                _toolTrace.value = emptyList()
                _running.value = false
                job = null
            }
        }
    }
}
