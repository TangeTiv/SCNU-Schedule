package com.xingheyuzhuan.shiguangschedule.data.ai

import com.xingheyuzhuan.shiguangschedule.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

// ═══════════════════════════════════════════════════════════════════════════
// 规则前置的意图路由
//
// 方案文档 §9.2「路径策略：规则前置 + Agent 兜底」：
//
//   用户提问
//     ├ 规则命中 → 本地直接查好 → 交给模型翻译
//     │            零成本、零延迟、100% 准确、任何模型都能用
//     └ 规则未命中 → 走 Agent 循环，让模型自己选工具
//
// 本类是一个**纯函数对象**：不碰数据库、不做 IO，只根据问题文本与
// "今天是哪天 / 本周一是哪天"算出结论。因此可以脱离 Android 直接单测。
//
// ## 它的三个职责
//
// 1. **决定加载哪组工具**（§6.5 的硬要求，工具数必须始终 ≤ 6）；
// 2. **对"按日期范围查课"做预取** —— 基础层只注入本周+下周，
//    用户问"上个月某天"时本地先把明细查好塞进上下文，省掉一轮工具往返；
// 3. 给 UI / 日志一个明确的意图标签。
//
// ## 为什么只有课表做预取
//
// 因为基础层（§6.7）**已经注入了**：全部成绩明细、学分与绩点汇总、
// 培养计划类别汇总、非正式学时汇总+明细、未来考试、本周+下周课表。
// 也就是说「高数考了多少分」「绩点多少」「还差哪几类学分」这些问法，
// 模型在第一次调用时就**已经拿到了全部所需数据**，再预取一次纯属浪费。
//
// 唯独"某天/某段日期有什么课"无法预先注入（全学期 100+ 条，全塞进去又贵又散），
// 必须按用户问的日期现查 —— 这才是规则前置真正省下那一轮往返的地方，
// 也恰好是最高频的问法。
// ═══════════════════════════════════════════════════════════════════════════

/** 规则命中的意图。 */
sealed interface AiIntent {

    /**
     * 按日期范围查课。
     *
     * @param label 给用户/日志看的范围描述（如「明天」「下周」）
     */
    data class Courses(
        val start: LocalDate,
        val end: LocalDate,
        val label: String
    ) : AiIntent

    /** 考试安排。 */
    data class Exams(val onlyUpcoming: Boolean) : AiIntent

    /** 成绩明细。[courseName] 为空表示"全部成绩"。 */
    data class Grades(val courseName: String?) : AiIntent

    /** 学分 / 绩点 / 非正式学时汇总。 */
    data object CreditSummary : AiIntent

    /** 培养计划分类完成度。 */
    data class PlanProgress(val category: String?) : AiIntent

    /** 非正式学时。 */
    data object NonFormalHours : AiIntent
}

/**
 * 路由结论。
 *
 * @param intent 命中的意图；null = 规则未命中 → Agent 兜底
 * @param groups 本次要加载的工具组。**永远至少含一个组**，
 *        且总工具数受 `specsFor` 约束在 6 个以内
 * @param prefetch 规则前置已确定要查的东西；null = 基础层已覆盖，无需预取
 */
data class RoutingDecision(
    val intent: AiIntent?,
    val groups: Set<ToolGroup>,
    val prefetch: Prefetch? = null
) {
    val hit: Boolean get() = intent != null
}

/** 需要本地预取的一次查询。 */
data class Prefetch(
    val toolName: String,
    val arguments: JsonObject,
    /** 给用户看的过程反馈文案（资源 id） */
    val hintRes: Int
)

@Singleton
class IntentRouter @Inject constructor() {

