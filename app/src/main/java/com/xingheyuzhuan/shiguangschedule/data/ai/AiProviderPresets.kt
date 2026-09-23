package com.xingheyuzhuan.shiguangschedule.data.ai

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.xingheyuzhuan.shiguangschedule.R

// ═══════════════════════════════════════════════════════════════════════════
// 厂商预设
//
// 已定（方案文档 §9.1.1）：**只放「DeepSeek」+「自定义」两项**，不铺开多厂商。
//
// 本文件里的所有参数（base_url、模型名、价格、分时窗口）均于 **2026-09-23**
// 核对官方文档 https://api-docs.deepseek.com/quick_start/pricing 与
// https://api-docs.deepseek.com/ 后写入。
//
// ⚠️ **价格单位是美元**。方案文档 §9.1.1 的表格把同样的数值标成了「元」，
// 那是个单位笔误（数值本身是官方 USD 报价）。代码里以官方 USD 为准。
// ═══════════════════════════════════════════════════════════════════════════

/** 单个模型的价格（美元 / 百万 token）。 */
@Immutable
data class AiModelPrice(
    /** 计价币种，官方为 USD */
    val currency: String = "USD",
    /** 输入（命中缓存）空闲时段价 */
    val inputCacheHitOffPeak: Double,
    /** 输入（未命中缓存）空闲时段价 */
    val inputCacheMissOffPeak: Double,
    /** 输出空闲时段价 */
    val outputOffPeak: Double,
    /** 高峰时段相对空闲时段的倍数 */
    val peakMultiplier: Double = 2.0
)

/**
 * 高峰时段窗口（**本地时间**，即设备时区的钟点）。
 *
 * 官方表述是 UTC 的 `01:00–04:00` 与 `06:00–10:00`（周一至周五，中国法定节假日除外），
 * 换算到北京时间（UTC+8）正好是 `09:00–12:00` 与 `14:00–18:00`。
 *
 * 用结构化的小时区间而不是一段中文文案：文案要出 4 份 `strings.xml`，
 * 而时区换算逻辑一旦变成字符串就没法再被程序判断。
 */
@Immutable
data class AiPeakWindow(val startHour: Int, val endHour: Int)

/** 一个可选模型。 */
@Immutable
data class AiModelOption(
    val id: String,
    /** 是否为预设推荐项（UI 上打「推荐」标） */
    val recommended: Boolean = false,
    /** 是否支持图像理解（本版**不做**多模态，仅用于如实标注能力） */
    val supportsVision: Boolean = false,
    val price: AiModelPrice? = null
)

/**
 * 图文教程的一步。
 *
 * [illustration] 决定 UI 画哪张**自绘示意图** —— 我们无法产出厂商官网的真实截图，
 * 因此用 Compose 画的步骤示意图代替，并配文字说明与官网链接。
 */
@Immutable
data class AiTutorialStep(
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val illustration: AiTutorialIllustration
)

/** 教程示意图的种类。每种对应 UI 里一个自绘图形。 */
enum class AiTutorialIllustration {
    /** 打开官网并注册 / 登录 */
    OPEN_SITE,
    /** 充值余额 */
    RECHARGE,
    /** 创建并复制 API Key */
    CREATE_KEY,
    /** 回到本页粘贴并测试 */
    PASTE_KEY
}

/**
 * 厂商预设。
 *
 * @param allowsCustomBaseUrl `自定义` 为 true —— 用户必须自己填全
 * @param declaredToolCalls 预设**声明**的能力。`自定义` 填 false 表示"未知"，
 *        由运行期探测（[AiFailure.isToolUnsupported]）而不是靠声明
 * @param requiresFullConfig true = 必须填全 base_url + model + key 才能用
 */
@Immutable
data class AiProviderPreset(
    val id: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<AiModelOption>,
    val declaredToolCalls: Boolean,
    val supportsThinkingToggle: Boolean,
    val allowsCustomBaseUrl: Boolean,
    val allowsCustomModel: Boolean,
    val requiresFullConfig: Boolean,
    /** 创建 API Key 的页面（官方文档给出的地址） */
    val apiKeysUrl: String?,
    /** 控制台首页（注册 / 充值入口） */
    val consoleUrl: String?,
    /** 是否有时段性定价，需要向用户提示 */
    val hasTimeOfDayPricing: Boolean,
    val tutorialSteps: List<AiTutorialStep>
)

object AiProviderPresets {

    /** `自定义` 的固定 id。 */
    const val CUSTOM_ID = "custom"

    /** DeepSeek 的固定 id。 */
    const val DEEPSEEK_ID = "deepseek"

    /**
     * DeepSeek 的高峰时段（北京时间）。
     *
     * 与官方 UTC 窗口 `01:00–04:00` / `06:00–10:00` 一一对应。
     */
    val DEEPSEEK_PEAK_WINDOWS = listOf(
        AiPeakWindow(startHour = 9, endHour = 12),
        AiPeakWindow(startHour = 14, endHour = 18)
    )

