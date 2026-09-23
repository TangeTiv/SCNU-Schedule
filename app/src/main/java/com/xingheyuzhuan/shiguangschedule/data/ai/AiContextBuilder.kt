package com.xingheyuzhuan.shiguangschedule.data.ai

import com.xingheyuzhuan.shiguangschedule.data.db.main.GradeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分层上下文组装（方案文档 §6.7）。
 *
 * ## 为什么"全覆盖"≠"全塞 prompt"
 *
 * 成绩明细 + 培养计划树 + 非正式学时全部序列化后 token 量很大，
 * 而且**上下文越长，模型注意力越分散、准确率越低**。
 * 因此分两层：
 *
 * | 层 | 内容 | 何时启用 |
 * |---|---|---|
 * | 基础层 | 本周+下周课表 / 未来考试 / 全部成绩明细 / 学分与绩点汇总 / 培养计划类别汇总 / 非正式学时汇总+明细 | 必做，**任何模型都能用**（含不支持 tool use 的） |
 * | 增强层 | 按需查询：某段日期的课程明细 / 某门课成绩 / 培养计划某类别 | 模型支持 tool use 时 |
 *
 * ## 注入口径（§6.7 的表，逐条对应）
 *
 * | 数据 | 注入 | 不注入 |
 * |---|---|---|
 * | 课表 | **本周 + 下周** | 全学期（100+ 条，又贵又散） |
 * | 考试 | **未来未考的** | 已考完的历史 |
 * | 成绩 | **全部课程明细**（约 50 行，token 可接受） | — |
 * | 学分 / 绩点 | **本地算好的汇总** | 原始数据 |
 * | 培养计划 | **类别级汇总**（每类"要求 X / 已修 Y"） | 全部节点明细 |
 * | 非正式学时 | 汇总 + 明细 | — |
 *
 * ## 一个刻意的取舍：system 提示词必须**逐字稳定**
 *
 * DeepSeek 有「缓存命中」价（输入 token 便宜 50 倍）。缓存按**前缀**匹配，
 * 因此 system 提示词在同一会话内只要字节相同就一直命中。
 * 反过来说：**绝不能在 system 里塞时间戳、随机数、或用户当前的提问** ——
 * 那样每次请求前缀都变，缓存全部落空，成本直接翻上去。
 *
 * 本类因此是纯函数式的：输入（日期 + 数据库内容）相同 → 输出**逐字相同**。
 * 会随提问变化的东西（如规则前置预取的结果）一律不进 system，
 * 而是作为一次"已经发生过的工具调用"追加在消息列表末尾（见 [AgentLoop]）。
 */