    companion object {
        // ── 关键词表 ──
        //
        // 判定顺序**有意义**，写在 [route] 里，不要随意调整：
        // "下周期中在哪考"同时含"下周"与"期中"，必须让考试先命中。

        private val EXAM_WORDS = listOf(
            "考试", "期中", "期末", "考场", "补考", "重考", "缓考",
            "没考", "考完", "考试安排", "几门没考", "什么时候考", "在哪考"
        )

        private val GRADE_WORDS = listOf(
            "成绩", "分数", "考了多少", "考了几分", "多少分", "几分",
            "挂科", "挂了", "不及格", "及格", "成绩单"
        )

        private val PLAN_WORDS = listOf(
            "培养计划", "哪几类", "哪类", "哪一类", "类别", "通识", "大类教育",
            "专业必修", "专业选修", "实践教育", "思政", "学分要求"
        )

        private val NON_FORMAL_WORDS = listOf(
            "非正式学时", "第二类课", "学时够", "学时还差", "非正式课程", "第二课堂"
        )

        private val CREDIT_WORDS = listOf(
            "学分", "绩点", "gpa", "平均分", "加权", "毕业要求", "修了多少"
        )

        private val COURSE_WORDS = listOf(
            "课表", "有什么课", "有哪些课", "上什么课", "几节课", "有课吗",
            "上课", "课程安排", "调课", "空课", "没课"
        )

        /** 二手集市（预留模块）。 */
        private val MARKETPLACE_WORDS = listOf(
            "二手", "卖", "买", "交易", "转手", "出掉", "求购", "跳蚤", "闲置", "出手"
        )

        /** 美食街（预留模块）。 */
        private val FOOD_WORDS = listOf(
            "美食", "吃", "食堂", "餐厅", "外卖", "夜宵", "奶茶", "饭堂"
        )

        /** 问句里的口水词，提取课程名时先剥掉。 */
        private val FILLER_PREFIXES = listOf(
            "请问", "我想知道", "想知道", "帮我看看", "帮我查", "帮我",
            "查一下", "看一下", "看下", "我的", "我", "咱"
        )

        /** 《课程名》 */
        private val BOOK_TITLE = Regex("""《([^》]{1,20})》""")

        /**
         * 「X 考了多少分 / 成绩 / 分数」里的 X。
         *
         * 用 reluctant 量词从最早位置尝试，因此"我的高数考了多少分"会先给出
         * "我的高数"，再由 [FILLER_PREFIXES] 剥成"高数"。
         */
        private val SUBJECT_BEFORE_SCORE = Regex(
            """([\u4e00-\u9fa5A-Za-z0-9]{2,20}?)(?:这门?课?)?""" +
                """(?:考了多少分|考了几分|多少分|几分|成绩|分数)"""
        )

        /** 具体日期：2026-09-25 */
        private val FULL_DATE = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""")

        /** 具体日期：9月25日 / 9月25号 */
        private val MONTH_DAY = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")

        /** 周几：周三 / 星期三 / 礼拜3 / 周天 */
        private val WEEKDAY = Regex("""(?:周|星期|礼拜)\s*([一二三四五六日天1-7])""")

        /** 相对日：今天 / 明天 / 后天 / 大后天 / 昨天 */
        private val RELATIVE_DAY = Regex("""大后天|后天|明天|明日|今天|今日|昨天|昨日""")

        /** 星期字 → 1..7 */
        private fun weekdayValue(token: String): Int = when (token) {
            "一", "1" -> 1
            "二", "2" -> 2
            "三", "3" -> 3
            "四", "4" -> 4
            "五", "5" -> 5
            "六", "6" -> 6
            "日", "天", "7" -> 7
            else -> 1
        }
    }

    /**
     * 规则路由。
     *
     * @param question 用户的原始提问
     * @param today 今天（由调用方注入，便于测试与"跨零点"一致性）
     * @param weekStart 今天所在周的起始日（按课表设置的 `firstDayOfWeek` 对齐）。
     *        由调用方从 `LocalDataAggregator.weekStartOf` 取，避免本类依赖数据库。
     */
    fun route(question: String, today: LocalDate, weekStart: LocalDate): RoutingDecision {
        val text = question.trim()
        val lower = text.lowercase()

        // ── 1. 先定工具组（§6.5 的硬要求，与意图识别相互独立） ──
        val groups = resolveGroups(lower)

        // ── 2. 再定意图。顺序不可调换，见 EXAM_WORDS 上方的说明 ──
        val intent = detectIntent(text, lower, today, weekStart)

        // ── 3. 只有"按日期查课"需要预取，理由见文件头 ──
        val prefetch = if (intent is AiIntent.Courses) {
            Prefetch(
                toolName = LocalQueryTools.TOOL_COURSES,
                arguments = buildJsonObject {
                    put("start_date", intent.start.toString())
                    put("end_date", intent.end.toString())
                },
                hintRes = R.string.ai_tool_loading_courses
            )
        } else {
            null
        }

        return RoutingDecision(intent = intent, groups = groups, prefetch = prefetch)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 工具组
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 决定加载哪组工具。
     *
     * 规则（§6.5）：
     * - 默认【教务组】
     * - 命中二手/美食 → 换成/追加对应组
     * - 跨模块（如"没课 + 交易"）→ 两组并集，仍受 6 个工具的上限约束
     *
     * ⚠️ 预留组当前**没有任何工具实现**，因此：
     * - 纯"二手"提问 → 工具集为空 → `AgentLoop` 走降级路径，界面如实提示
     *   「二手集市 / 美食街的 AI 查询尚未开放」（而不是编一个答案）
     * - "没课 + 交易"这类跨模块提问 → 并集里教务组仍有 5 个工具，
     *   因此**组合查询依然可用**（这正是 §9.6 认为 AI 真正有价值的用法 B）
     */
    private fun resolveGroups(lowerQuestion: String): Set<ToolGroup> {
        val wantsMarketplace = MARKETPLACE_WORDS.any { lowerQuestion.contains(it) }
        val wantsFood = FOOD_WORDS.any { lowerQuestion.contains(it) }
        val wantsAcademic = ACADEMIC_WORDS.any { lowerQuestion.contains(it) }

        val groups = LinkedHashSet<ToolGroup>()
        if (wantsMarketplace) groups += ToolGroup.MARKETPLACE
        if (wantsFood) groups += ToolGroup.FOOD

        // 没有任何非教务诉求，或同时含教务诉求（跨模块组合查询）→ 带上教务组
        if (groups.isEmpty() || wantsAcademic) groups += ToolGroup.ACADEMIC

        return groups
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 意图识别
    // ═══════════════════════════════════════════════════════════════════════

    private fun detectIntent(
        text: String,
        lower: String,
        today: LocalDate,
        weekStart: LocalDate
    ): AiIntent? = when {
        EXAM_WORDS.any { text.contains(it) } -> {
            // "上学期的考试""考完了"→ 需要历史；默认只要没考完的
            val wantsHistory = listOf("上学期", "以前", "之前", "历史", "考完", "已经考").any {
                text.contains(it)
            }
            AiIntent.Exams(onlyUpcoming = !wantsHistory)
        }

        GRADE_WORDS.any { lower.contains(it) } -> AiIntent.Grades(extractCourseName(text))

        PLAN_WORDS.any { text.contains(it) } -> AiIntent.PlanProgress(extractPlanCategory(text))

        NON_FORMAL_WORDS.any { text.contains(it) } -> AiIntent.NonFormalHours

        CREDIT_WORDS.any { lower.contains(it) } -> AiIntent.CreditSummary

        COURSE_WORDS.any { text.contains(it) } -> AiIntent.Courses(
            start = resolveRangeStart(text, today, weekStart),
            end = resolveRangeEnd(text, today, weekStart),
            label = describeRange(text, today)
        )

        // 只给了时间词（"明天呢""下周三"）也算课表意图
        RELATIVE_DAY.containsMatchIn(text) || WEEKDAY.containsMatchIn(text) ||
            FULL_DATE.containsMatchIn(text) || MONTH_DAY.containsMatchIn(text) ->
            AiIntent.Courses(
                start = resolveRangeStart(text, today, weekStart),
                end = resolveRangeEnd(text, today, weekStart),
                label = describeRange(text, today)
            )

        else -> null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 日期范围解析
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 解析日期范围的起点。
     *
     * 支持（优先级从上到下）：
     * 1. 完整日期 `2026-09-25`
     * 2. 月日 `9月25日`
     * 3. 相对日 `今天/明天/后天/大后天/昨天`
     * 4. 周几 `周三`（本周已过则取下周同一天 —— 说"这周三"时若今天已是周四，
     *    用户指的几乎必然是即将到来的那个周三）
     * 5. 周范围 `这周/下周/下下周`
     * 6. 都没有 → 从今天开始
     */
    private fun resolveRangeStart(text: String, today: LocalDate, weekStart: LocalDate): LocalDate {
        FULL_DATE.find(text)?.let { m ->
            parseDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { return it }
        }
        MONTH_DAY.find(text)?.let { m ->
            val month = m.groupValues[1].toIntOrNull()
            val day = m.groupValues[2].toIntOrNull()
            if (month != null && day != null) {
                runCatching { LocalDate.of(today.year, month, day) }
                    .getOrNull()
                    // 已过去的月日按明年算（"1月3日考试"在 9 月问，指的是明年）
                    ?.let { date -> return if (date.isBefore(today)) date.plusYears(1) else date }
            }
        }
        RELATIVE_DAY.find(text)?.let { m ->
            return when (m.value) {
                "今天", "今日" -> today
                "明天", "明日" -> today.plusDays(1)
                "后天" -> today.plusDays(2)
                "大后天" -> today.plusDays(3)
                "昨天", "昨日" -> today.minusDays(1)
                else -> today
            }
        }

        val weekShift = weekShiftOf(text)
        WEEKDAY.find(text)?.let { m ->
            val target = weekStart.plusDays((weekdayValue(m.groupValues[1]) - 1).toLong())
            val shifted = target.plusWeeks(weekShift.toLong())
            // 没写"下周/上周"时，已过去的那一天顺延到下周
            return if (weekShift == 0 && shifted.isBefore(today)) shifted.plusWeeks(1) else shifted
        }

        return when {
            weekShift > 0 -> weekStart.plusWeeks(weekShift.toLong())
            weekShift < 0 -> weekStart.plusWeeks(weekShift.toLong())
            else -> today
        }
    }

    /** 解析日期范围的终点。单天提问时与起点相同；周范围提问时取该周周日。 */
    private fun resolveRangeEnd(text: String, today: LocalDate, weekStart: LocalDate): LocalDate {
        val start = resolveRangeStart(text, today, weekStart)

        // 单天：今天/明天/后天/昨天/具体日期/周几
        if (RELATIVE_DAY.containsMatchIn(text) ||
            WEEKDAY.containsMatchIn(text) ||
            FULL_DATE.containsMatchIn(text) ||
            MONTH_DAY.containsMatchIn(text)
        ) {
            return start
        }

        // 周范围：这一整周
        return when (weekShiftOf(text)) {
            0 -> if (listOf("这周", "本周", "这一周", "这个星期", "本星期").any { text.contains(it) }) {
                start.plusDays(6)
            } else {
                // 只有课程类关键词（"有什么课"）→ 默认给"从今天起两周"
                today.plusDays(13)
            }

            else -> start.plusDays(6)
        }
    }

    /** 周偏移：下周 +1、下下周 +2、上周 -1、这周 0。 */
    private fun weekShiftOf(text: String): Int = when {
        listOf("下下周", "下下个星期").any { text.contains(it) } -> 2
        listOf("下周", "下个星期", "下星期", "下礼拜").any { text.contains(it) } -> 1
        listOf("上周", "上个星期", "上星期", "上礼拜").any { text.contains(it) } -> -1
        else -> 0
    }

    /** 范围描述文案，用于日志与（未来的）界面标注。 */
    private fun describeRange(text: String, today: LocalDate): String {
        RELATIVE_DAY.find(text)?.let { return it.value }
        val shift = weekShiftOf(text)
        if (shift == 2) return "下下周"
        if (shift == 1) return "下周"
        if (shift == -1) return "上周"
        WEEKDAY.find(text)?.let { return "周${it.groupValues[1]}" }
        FULL_DATE.find(text)?.let { return it.value }
        MONTH_DAY.find(text)?.let { return it.value }
        return "未来两周"
    }

    private fun parseDate(year: String, month: String, day: String): LocalDate? = runCatching {
        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
    }.getOrNull()

    // ═══════════════════════════════════════════════════════════════════════
    // 文本参数提取
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 从提问里猜课程名。
     *
     * 猜错的代价可控：`get_grades` 匹配不到时会返回 0 条并附带提示，
     * 模型会如实说"没查到"，而不是编一个分数。
     */
    private fun extractCourseName(text: String): String? {
        BOOK_TITLE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        val raw = SUBJECT_BEFORE_SCORE.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return stripFillers(raw).takeIf { it.length in 2..20 }
    }

    /**
     * 从提问里猜培养计划类别名。
     *
     * 直接复用已知的类别关键词，命中即用 —— 比正则切词稳。
     */
    private fun extractPlanCategory(text: String): String? =
        PLAN_CATEGORY_KEYWORDS.firstOrNull { text.contains(it) }

    private fun stripFillers(raw: String): String {
        var text = raw
        var changed = true
        while (changed) {
            changed = false
            FILLER_PREFIXES.firstOrNull { text.startsWith(it) }?.let {
                text = text.removePrefix(it)
                changed = true
            }
        }
        return text.trim()
    }

    private val PLAN_CATEGORY_KEYWORDS = listOf(
        "通识", "大类", "专业必修", "专业选修", "实践", "思政", "外语", "体育", "创新创业"
    )

    /** 只要提到教务相关词，就认为需要教务组（跨模块组合查询用）。 */
    private val ACADEMIC_WORDS = EXAM_WORDS + GRADE_WORDS + PLAN_WORDS +
        NON_FORMAL_WORDS + CREDIT_WORDS + COURSE_WORDS
}
