package com.xingheyuzhuan.shiguangschedule.data.ai

import androidx.annotation.StringRes
import com.xingheyuzhuan.shiguangschedule.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════
// Agent 循环：工具调用 + 轮数上限 + 降级
//
// 方案文档 §9.2「Agent 循环与本地工具集」与 §6.6「Agent 循环与降级」。
//
// 流程：
//
//   用户提问 → 规则路由（决定工具组 + 是否需要预取）
//            → 组装 system（分层上下文）
//            → LLM（带工具清单）→ 需要工具？
//                                  ├ 是 → 本地执行（读 Room）→ 结果回传 → 再决策
//                                  └ 否 → 直接生成回答
// ═══════════════════════════════════════════════════════════════════════════

/** Agent 运行期吐给 UI 的事件。 */
sealed interface AiAgentEvent {

    /**
     * 工具开始执行。
     *
     * **UX 硬要求**（§9.2 坑 3）：工具调用期间必须显示「正在查询你的课表…」。
     * 3 轮工具 = 4 次 API 请求，等待明显更长，不给反馈用户会以为卡死。
     */
    data class ToolStarted(@StringRes val hintRes: Int, val toolName: String) : AiAgentEvent

    /** 工具执行结束。[ok] 为 false 时界面应把该条过程反馈标成"未查到"。 */
    data class ToolFinished(val toolName: String, val ok: Boolean) : AiAgentEvent

    /** 正文增量，UI 直接追加显示（流式输出的来源）。 */
    data class TextDelta(val text: String) : AiAgentEvent

    /** 本轮回答结束。[toolRounds] 是实际发生的工具轮数，便于排查"为什么这么慢"。 */
    data class Completed(val toolRounds: Int) : AiAgentEvent

    /** 失败。UI 负责把 [failure] 映射成本地化文案。 */
    data class Failed(val failure: AiFailure) : AiAgentEvent

    /** 进入了降级模式（规则直查 + 摘要注入）。 */
    data class Degraded(val reason: DegradeReason) : AiAgentEvent
}

/** 降级原因。 */
enum class DegradeReason {
    /**
     * 当前意图对应的工具组里**一个工具都没有**。
     *
     * 本版只有教务组有实现，因此命中"二手集市 / 美食街"时会走这里 ——
     * 界面应如实提示「该模块的 AI 查询尚未开放」，而不是让模型编答案。
     */
    NO_TOOL_FOR_INTENT,

    /**
     * 模型不支持工具调用。
     *
     * 探测方式：服务端回 400 且报错里提到 tools/function。
     * 一经确认就持久化（`ai_tool_use_unsupported`），不再每轮都拿一次 400 去试。
     */
    MODEL_WITHOUT_TOOL_SUPPORT
}

/**
 * 工具调用循环。
 *
 * ## 轮数上限 = 5（§6.6 已定）
 *
 * 防死循环烧用户的钱。实现上有个细节：**第 5 轮不发送工具清单**，
 * 于是模型这一轮无论如何都必须给出最终答案。
 * 这比"第 5 轮之后额外再来一次不带工具的收尾调用"更好 ——
 * 总请求数硬上限就是 5，不会出现第 6 次。
 *
 * ## 降级路径
 *
 * | 触发条件 | 行为 |
 * |---|---|
 * | 该意图的工具组为空 | 不发 `tools` 字段，只靠基础层摘要回答 + 界面提示模块未开放 |
 * | 服务端报"不支持 tools" | 持久化标记，**重建消息（去掉工具往返）**后重跑一次，只靠摘要回答 |
 *
 * > 注：DeepSeek 两个模型都支持 Tool Calls（§9.1.1 已核实），
 * > 因此降级路径**实际只对「自定义」接入的第三方模型生效**，属安全网而非常见路径。
 */
