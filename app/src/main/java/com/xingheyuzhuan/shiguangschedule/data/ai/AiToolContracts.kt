package com.xingheyuzhuan.shiguangschedule.data.ai

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// ═══════════════════════════════════════════════════════════════════════════
// 工具的契约层
//
// 对应方案文档 §9.6「必须预留的 3 条」：
//
// | 预留项 | 在本文件的体现 |
// |---|---|
// | 工具定义成 `suspend fun` + 统一结果包装 | [ToolHandler.execute] 是 suspend，返回 [ToolResult]（成功 / 失败 / 超时） |
// | 工具按来源分组（ToolGroup） | [ToolGroup]，配合 `specsFor(groups)` 只加载命中的一组 |
// | ToolSpec（声明）与 ToolHandler（实现）分离 | [ToolSpec] 只描述"是什么"，[ToolHandler] 只负责"怎么做"，由 [LocalQueryTools] 按名字配对 |
//
// **明确不预留**（§9.6）：二手集市 / 美食街的具体工具签名 —— 那两个模块尚未设计，
// 参数（筛选维度、返回字段）几乎必然要改，现在写就是白写。
// 但分组枚举与"只加载一组"的机制**现在就完整实现**，将来只需加一对 Spec+Handler。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 工具来源分组。
 *
 * ## 为什么需要分组（§9.2 入口形态）
 *
 * AI 入口在【校园】页，用户可能问任何模块的事。若把全部模块的工具都塞进 prompt：
 * 1. **prompt 变长** —— 工具清单每次请求都要发，是持续性成本；
 * 2. **模型选择准确率下降** —— 工具越多越容易选错。
 *
 * 因此按意图路由只加载命中的组，**工具数始终 ≤ 6**，不随模块数量线性膨胀。
 */
enum class ToolGroup {
    /** 教务组：课表 / 考试 / 成绩 / 学分 / 培养计划。本版**唯一有工具实现**的组。 */
    ACADEMIC,

    /** 二手集市组。**预留**：本版 0 个工具，等 P4（需 P1 后端 + P2 评价先落地）。 */
    MARKETPLACE,

    /** 美食街组。**预留**：本版 0 个工具。 */
    FOOD
}

/**
 * 工具声明。
 *
 * ## `description` 的写法很关键
 *
 * 方案文档 §9.2 的经验：**description 直接决定模型选得对不对**。
 * 最有效的写法是在描述里写清「用户会怎么问」，例如
 * 「当用户问"明天有什么课""下周课表""这周三下午有课吗"时调用」——
 * 远比写"获取课程数据"准确。本文件的 5 个描述都按此写法。
 *
 * @param loadingHintRes 工具执行期间界面显示的提示（如「正在查询你的课表…」）。
 *        这是**UX 硬要求**（§9.2 坑 3）：3 轮工具 = 4 次 API 请求，等待明显更长，
 *        不给反馈用户会以为卡死。放在 Spec 里而不是 Handler 里，
 *        因为它是"给用户看的一句话"，与实现无关。
 */
@Immutable
data class ToolSpec(
    val name: String,
    val description: String,
    val group: ToolGroup,
    val parametersSchema: JsonObject,
    @StringRes val loadingHintRes: Int
) {
    /**
     * 转成给模型看的声明。
     *
     * 只保留模型需要的三个字段 —— `group` 与 `loadingHintRes` 是本地实现细节，
     * 塞进 prompt 只是白花钱。
     */
    fun toDeclaration(): AiToolDeclaration = AiToolDeclaration(
        name = name,
        description = description,
        parametersSchema = parametersSchema
    )
}

/** 工具失败的原因分类。 */
enum class ToolFailureKind {
    /** 参数不合法（日期格式错、课程名过长等）—— 模型可以据此自我纠正 */
    INVALID_ARGUMENT,

    /** 本地没有这项数据（用户还没同步），或该模块尚未开放 */
    NOT_AVAILABLE,

    /** 工具内部异常 */
    INTERNAL
}

