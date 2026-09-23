package com.xingheyuzhuan.shiguangschedule.data.ai

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ═══════════════════════════════════════════════════════════════════════════
// 领域模型（UI 与 AgentLoop 之间）
// ═══════════════════════════════════════════════════════════════════════════

/** 对话角色。只区分用户与助手 —— 工具消息是内部实现，不进 UI。 */
enum class AiRole { USER, ASSISTANT }

/**
 * 一条对话消息（UI 展示用）。
 *
 * @param id 自增序号，供 LazyColumn 稳定做 key
 * @param toolNoteRes 该条回答过程中展示过的工具提示（`@StringRes` 列表）。
 *        保留它是因为**过程反馈在流式结束后会消失**，用户回看时应该还能知道
 *        「这个答案是查了课表得出来的」，而不是凭空出现。
 */
@Immutable
data class AiChatMessage(
    val id: Long,
    val role: AiRole,
    val text: String,
    val toolNoteRes: List<Int> = emptyList(),
    /** 流式进行中标记：true 时 UI 显示光标/停止按钮 */
    val streaming: Boolean = false
)

/**
 * 运行期使用的厂商配置快照。
 *
 * ## 关于 [apiKey] 是 String
 *
 * 与教务密码不同，API Key 的输入源是 Compose 的 `TextField`（值只能是 `String`），
 * 因此**必然**存在一份不可擦除的 String 副本 —— 这一点不假装能避免。
 * 能控制的是：不落盘明文（Keystore 密文）、不进日志、不进 `toString()`。
 * 因此本类**刻意不覆写 `toString()`**，避免整对象被打进日志。
 *
 * @param supportsToolCalls 预设声明是否支持 Tool Calls；`自定义` 厂商为 false 表示**未知**，
 *        由运行期探测（见 [AiFailure.isToolUnsupported]）
 * @param supportsThinkingToggle 是否接受 DeepSeek 专有的 `thinking` 字段。
 *        非 DeepSeek 厂商必须为 false —— 未知字段可能被严格实现的服务端判 400
 */
@Immutable
data class AiProviderConfig(
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val supportsToolCalls: Boolean,
    val supportsThinkingToggle: Boolean
)

/** 一键测试连通性的结果。 */
sealed interface AiConnectionTestResult {
    /** 可用。[modelEcho] 是服务端回显的模型名，用于确认"填的模型真的存在" */
    data class Ok(val modelEcho: String?, val latencyMillis: Long) : AiConnectionTestResult

    /** 不可用。[failure] 由 UI 映射成本地化原因 */
    data class Failed(val failure: AiFailure) : AiConnectionTestResult
}

// ═══════════════════════════════════════════════════════════════════════════
// 失败类型
// ═══════════════════════════════════════════════════════════════════════════

/**
 * AI 调用的失败分类。
 *
 * ## 为什么不直接抛 Exception(message)
 *
 * 调用方（设置页的「测试连通性」、对话页的错误提示）需要给出**不同的处理建议**：
 * Key 无效要去改 Key、限流要等一会、网络要检查连接。
 * 压成字符串会让 UI 只能原样显示服务端的英文报错。
 *
 * ## 安全约束
 *
 * [detail] 一律经过 [sanitizeDetail] 清洗，**绝不允许包含 API Key**。
 */
sealed class AiFailure(message: String? = null) : Exception(message) {

    /** 脱敏后的原始信息，仅供排查；UI 优先显示本地化文案 */
    abstract val detail: String?

    /** 网络不可达 / DNS 失败 / 连接被拒 */
    class Network(override val detail: String?) : AiFailure(detail)

    /** 读超时（含流式响应中途无数据） */
    class Timeout(override val detail: String?) : AiFailure(detail)

    /** 401 / 403：Key 无效、过期、无权限 */
    class Unauthorized(override val detail: String?) : AiFailure(detail)

    /** 429：限流 */
    class RateLimited(override val detail: String?) : AiFailure(detail)

    /**
     * 402：余额不足。
     *
     * 单独成一类而不是并进 [RateLimited]：BYOK 下「余额用完了」是最常见的失败，
     * 用户需要的是"去充值"而不是"等一会再试"。
     */
    class InsufficientBalance(override val detail: String?) : AiFailure(detail)

    /** 400 / 422：请求本身有问题（模型名不存在、参数不被支持等） */
    class BadRequest(override val detail: String?) : AiFailure(detail)

    /** 5xx：服务端故障 */
    class Server(val status: Int, override val detail: String?) : AiFailure(detail)

    /** 响应不是预期的 JSON / SSE 结构 */
    class BadResponse(override val detail: String?) : AiFailure(detail)

    /** 本地没配 Key 或没填全（base_url / model） */
    class NotConfigured(override val detail: String?) : AiFailure(detail)

    /** 其它未分类异常 */
    class Unknown(override val detail: String?) : AiFailure(detail)
}