@Singleton
class AiContextBuilder @Inject constructor(
    private val aggregator: LocalDataAggregator
) {

    companion object {
        /** 没数据时的统一占位，避免模型把"字段缺失"当成"数值为 0"。 */
        private const val NO_DATA = "（本地暂无数据，用户可能还没在【校园】页同步）"

        private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    }

    /**
     * 组装 system 提示词。
     *
     * @param today 今天（由调用方注入，便于测试）
     */
    suspend fun buildSystemPrompt(today: LocalDate = LocalDate.now()): String {
        // ── 1. 取数（IO） ──
        val table = aggregator.loadCourseTable()
        val currentWeek = aggregator.currentWeek(today)
        val upcoming = aggregator.currentAndNextWeekOccurrences(today)
        val exams = aggregator.exams(onlyUpcoming = true, today = today)
        val grades = aggregator.allGrades()
        val summary = aggregator.creditSummary()

        // ── 2. 组装（CPU，挪到 Default） ──
        return withContext(Dispatchers.Default) {
            buildString {
                appendHardRules()
                appendLine()

                appendLine("【今天】${today}（${WEEKDAY_LABELS.getOrElse(today.dayOfWeek.value - 1) { "未知" }}）")
                appendLine(weekLine(currentWeek, table?.totalWeeks ?: 0, table?.semesterStartDate == null))
                appendLine()

                appendLine("【本周与下周课程】")
                if (table?.semesterStartDate == null) {
                    appendLine("未设置开学日期，无法换算教学周。")
                } else if (upcoming.isEmpty()) {
                    appendLine("这两周没有查到课程。")
                } else {
                    upcoming.forEach { appendLine(it.toContextLine()) }
                }
                appendLine()

                appendLine("【未来考试（未考完）】")
                if (exams.isEmpty()) appendLine("没有查到待考记录。")
                else exams.forEach { appendLine(it.toContextLine()) }
                appendLine()

                appendLine("【成绩明细】")
                if (grades.isEmpty()) {
                    appendLine(NO_DATA)
                } else {
                    grades.forEach { appendLine(it.toContextLine()) }
                }
                appendLine()

                appendLine("【学分与绩点（已由本地算好，直接引用）】")
                appendLine("加权平均绩点：${format1(summary.gpa)}（共 ${summary.gradeCount} 条成绩记录）")
                appendLine(
                    "成绩表学分列合计：${format1(summary.gradeBasedCredits)}" +
                        "（含不及格课程，**不等于已获学分**，仅供参考）"
                )
                if (summary.hasPlan) {
                    appendLine(
                        "培养计划：已获 ${format1(summary.planEarnedTotal)} / 要求 " +
                            "${format1(summary.planRequiredTotal)}，还差 ${format1(summary.planGapTotal)}"
                    )
                } else {
                    appendLine("培养计划：$NO_DATA")
                }
                appendLine(
                    "本地判定的不及格课程：" +
                        if (summary.failedCourseNames.isEmpty()) "无"
                        else summary.failedCourseNames.joinToString("、")
                )
                appendLine()

                appendLine("【培养计划类别完成度】")
                if (summary.categories.isEmpty()) {
                    appendLine(NO_DATA)
                } else {
                    summary.categories.forEach { appendLine(it.toContextLine()) }
                }
                appendLine()

                appendLine("【非正式学时（第二类课）】")
                val nonFormal = summary.nonFormal
                if (nonFormal.courseCount == 0) {
                    appendLine(NO_DATA)
                } else {
                    appendLine("合计 ${nonFormal.totalHours} 学时 / ${nonFormal.courseCount} 条记录")
                    nonFormal.items.forEach { item ->
                        appendLine(
                            "${item.yearTerm} ${item.courseName} ${item.hours}学时 结论:${item.result}"
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 硬约束
    //
    // ⚠️ 作用域必须写准（真机实测踩过）：
    // 早期版本写成「**只能**依据本地数据回答」＋「**只能**查这六类」＋
    // 「与数据无关的直接说明尚未开放」，导致模型问「17 × 23」时先答对、
    // 再补一句"这不在我的服务范围内"，比直接拒绝更让人困惑。
    //
    // 正确的作用域划分：
    // - **用户本人数据** → 必须依据本地数据，查不到就说查不到（防幻觉的核心）
    // - **一般问题**（数学、常识、解释概念、改写文字）→ 正常回答，但要求简短
    //
    // 关键认知：防幻觉靠的是「用户数据必须来自本地」，与「能不能答一道数学题」
    // 是两件独立的事。放开通用能力**不会**削弱防幻觉 —— 第 2 条才是核心。
    // ═══════════════════════════════════════════════════════════════════════

    private fun StringBuilder.appendHardRules() {
        appendLine(
            """
            你是「师陶学程」App 内的助手。你的主要用途是帮用户查他自己的课表、考试、成绩、
            学分、培养计划、非正式学时，下面提供了他当前的本地数据。

            【必须遵守】
            1. 涉及用户本人数据（课表、考试、成绩、学分、培养计划、非正式学时）的问题，
               只能依据下面的数据和工具返回结果回答。数据里没有的，明确说「查不到」，
               绝对不要编造课程名、分数、教室、时间、教师姓名。
            2. 本地已经算好的汇总（加权平均绩点、已获学分、学分差额、非正式学时合计、
               距今天数）直接引用，不要自己重新统计；也不要自己把成绩明细加权算一个
               绩点出来 —— 那会与本地口径不一致。若用户问的口径本地没算过
               （例如算术平均分），可以按明细现算，但要说明这是按明细现算的、
               可能与教务口径不同。对已给出的明细做比较、排序、筛选
               （如「哪门分数最高」）是允许的。
            3. 与用户数据无关的一般问题（数学计算、常识、解释概念、改写文字等）
               可以正常回答，但要**简短**：一两句给出结论即可，不要长篇展开，
               也不要反问引导用户继续聊无关话题。
            4. 工具返回空结果时如实说「没查到」，不要用常识或猜测补全。
            5. 二手集市、美食街等**尚未开放的功能**如实说明尚未开放；
               但不要因为问题与数据无关就一律拒绝（见第 3 条）。
            6. 用与用户提问相同的语言回答；先给结论，再给依据，不要输出
               「根据提供的数据」这类元话术。
            7. **用纯文本回答，不要使用 Markdown 语法**。界面按纯文本渲染，
               `**加粗**`、`# 标题`、`| 表格 |`、`` `代码` `` 会原样显示成符号，
               非常难看。需要列举时用「1. 2. 3.」或「- 」，一行一条；
               多条成绩/课程不要排成表格，改成「课程名 学分 成绩」这样的单行文本。
            8. 回答尽量简短，避免把全部原始数据复述一遍；用户要明细时再逐条列。
            """.trimIndent()
        )
    }

    private fun weekLine(currentWeek: Int?, totalWeeks: Int, missingStartDate: Boolean): String =
        when {
            missingStartDate -> "教学周：未设置开学日期，无法判断第几周"
            currentWeek == null -> "教学周：当前不在学期内（本学期共 $totalWeeks 周）"
            else -> "教学周：第 $currentWeek 周（本学期共 $totalWeeks 周）"
        }

    // ═══════════════════════════════════════════════════════════════════════
    // 行格式化（紧凑、字段明确，便于模型引用）
    // ═══════════════════════════════════════════════════════════════════════

    private fun CourseOccurrence.toContextLine(): String = buildString {
        append("$date(${WEEKDAY_LABELS.getOrElse(dayOfWeek - 1) { "未知" }}) 第${weekNumber}周 ")
        if (startTime.isNotBlank()) {
            append(startTime)
            if (endTime.isNotBlank()) append("-$endTime")
            append(' ')
        }
        append("$name 教师:$teacher 地点:$position $sectionLabel")
    }

    private fun AiExamItem.toContextLine(): String = buildString {
        append(rawTime)
        daysUntil?.let { days ->
            append(
                when {
                    days > 0 -> "（还有 $days 天）"
                    days == 0L -> "（今天）"
                    else -> "（已过 ${-days} 天）"
                }
            )
        }
        append(" $name 科目:$course 考场:$venue")
    }

    private fun GradeEntity.toContextLine(): String =
        buildString {
            val term = listOf(xnmmc, xqmmc).filter { it.isNotBlank() }.joinToString(" ")
            if (term.isNotBlank()) append("$term ")
            append(kcmc.ifBlank { "—" })
            if (xf.isNotBlank()) append(" 学分:$xf")
            append(" 成绩:${cj.ifBlank { "—" }}")
            if (jd.isNotBlank()) append(" 绩点:$jd")
            if (kcxzmc.isNotBlank()) append(" 性质:$kcxzmc")
            if (jsxm.isNotBlank()) append(" 教师:$jsxm")
        }

    private fun PlanCategoryProgress.toContextLine(): String =
        "$name 要求:${format1(requiredCredits)} 已修:${format1(earnedCredits)} " +
            "还差:${format1(gapCredits)}（含 $courseCount 门课）"

    /** 学分/绩点保留 1 位小数；与 `AcademicTreeBuilder.formatCredits` 的口径一致。 */
    private fun format1(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", rounded)
    }
}