/**
 * 工具执行的统一结果包装：**成功 / 失败 / 超时**。
 *
 * ## 为什么现在就要这一层（§9.6 预留项 1）
 *
 * 后续的二手集市 / 美食街工具读**服务器**，是异步、会失败、有超时的。
 * 若只按"同步本地函数"设计（比如直接返回 `List<Course>` 或抛异常），
 * 以后必须重构 `AgentLoop`。**即使本地工具立即返回，也照此定义。**
 *
 * [payload] 是**回传给模型的文本**，不是给人看的。因此：
 * - 失败也要回传（让模型如实说"没查到/参数不对"，而不是自己编）
 * - 内容必须是本地算好的确定性结果（§6.8：模型只做翻译，不做检索与计算）
 */
sealed interface ToolResult {

    /** 是否成功。`AgentLoop` 用它决定界面上的工具状态图标。 */
    val ok: Boolean

    /** 回传给模型的内容（JSON 字符串） */
    val payload: String

    /**
     * 成功。
     *
     * 没有 `userHintRes` —— 过程反馈的"正在查询…"由 [ToolSpec.loadingHintRes]
     * 在执行**之前**显示，成功之后不需要再补一句。
     */
    data class Success(override val payload: String) : ToolResult {
        override val ok: Boolean get() = true
    }

    /**
     * 失败。
     *
     * @param userHintRes 给用户看的一句话（如「本地还没有这项数据，请先同步」）。
     *        [payload] 是给模型看的，两者受众不同 —— 用户不该读 JSON。
     */
    data class Failure(
        val kind: ToolFailureKind,
        override val payload: String,
        @StringRes val userHintRes: Int
    ) : ToolResult {
        override val ok: Boolean get() = false
    }

    /**
     * 超时。
     *
     * 本版的 5 个工具全是本地 Room 查询，**不会**超时 —— 但类型现在就留着，
     * 这样将来接远程工具时 `AgentLoop` 的分支已经写好了。
     */
    data class Timeout(
        override val payload: String,
        @StringRes val userHintRes: Int
    ) : ToolResult {
        override val ok: Boolean get() = false
    }
}

/**
 * 工具实现。
 *
 * 刻意用普通 interface 而不是 `fun interface`：`suspend` 抽象成员配合 SAM
 * 转换虽然可用，但每个工具都需要独立的 KDoc 说明参数校验规则，
 * 具名实现类比 lambda 更适合承载这些说明。
 */
interface ToolHandler {
    /**
     * @param arguments 已解析的 JSON 对象。**内容完全来自模型，必须逐项校验** ——
     *        日期格式、课程名长度都不能"模型传什么就执行什么"（§9.2 坑 4）。
     */
    suspend fun execute(arguments: JsonObject): ToolResult
}

// ═══════════════════════════════════════════════════════════════════════════
// JSON Schema 构造小工具
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 构造一个 object 类型的 JSON Schema。
 *
 * 刻意**不写** `additionalProperties: false`：部分 OpenAI 兼容服务端会拿它做
 * 严格校验，而模型偶尔会多塞一个自认为有用的字段，那会让整次工具调用被判 400。
 * 参数校验本来就由我们自己在 [ToolHandler] 里做，不需要 schema 帮忙兜底。
 */
internal fun objectSchema(
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (name, schema) -> put(name, schema) }
    }
    if (required.isNotEmpty()) {
        putJsonArray("required") { required.forEach { add(it) } }
    }
}

internal fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

internal fun booleanProperty(description: String): JsonObject = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

/** 空 schema：表示该工具不接受任何参数。 */
internal fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { }
}

/** 构造一个无参数的 JsonObject（工具入参缺省时用）。 */
internal fun emptyArguments(): JsonObject = buildJsonObject { }

/** 便捷构造 JSON 数组 schema（本版未使用，留作后续远程工具的筛选项） */
@Suppress("unused")
internal fun arrayOfStringsProperty(description: String): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", description)
    putJsonObject("items") { put("type", "string") }
}