@Singleton
class AgentLoop @Inject constructor(
    private val provider: AiProvider,
    private val keyStore: AiKeyStore,
    private val router: IntentRouter,
    private val tools: LocalQueryTools,
    private val contextBuilder: AiContextBuilder,
    private val aggregator: LocalDataAggregator
) {

    companion object {
        /**
         * 最大工具调用轮数。
         *
         * §6.6 已定为 5，不随实现调整。
         */
        const val MAX_TOOL_ROUNDS = 5

        /**
         * 回传的历史轮数上限。
         *
         * §6.9 要求「上下文轮数限制 6–10 轮，不无限累积（越聊越慢越贵）」，
         * 这里取区间中值 8 轮（= 最多 16 条消息）。
         */
        const val MAX_HISTORY_TURNS = 8

        /** 预取那次工具调用用的固定 id 前缀，便于排查。 */
        private const val PREFETCH_CALL_ID = "prefetch_call_0"
    }

    /**
     * 跑一轮问答。
     *
     * @param question 用户本次的提问
     * @param history 本次提问**之前**的对话（UI 维护，仅内存）
     */
    fun run(
        question: String,
        history: List<AiChatMessage>
    ): Flow<AiAgentEvent> = channelFlow {

        val config = keyStore.loadRequestConfig()
        if (config == null) {
            send(AiAgentEvent.Failed(AiFailure.NotConfigured("missing provider config")))
            return@channelFlow
        }

        val today = LocalDate.now()
        // 本周起始日按课表设置的 firstDayOfWeek 对齐；取不到就退回"今天"，
        // 此时日期解析会退化成相对今天计算，仍能给出可用答案。
        val weekStart = runCatching { aggregator.weekStartOf(today) }.getOrNull() ?: today
        val decision = router.route(question, today, weekStart)

        val specs = tools.specsFor(decision.groups)
        val declarations = tools.declarationsFor(decision.groups)

        // 先声明降级（如果有），让界面能立刻给出提示
        val declaredDegrade: DegradeReason? = when {
            !config.supportsToolCalls -> DegradeReason.MODEL_WITHOUT_TOOL_SUPPORT
            specs.isEmpty() -> DegradeReason.NO_TOOL_FOR_INTENT
            else -> null
        }
        declaredDegrade?.let { send(AiAgentEvent.Degraded(it)) }

        val systemPrompt = try {
            contextBuilder.buildSystemPrompt(today)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            send(AiAgentEvent.Failed(AiFailure.Unknown("context build failed")))
            return@channelFlow
        }

        // ── 规则前置：本地先把数据查好 ──
        val prefetch = decision.prefetch?.let { p -> executePrefetch(p) }

        // ── 尝试带工具跑；若服务端说不支持，则降级重跑一次 ──
        var activeDeclarations = if (declaredDegrade == null) declarations else emptyList()
        var toolRounds = 0

        while (true) {
            val messages = buildMessages(
                systemPrompt = systemPrompt,
                question = question,
                history = history,
                prefetch = prefetch,
                seedPrefetchAsToolCall = activeDeclarations.isNotEmpty()
            )

            val outcome = runOnce(
                config = config,
                initialMessages = messages,
                declarations = activeDeclarations,
                toolRoundsSoFar = toolRounds,
                emitEvent = { event -> send(event) }
            )

            when (outcome) {
                is RunOutcome.Done -> {
                    toolRounds = outcome.toolRounds
                    send(AiAgentEvent.Completed(outcome.toolRounds))
                    return@channelFlow
                }

                is RunOutcome.Failed -> {
                    val failure = outcome.failure
                    // 服务端明确说"不支持 tools" → 持久化 + 降级重跑（只重跑一次）
                    if (failure.isToolUnsupported && activeDeclarations.isNotEmpty()) {
                        runCatching { keyStore.setToolUseUnsupported(true) }
                        send(AiAgentEvent.Degraded(DegradeReason.MODEL_WITHOUT_TOOL_SUPPORT))
                        activeDeclarations = emptyList()
                        toolRounds = 0
                        continue
                    }
                    send(AiAgentEvent.Failed(failure))
                    return@channelFlow
                }
            }
        }
    }
        // 流式事件量大（每个 token 一个 TextDelta）。给一个**有限但绝不丢数据**的缓冲：
        // 溢出时挂起上游而不是丢弃 —— 丢一个 TextDelta 就等于回答里少几个字。
        .buffer(capacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        .flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════════════
    // 单次运行（可被降级路径重跑）
    // ═══════════════════════════════════════════════════════════════════════

    private sealed interface RunOutcome {
        data class Done(val toolRounds: Int) : RunOutcome
        data class Failed(val failure: AiFailure) : RunOutcome
    }

    /**
     * 跑完一整轮对话（含若干次工具往返）。
     *
     * @param toolRoundsSoFar 已经用掉的工具轮数（降级重跑时会清零）
     */
    private suspend fun runOnce(
        config: AiProviderConfig,
        initialMessages: List<AiProviderTurn>,
        declarations: List<AiToolDeclaration>,
        toolRoundsSoFar: Int,
        emitEvent: suspend (AiAgentEvent) -> Unit
    ): RunOutcome {
        val messages = initialMessages.toMutableList()
        var toolRounds = toolRoundsSoFar

        try {
            while (toolRounds < MAX_TOOL_ROUNDS) {
                val nextRound = toolRounds + 1
                // 最后一轮不发工具清单 → 模型必须给出最终答案，
                // 于是总请求数硬上限就是 MAX_TOOL_ROUNDS 次。
                val roundDeclarations = if (nextRound == MAX_TOOL_ROUNDS) emptyList() else declarations

                val requested = mutableListOf<AiToolCall>()
                provider.streamChat(config, messages, roundDeclarations).collect { event ->
                    when (event) {
                        is AiStreamEvent.TextDelta -> emitEvent(AiAgentEvent.TextDelta(event.text))
                        is AiStreamEvent.ToolCallsReady -> requested += event.calls
                        AiStreamEvent.Finished -> Unit
                    }
                }

                toolRounds = nextRound
                if (requested.isEmpty()) return RunOutcome.Done(toolRounds)

                // 把模型的工具意图与本地执行结果都写回消息列表，供下一轮使用
                messages += AiProviderTurn.Assistant(text = null, toolCalls = requested)
                requested.forEach { call ->
                    val spec = tools.specOf(call.name)
                    emitEvent(
                        AiAgentEvent.ToolStarted(
                            hintRes = spec?.loadingHintRes ?: R.string.ai_tool_loading_generic,
                            toolName = call.name
                        )
                    )
                    val result = tools.execute(call)
                    emitEvent(AiAgentEvent.ToolFinished(call.name, result.ok))
                    messages += AiProviderTurn.ToolResult(
                        callId = call.id,
                        name = call.name,
                        payload = result.payload
                    )
                }
            }
            // 理论上到不了这里（最后一轮不带工具，requested 必为空）
            return RunOutcome.Done(toolRounds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiFailure) {
            return RunOutcome.Failed(e)
        } catch (e: Throwable) {
            return RunOutcome.Failed(AiFailure.Unknown(e.javaClass.simpleName))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 规则前置预取
    // ═══════════════════════════════════════════════════════════════════════

    /** 预取结果（已本地执行完毕的一次工具调用）。 */
    private data class PrefetchResult(
        val toolName: String,
        val arguments: String,
        val payload: String,
        val ok: Boolean
    )

    /**
     * 执行规则前置的预取。
     *
     * 与"让模型自己调工具"相比，这里省掉的是**整整一次 API 往返**：
     * 本地已经知道要查什么（如"明天有什么课" → 查 2026-09-24 那一天），
     * 没必要先花一次请求让模型把这个日期算出来。
     */
    private suspend fun executePrefetch(prefetch: Prefetch): PrefetchResult {
        val arguments = prefetch.arguments.toString()
        val result = tools.execute(
            AiToolCall(id = PREFETCH_CALL_ID, name = prefetch.toolName, arguments = arguments)
        )
        return PrefetchResult(
            toolName = prefetch.toolName,
            arguments = arguments,
            payload = result.payload,
            ok = result.ok
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 消息组装
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 组装请求消息。
     *
     * ## 为什么预取有两种形态
     *
     * - **带工具时**：把预取伪装成"一次已经发生过的工具调用"
     *   （assistant 的 tool_calls + tool 的结果）。这样模型看到的是
     *   「我自己查过了，结果如下」，语义最自然，也能接着再调别的工具。
     * - **降级时**（不带工具）：此时消息里**不能出现** tool 角色的消息 ——
     *   没有 `tools` 字段的请求带 tool 消息，多数服务端会直接判 400。
     *   因此改为把结果作为文本附在用户提问后面。
     *
     * @param seedPrefetchAsToolCall true = 用工具往返形态；false = 用文本附注形态
     */
    private fun buildMessages(
        systemPrompt: String,
        question: String,
        history: List<AiChatMessage>,
        prefetch: PrefetchResult?,
        seedPrefetchAsToolCall: Boolean
    ): List<AiProviderTurn> {
        val messages = mutableListOf<AiProviderTurn>()
        messages += AiProviderTurn.System(systemPrompt)

        // 历史：只保留最近 MAX_HISTORY_TURNS 轮；空消息（如失败的那一轮）直接丢掉，
        // 否则会往上下文里塞一堆空 assistant 消息，既费 token 又干扰模型。
        history.filter { it.text.isNotBlank() }
            .takeLast(MAX_HISTORY_TURNS * 2)
            .forEach { message ->
                messages += when (message.role) {
                    AiRole.USER -> AiProviderTurn.User(message.text)
                    AiRole.ASSISTANT -> AiProviderTurn.Assistant(message.text)
                }
            }

        if (prefetch == null || seedPrefetchAsToolCall) {
            messages += AiProviderTurn.User(question)
        } else {
            messages += AiProviderTurn.User(
                buildString {
                    appendLine("【本次已在本地查好的数据（无需再调用工具）】")
                    appendLine(prefetch.payload)
                    appendLine()
                    appendLine("【用户问题】")
                    append(question)
                }
            )
        }

        if (prefetch != null && seedPrefetchAsToolCall) {
            val call = AiToolCall(
                id = PREFETCH_CALL_ID,
                name = prefetch.toolName,
                arguments = prefetch.arguments
            )
            messages += AiProviderTurn.Assistant(text = null, toolCalls = listOf(call))
            messages += AiProviderTurn.ToolResult(
                callId = call.id,
                name = prefetch.toolName,
                payload = prefetch.payload
            )
        }

        return messages
    }
}
