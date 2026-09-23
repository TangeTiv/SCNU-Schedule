package com.xingheyuzhuan.shiguangschedule.data.ai

import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.TreeMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * LLM 厂商的抽象接口。
 *
 * ## 为什么必须抽象（方案文档 §9.1.1 第 5 条）
 *
 * 本版只实现 OpenAI 兼容协议，但**接口不接受任何 OpenAI 专有类型** ——
 * 入参是 [AiProviderTurn] / [AiToolDeclaration] 这组领域模型。
 * 这样将来接「官方 AI 网关」时只需换一个实现，`AgentLoop` 与上下文构建一行都不用改。
 *
 * ## 安全约束
 *
 * - [AiProviderConfig.apiKey] 只用于 `Authorization` 头，**绝不进日志**。
 * - 实现方**不得**复用 `@Named("scnu")` 的 OkHttpClient：那个 client 信任所有证书、
 *   不校验主机名（为兼容校园自签证书），用它调 LLM 等于关掉证书校验，
 *   用户的 API Key 会在可被中间人劫持的信道上传输。
 */
interface AiProvider {

    /** 实现标识，便于排查（如 `openai-compatible`）。 */
    val id: String

    /**
     * 一键测试连通性。
     *
     * 设计要点：**不抛异常**，把失败也作为一种结果返回 —— 调用方（设置页）
     * 要的是"可用 / 不可用 + 原因"，不是一堆 try/catch。
     */
    suspend fun testConnection(config: AiProviderConfig): AiConnectionTestResult

    /**
     * 发起一次流式对话。
     *
     * @param turns 完整的对话上下文（含 system 提示词与历史工具往返）
     * @param tools 本轮允许模型调用的工具；**空列表表示走降级路径**（不发 tools 字段）
     * @return 增量事件流；失败以 [AiFailure] 形式抛出
     */
    fun streamChat(
        config: AiProviderConfig,
        turns: List<AiProviderTurn>,
        tools: List<AiToolDeclaration>
    ): Flow<AiStreamEvent>
}

/**
 * OpenAI 兼容实现（`POST {base_url}/v1/chat/completions`）。
 *
 * DeepSeek 与「自定义」厂商都走这里。
 *
 * ## 为什么手写 SSE 解析而不用 Ktor 的 SSE 插件
 *
 * OpenAI 的流式格式就是「每行一个 `data: {...}`，以 `data: [DONE]` 收尾」，
 * 解析它只需要按行读 + 一次 JSON 反序列化。手写的好处是完全可控：
 * 插件版本升级不会改变我们的行为，错误响应的处理也不必和插件博弈。
 * 依赖的 `readLine()` 来自 ktor-io，是 Ktor 3.5 的正式 API
 * （`readUTF8Line` 已弃用，故不用）。
 *
 * ## 关于 [channelFlow] 而不是 [kotlinx.coroutines.flow.flow]
 *
 * `HttpStatement.execute {}` 的实现在 JVM 上默认**不切换**调度器，
 * 但 Ktor 保留了通过系统属性 `io.ktor.client.statement.useEngineDispatcher`
 * 切到引擎调度器的能力（那会新建一个 Job）。用 `flow {}` 的话，
 * 一旦该属性被打开就会触发 "Flow invariant is violated"。
 * `channelFlow` 允许从子协程发送事件，因此不受影响。
 */