/**
 * 服务端是否在抱怨「不支持 tools / function」。
 *
 * 这是 §6.6 降级路径的**触发条件**：BYOK 下用户可能接了一个不支持 Tool Calls
 * 的第三方模型，OpenAI 兼容服务端通常会回 400 并在 message 里提到
 * `tools` / `function` / `tool_choice`。命中后 AgentLoop 会持久化标记并降级为
 * 「规则直查 + 摘要注入」。
 *
 * 之所以靠**探测**而不是靠预设声明：`自定义` 厂商填什么都可能，
 * 预设里无从知道，只能让它自己说话。
 */
val AiFailure.isToolUnsupported: Boolean
    get() {
        if (this !is AiFailure.BadRequest) return false
        val text = detail.orEmpty().lowercase()
        if (text.isEmpty()) return false
        return text.contains("tool") || text.contains("function")
    }

/**
 * 清洗服务端返回的文本，防止 API Key 被回显进 UI / 日志。
 *
 * 服务端**通常**不会回显 Authorization 头，但报错信息里带上 Key 片段并非不可能，
 * 且我们自己拼的 URL 里也可能被误带。因此统一把 [secret] 出现的位置替换成占位符。
 */
fun sanitizeDetail(raw: String?, secret: String? = null): String? {
    var text = raw?.takeIf { it.isNotBlank() } ?: return null
    if (!secret.isNullOrBlank()) {
        text = text.replace(secret, "***")
    }
    // 兜底：任何形如 sk-xxxxx 的串一律打码，即使它不是本次使用的 Key
    text = SK_PATTERN.replace(text) { match -> match.value.take(3) + "***" }
    return text.take(300)
}

private val SK_PATTERN = Regex("""sk-[A-Za-z0-9_\-]{6,}""")

// ═══════════════════════════════════════════════════════════════════════════
// 流式事件
// ═══════════════════════════════════════════════════════════════════════════

/** [AiProvider.streamChat] 吐出的事件。 */
sealed interface AiStreamEvent {
    /** 正文增量。UI 直接追加显示。 */
    data class TextDelta(val text: String) : AiStreamEvent

    /**
     * 本轮模型决定调用工具，参数已完整拼好。
     *
     * 只在流结束时发出**一次** —— 流式过程中 `tool_calls` 的 `arguments` 是按
     * 片段下发的，中途触发会拿到半截 JSON。
     */
    data class ToolCallsReady(val calls: List<AiToolCall>) : AiStreamEvent

    /** 本轮正常结束（无工具调用）。 */
    data object Finished : AiStreamEvent
}

/**
 * 模型请求调用的一次工具。
 *
 * [arguments] 是**原始 JSON 字符串**，由服务端生成，因此：
 * - 必须是"模型传什么就是什么"，绝不能直接当可信参数用
 * - 必须经 [LocalQueryTools] 的参数校验后才能执行
 */
@Immutable
data class AiToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

// ═══════════════════════════════════════════════════════════════════════════
// Provider 层的领域消息（**刻意不使用 OpenAI 的线上结构**）
//
// §9.1.1 要求「抽象 AiProvider 接口，不写死 OpenAI client」。
// 因此接口的入参只认下面这组领域类型，OpenAI 的 JSON 形态被封在
// OpenAiCompatibleProvider 内部 —— 将来接官方网关只需换一个实现，
// 不必改动 AgentLoop 与上下文构建。
// ═══════════════════════════════════════════════════════════════════════════

/** 交给模型的一条消息。 */
@Immutable
sealed interface AiProviderTurn {
    /** 系统提示词（含硬约束与本地数据摘要） */
    data class System(val text: String) : AiProviderTurn

    /** 用户提问 */
    data class User(val text: String) : AiProviderTurn

    /**
     * 助手的历史发言。
     *
     * [toolCalls] 非空表示这一轮它要调工具（此时 [text] 可能为 null）。
     */
    data class Assistant(
        val text: String?,
        val toolCalls: List<AiToolCall> = emptyList()
    ) : AiProviderTurn

    /** 工具执行结果回传。必须带上 [callId] 与发起调用一一对应，否则服务端会报错 */
    data class ToolResult(
        val callId: String,
        val name: String,
        val payload: String
    ) : AiProviderTurn
}

/**
 * 向模型声明一个可用工具。
 *
 * 这是 [AiToolCall] 的"声明侧"：只含模型选工具所需的三个字段。
 * 刻意不含 `group` / `loadingHintRes` 这些本地实现细节 —— 它们对模型无意义，
 * 塞进 prompt 只是白花钱。
 */
@Immutable
data class AiToolDeclaration(
    val name: String,
    val description: String,
    /** JSON Schema 对象 */
    val parametersSchema: JsonObject
)