    /** 高峰时段仅限工作日（周一至周五），中国法定节假日全天按空闲计价。 */
    const val DEEPSEEK_PEAK_WEEKDAYS_ONLY = true

    private val FLASH_PRICE = AiModelPrice(
        inputCacheHitOffPeak = 0.003,
        inputCacheMissOffPeak = 0.15,
        outputOffPeak = 0.60
    )

    private val PRO_PRICE = AiModelPrice(
        inputCacheHitOffPeak = 0.022,
        inputCacheMissOffPeak = 0.66,
        outputOffPeak = 1.98
    )

    /**
     * DeepSeek 预设。
     *
     * 两个模型都支持 Tool Calls（已核实），因此 §6.6 的降级路径在 DeepSeek 上
     * **不会触发**，它只对「自定义」接的第三方模型生效。
     */
    val DEEPSEEK = AiProviderPreset(
        id = DEEPSEEK_ID,
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        models = listOf(
            AiModelOption(
                id = "deepseek-flash",
                recommended = true,
                supportsVision = true,
                price = FLASH_PRICE
            ),
            AiModelOption(
                id = "deepseek-v4-pro",
                recommended = false,
                supportsVision = false,
                price = PRO_PRICE
            )
        ),
        declaredToolCalls = true,
        // 接受 DeepSeek 专有的 `thinking` 字段
        supportsThinkingToggle = true,
        allowsCustomBaseUrl = false,
        allowsCustomModel = true,
        requiresFullConfig = false,
        apiKeysUrl = "https://platform.deepseek.com/api_keys",
        consoleUrl = "https://platform.deepseek.com",
        hasTimeOfDayPricing = true,
        tutorialSteps = listOf(
            AiTutorialStep(
                titleRes = R.string.ai_tutorial_step_open_site_title,
                descRes = R.string.ai_tutorial_step_open_site_desc,
                illustration = AiTutorialIllustration.OPEN_SITE
            ),
            AiTutorialStep(
                titleRes = R.string.ai_tutorial_step_recharge_title,
                descRes = R.string.ai_tutorial_step_recharge_desc,
                illustration = AiTutorialIllustration.RECHARGE
            ),
            AiTutorialStep(
                titleRes = R.string.ai_tutorial_step_create_key_title,
                descRes = R.string.ai_tutorial_step_create_key_desc,
                illustration = AiTutorialIllustration.CREATE_KEY
            ),
            AiTutorialStep(
                titleRes = R.string.ai_tutorial_step_paste_key_title,
                descRes = R.string.ai_tutorial_step_paste_key_desc,
                illustration = AiTutorialIllustration.PASTE_KEY
            )
        )
    )

    /**
     * 「自定义」预设：base_url / model / key **必须填全**。
     *
     * ⚠️ 明确提示用户：**需支持 OpenAI 兼容格式与 Tool Calls**，
     * 否则只能回答摘要类问题（走降级路径）。
     */
    val CUSTOM = AiProviderPreset(
        id = CUSTOM_ID,
        baseUrl = "",
        defaultModel = "",
        models = emptyList(),
        // 声明为 false 表示"未知"，实际由运行期探测决定
        declaredToolCalls = false,
        // 不是 DeepSeek，不能发 thinking 字段 —— 未知字段可能被严格服务端判 400
        supportsThinkingToggle = false,
        allowsCustomBaseUrl = true,
        allowsCustomModel = true,
        requiresFullConfig = true,
        apiKeysUrl = null,
        consoleUrl = null,
        hasTimeOfDayPricing = false,
        tutorialSteps = emptyList()
    )

    /** 全部预设，顺序即 UI 展示顺序（DeepSeek 在前）。 */
    val ALL: List<AiProviderPreset> = listOf(DEEPSEEK, CUSTOM)

    fun byId(id: String?): AiProviderPreset =
        ALL.firstOrNull { it.id == id } ?: DEEPSEEK

    /**
     * 把用户填的 base_url 规范化成 `chat/completions` 的完整地址。
     *
     * 规则（按顺序判断）：
     * 1. 已经是完整端点（以 `/chat/completions` 结尾）→ 原样使用
     * 2. 以 `/v1` 结尾 → 追加 `/chat/completions`
     * 3. 其它 → 追加 `/v1/chat/completions`
     *
     * 第 3 条覆盖了 `https://api.deepseek.com` 这种"裸域名"：
     * DeepSeek 官方明确支持 `/v1` 前缀（为兼容 OpenAI SDK），因此
     * `https://api.deepseek.com/v1/chat/completions` 是有效地址。
     *
     * @return null 表示 base_url 为空或不是 http(s) 地址
     */
    fun buildChatCompletionsUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null

        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /**
     * base_url 是否是**明文 HTTP**。
     *
     * 明文信道下 API Key 会被同网段的人直接看到，因此设置页要对此给出警告
     * （不硬拦 —— 用户可能在内网自建网关，但要让他知道代价）。
     */
    fun isInsecureBaseUrl(baseUrl: String): Boolean =
        baseUrl.trim().lowercase().startsWith("http://")
}