@Singleton
class OpenAiCompatibleProvider @Inject constructor(
    @Named("ai") private val client: HttpClient,
    @Named("ai") private val json: Json
) : AiProvider {

    override val id: String = "openai-compatible"

    companion object {
        /** 连通性测试的提示语。刻意要求短回复，避免测试本身烧掉一堆 token。 */
        private const val PING_PROMPT = "ping"

        /** SSE 数据行前缀 */
        private const val DATA_PREFIX = "data:"

        /** OpenAI 流结束哨兵 */
        private const val DONE_SENTINEL = "[DONE]"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 连通性测试
    // ═══════════════════════════════════════════════════════════════════════

    override suspend fun testConnection(config: AiProviderConfig): AiConnectionTestResult {
        val url = AiProviderPresets.buildChatCompletionsUrl(config.baseUrl)
            ?: return fail(AiFailure.NotConfigured("base_url"))
        if (config.apiKey.isBlank()) return fail(AiFailure.NotConfigured("api_key"))
        if (config.model.isBlank()) return fail(AiFailure.NotConfigured("model"))

        val request = ChatCompletionRequest(
            model = config.model,
            messages = listOf(ApiMessage.user(PING_PROMPT)),
            stream = false,
            thinking = thinkingOrNull(config)
        )

        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val response = client.preparePost(url) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(json.encodeToString(ChatCompletionRequest.serializer(), request))
            }.execute()

            val raw = response.bodyAsText()
            if (!response.status.isSuccess()) {
                return fail(mapHttpFailure(response.status.value, raw, config.apiKey))
            }

            val parsed = runCatching {
                json.decodeFromString(ChatCompletionResponse.serializer(), raw)
            }.getOrNull()
                ?: return fail(
                    AiFailure.BadResponse(
                        sanitizeDetail("响应不是预期的 JSON 结构", config.apiKey)
                    )
                )

            AiConnectionTestResult.Ok(
                modelEcho = parsed.model,
                latencyMillis = SystemClock.elapsedRealtime() - startedAt
            )
        } catch (e: Throwable) {
            fail(mapException(e, config.apiKey))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 流式对话
    // ═══════════════════════════════════════════════════════════════════════

    override fun streamChat(
        config: AiProviderConfig,
        turns: List<AiProviderTurn>,
        tools: List<AiToolDeclaration>
    ): Flow<AiStreamEvent> = channelFlow {
        val url = AiProviderPresets.buildChatCompletionsUrl(config.baseUrl)
            ?: throw AiFailure.NotConfigured("base_url")
        if (config.apiKey.isBlank()) throw AiFailure.NotConfigured("api_key")
        if (config.model.isBlank()) throw AiFailure.NotConfigured("model")

        val request = ChatCompletionRequest(
            model = config.model,
            messages = turns.map { it.toApiMessage() },
            stream = true,
            // 空列表 → 不发 tools 字段。降级路径（规则直查 + 摘要注入）走的就是这里。
            tools = tools.takeIf { it.isNotEmpty() }?.map { it.toApiDefinition() },
            thinking = thinkingOrNull(config)
        )
        val payload = json.encodeToString(ChatCompletionRequest.serializer(), request)

        // 按 index 累积工具调用片段。id 与 name 只在第一片出现，arguments 分多片到达。
        val toolCallAccumulator = TreeMap<Int, ToolCallAccumulator>()

        try {
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                setBody(payload)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val raw = response.bodyAsText()
                    throw mapHttpFailure(response.status.value, raw, config.apiKey)
                }

                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readLine() ?: break
                    if (line.isEmpty() || !line.startsWith(DATA_PREFIX)) continue

                    val data = line.removePrefix(DATA_PREFIX).trim()
                    if (data.isEmpty()) continue
                    if (data == DONE_SENTINEL) break

                    val chunk = runCatching {
                        json.decodeFromString(ChatCompletionChunk.serializer(), data)
                    }.getOrNull() ?: continue

                    val choice = chunk.choices.firstOrNull() ?: continue

                    choice.delta?.content?.takeIf { it.isNotEmpty() }?.let {
                        send(AiStreamEvent.TextDelta(it))
                    }
                    choice.delta?.toolCalls?.forEach { piece ->
                        toolCallAccumulator.getOrPut(piece.index) { ToolCallAccumulator() }
                            .append(piece)
                    }
                }
            }

            val calls = toolCallAccumulator.values.mapNotNull { it.build() }
            if (calls.isNotEmpty()) {
                send(AiStreamEvent.ToolCallsReady(calls))
            } else {
                send(AiStreamEvent.Finished)
            }
        } catch (e: Throwable) {
            throw mapException(e, config.apiKey)
        }
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════════════════
    // 领域模型 → 线上模型
    // ═══════════════════════════════════════════════════════════════════════

    private fun AiProviderTurn.toApiMessage(): ApiMessage = when (this) {
        is AiProviderTurn.System -> ApiMessage.system(text)
        is AiProviderTurn.User -> ApiMessage.user(text)
        is AiProviderTurn.Assistant ->
            if (toolCalls.isEmpty()) {
                ApiMessage.assistant(text.orEmpty())
            } else {
                ApiMessage.assistantWithToolCalls(
                    // 用空串而不是 null：带上 tools 时 assistant 消息的 content
                    // 允许为空，但**省略该字段**在部分 OpenAI 兼容服务端会被判 400。
                    // 空串是两边都接受的最稳形态。
                    text = text.orEmpty(),
                    calls = toolCalls.map { call ->
                        ApiToolCallPayload(
                            id = call.id,
                            function = ApiFunctionCallPayload(
                                name = call.name,
                                arguments = call.arguments
                            )
                        )
                    }
                )
            }

        is AiProviderTurn.ToolResult -> ApiMessage.tool(callId, payload)
    }

    private fun AiToolDeclaration.toApiDefinition(): ApiToolDefinition = ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = name,
            description = description,
            parameters = parametersSchema
        )
    )

    /**
     * 只在厂商支持时才带 `thinking` 字段。
     *
     * 非 DeepSeek 的「自定义」厂商拿到的 `supportsThinkingToggle` 为 false ——
     * 未知字段可能被严格实现的服务端判 400，不能想当然地发。
     */
    private fun thinkingOrNull(config: AiProviderConfig): ThinkingToggle? =
        if (config.supportsThinkingToggle) ThinkingToggle.DISABLED else null

    // ═══════════════════════════════════════════════════════════════════════
    // 错误映射
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * HTTP 状态码 → [AiFailure]。
     *
     * 状态码语义按 DeepSeek 官方错误码表映射（400 / 401 / 402 / 422 / 429 / 500 / 503），
     * 这套语义在 OpenAI 兼容生态里是通用的。
     */
    private fun mapHttpFailure(status: Int, raw: String, secret: String?): AiFailure {
        val detail = sanitizeDetail(extractErrorMessage(raw), secret)
        return when (status) {
            400, 422 -> AiFailure.BadRequest(detail)
            401, 403 -> AiFailure.Unauthorized(detail)
            402 -> AiFailure.InsufficientBalance(detail)
            429 -> AiFailure.RateLimited(detail)
            in 500..599 -> AiFailure.Server(status, detail)
            else -> AiFailure.Unknown(detail ?: "HTTP $status")
        }
    }

    /**
     * 异常 → [AiFailure]。
     *
     * [AiFailure] 原样透传，避免把自己抛的失败又包一层。
     */
    private fun mapException(e: Throwable, secret: String?): AiFailure = when (e) {
        is AiFailure -> e
        is SocketTimeoutException -> AiFailure.Timeout(sanitizeDetail(e.message, secret))
        is ConnectException, is UnknownHostException ->
            AiFailure.Network(sanitizeDetail(e.message, secret))

        is IOException -> AiFailure.Network(sanitizeDetail(e.message, secret))
        else -> AiFailure.Unknown(sanitizeDetail(e.message ?: e.javaClass.simpleName, secret))
    }

    /** 从错误响应体里取出人类可读的信息；取不到就返回截断的原文。 */
    private fun extractErrorMessage(raw: String): String? {
        if (raw.isBlank()) return null
        val envelope = runCatching {
            json.decodeFromString(ApiErrorEnvelope.serializer(), raw)
        }.getOrNull()
        return envelope?.error?.message
            ?: envelope?.message
            ?: raw.take(200)
    }

    private fun fail(failure: AiFailure) = AiConnectionTestResult.Failed(failure)
}

/**
 * 按 `index` 累积流式下发的工具调用片段。
 *
 * OpenAI 的流式协议里，一次工具调用会被切成多片：
 * 第一片带 `id` 与 `function.name`，后续片只带 `function.arguments` 的增量。
 * 若不做累积，一个工具会被当成好几个、且参数是半截 JSON。
 */
private class ToolCallAccumulator {
    private var id: String? = null
    private var name: String? = null
    private val arguments = StringBuilder()

    fun append(piece: ChunkToolCall) {
        piece.id?.takeIf { it.isNotBlank() }?.let { id = it }
        piece.function?.name?.takeIf { it.isNotBlank() }?.let { name = it }
        piece.function?.arguments?.let { arguments.append(it) }
    }

    /**
     * @return 名字与 id 都齐了才算一次有效调用；参数缺失时补成空对象，
     *         让工具侧的参数校验去报错（而不是在这里静默丢弃）。
     */
    fun build(): AiToolCall? {
        val callId = id ?: return null
        val functionName = name ?: return null
        val args = arguments.toString().takeIf { it.isNotBlank() } ?: "{}"
        return AiToolCall(id = callId, name = functionName, arguments = args)
    }
}