// ═══════════════════════════════════════════════════════════════════════════
// OpenAI 兼容协议的线上模型（wire DTO）
//
// 统一走 /v1/chat/completions，DeepSeek 与「自定义」共用这一套。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 请求体。
 *
 * 所有可选字段默认 null，配合 `Json { explicitNulls = false }` 达到
 * 「不填就不发」的效果 —— 这很重要：给不支持某字段的服务端发 null 也可能报错。
 */
@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val stream: Boolean = true,
    val tools: List<ApiToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    /**
     * 刻意**不发** `max_tokens`。
     *
     * 理由：部分第三方 OpenAI 兼容服务端对 `max_tokens` 的命名与取值有额外要求
     * （如只认 `max_completion_tokens`），发它会把「一键测试连通性」变成假失败。
     * 本功能不需要截断（回答本身很短），因此以最大兼容性为先。
     */
    val temperature: Double? = null,
    /**
     * DeepSeek 专有的思考模式开关，形如 `{"type": "disabled"}`。
     *
     * **必须显式关闭**，理由有两条（2026-09-23 核实官方文档）：
     * 1. 官方默认**开启**思考模式且 effort=high，课表问答用不上，白白增加延迟与输出 token
     *    （输出 token 比输入贵 4 倍）；
     * 2. 更关键：带 `tools` 时官方要求把每轮的 `reasoning_content` **完整回传**，
     *    否则直接 400。我们刻意不回传该字段，因此必须把思考模式关掉，
     *    从源头上不产生 `reasoning_content`。
     */
    val thinking: ThinkingToggle? = null
)

/** DeepSeek 思考模式开关。 */
@Serializable
internal data class ThinkingToggle(val type: String) {
    companion object {
        val DISABLED = ThinkingToggle(type = "disabled")
    }
}

/**
 * 一条线上消息。
 *
 * 四种形态共用本类：
 * - system / user：只用 [content]
 * - assistant（要调工具）：用 [toolCalls]，[content] 可能为空
 * - tool（回传工具结果）：用 [toolCallId] + [content]
 */
@Serializable
internal data class ApiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ApiToolCallPayload>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
) {
    companion object {
        fun system(text: String) = ApiMessage(role = "system", content = text)
        fun user(text: String) = ApiMessage(role = "user", content = text)
        fun assistant(text: String) = ApiMessage(role = "assistant", content = text)

        fun assistantWithToolCalls(text: String?, calls: List<ApiToolCallPayload>) =
            ApiMessage(role = "assistant", content = text, toolCalls = calls)

        fun tool(callId: String, payload: String) =
            ApiMessage(role = "tool", content = payload, toolCallId = callId)
    }
}

/** 工具声明。 */
@Serializable
internal data class ApiToolDefinition(
    val type: String = "function",
    val function: ApiFunctionDefinition
)

@Serializable
internal data class ApiFunctionDefinition(
    val name: String,
    val description: String,
    /** JSON Schema。用 [JsonObject] 直接承载，避免为每种参数写一个 DTO。 */
    val parameters: JsonObject
)

/** assistant 消息里回传的工具调用。 */
@Serializable
internal data class ApiToolCallPayload(
    val id: String,
    val type: String = "function",
    val function: ApiFunctionCallPayload
)

@Serializable
internal data class ApiFunctionCallPayload(
    val name: String,
    /** 原始 JSON 字符串，必须原样回传 */
    val arguments: String
)

/** 非流式响应（连通性测试用）。 */
@Serializable
internal data class ChatCompletionResponse(
    val model: String? = null,
    val choices: List<CompletionChoice> = emptyList()
)

@Serializable
internal data class CompletionChoice(
    val index: Int = 0,
    val message: ApiMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/** 流式响应块。 */
@Serializable
internal data class ChatCompletionChunk(
    val model: String? = null,
    val choices: List<ChunkChoice> = emptyList()
)

@Serializable
internal data class ChunkChoice(
    val index: Int = 0,
    val delta: ChunkDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

/**
 * 流式增量。
 *
 * [reasoningContent] 只用于**识别**（若它非空说明思考模式没关掉），
 * 绝不回传、也不上屏。
 */
@Serializable
internal data class ChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChunkToolCall>? = null
)

/**
 * 流式工具调用片段。
 *
 * 关键点：`index` 是**分组依据**，`id` 与 `function.name` 只在第一片出现，
 * `arguments` 会分多片到达。因此必须按 [index] 累积拼接，
 * 不能每片当一次调用（否则一个工具会被当成好几个）。
 */
@Serializable
internal data class ChunkToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: ChunkFunctionCall? = null
)

@Serializable
internal data class ChunkFunctionCall(
    val name: String? = null,
    val arguments: String? = null
)

/** 错误响应体（OpenAI 兼容服务端通用结构）。 */
@Serializable
internal data class ApiErrorEnvelope(
    val error: ApiErrorBody? = null,
    val message: String? = null
)

@Serializable
internal data class ApiErrorBody(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)
