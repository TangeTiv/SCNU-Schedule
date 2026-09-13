package com.xingheyuzhuan.shiguangschedule.data.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

// ═══════════════════════════════════════════════════════════════════════════
// 宽松标量序列化器
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 把「字符串或数字」都读成 [String] 的序列化器。
 *
 * ## 为什么必须要有它
 *
 * 教务同一个字段会返回**不同类型**，这是实测踩到的坑：
 *
 * | 字段 | 可能的值 | JSON 类型 |
 * |---|---|---|
 * | `JD`（绩点） | `3.4` / `5` / `""` | **数字** 或字符串 |
 * | `XF`（学分） | `"2.0"` / `2` | 字符串 或数字 |
 *
 * 如果 DTO 直接声明成 `String`，遇到 `"JD":3.4` 会抛
 * `Unexpected JSON token ... Expected quotation mark '"', but had '3'`，
 * **整个计划点的课程明细全部反序列化失败**（实测 9 个计划点、91 门课因此丢失）。
 *
 * 因此所有「值可能是数字」的字符串字段都必须用本序列化器。
 * 数字一律按其字面量原样转成字符串（不重新格式化），
 * 保证 `3.4` 不会变成 `"3.4000000000000004"` 这种浮点噪声。
 */
object LenientStringSerializer : KSerializer<String> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        // 非 JSON 解码器（理论上不会走到）：退回标准字符串读取
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement().jsonPrimitive
        return when {
            element.isString -> element.content
            // 数字原样取字面量，避免 double 精度噪声
            else -> element.content
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 学业情况（培养计划 + 第二类课）网络模型
//
// 字段全部使用 String 且带默认值，原因：
// 1. 教务 JSON 里「未修」课程的学年/学期/成绩字段是空字符串而非缺字段；
// 2. 成绩可能是数字字符串，也可能是「通过」「优秀」等文字评语；
// 3. 教务会不定期新增字段，Json 已配置 ignoreUnknownKeys = true。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 修读状态码 → 中文文案。
 *
 * 实测取值分布（某账号 135 条课程）：`4`=50 条、`3`=69 条、`1`=16 条。
 * `2` 未在实测数据中出现，但教务的码表里有，故保留映射。
 */
val XDZT_MAP: Map<String, String> = mapOf(
    "1" to "在修",
    "2" to "未修",
    "3" to "未修",
    "4" to "已修"
)

/** 把教务原始修读状态码翻译为中文；未知码原样返回。 */
fun translateReadStatus(code: String): String =
    XDZT_MAP[code.trim()] ?: code.trim()

/**
 * 培养计划点。
 *
 * 这是**解析后的中间模型**，不直接对应某一个 JSON 接口 ——
 * 教务把整棵培养计划树以 JS 拼装代码的形式内嵌在首页 HTML 里，
 * 由 [ScnuAcademicScraper.parsePlanTree] 还原出来。
 *
 * @param id 教务下发的 32 位十六进制 ID（个别特殊节点为 `qtkcxfyq` 之类的语义 id）
 * @param parentId 父节点 id；四大教育类根节点为 null
 * @param name 计划点名称（如「通识必修」）
 * @param requiredCredits 要求学分
 * @param earnedCredits 已获得学分
 * @param passed 是否通过（对应源码 `sftg='1'`）
 */
data class PlanNodeDto(
    val id: String,
    val parentId: String?,
    val name: String,
    val requiredCredits: Float,
    val earnedCredits: Float,
    val passed: Boolean
)

/**
 * 培养计划内课程明细。
 *
 * 对应接口 `POST /xsxy/xsxyqk_cxJxzxjhxfyqKcxx.html?gnmkdm=N105515`，
 * 返回**裸 JSON 数组**（不是 jqGrid 的 `{items:[...]}` 包装）。
 *
 * ## ⚠️ 学年/学期有两套字段，必须都读
 *
 * 实测（135 门课程）教务用了两组语义不同的字段：
 *
 * | 字段 | 含义 | 覆盖 |
 * |---|---|---|
 * | `XNMC` / `XQMMC` | 已获得成绩的学年 / 学期 | 只有 50 门**已修**课程有 |
 * | `JYXDXNMC` / `JYXDXQMC` | **建议修读**学年 / 学期 | 全部 135 门都有 |
 *
 * 具体表现：`XDZT=1`(在修) 与 `XDZT=3`(未修) 的课程**完全不返回** `XNMC`，
 * 只有 `JYXDXNMC`；`XDZT=4`(已修) 两者都返回且值相同。
 *
 * 因此若只读 `XNMC`，会有 **85 门课**（16 在修 + 69 未修）的学年学期丢失成空串。
 * 这里用「优先取成绩学年，回退到建议修读学年」的策略合并两组字段。
 *
 * ## ⚠️ 数字/字符串混用的字段
 *
 * `JD`（绩点）实测返回**裸 JSON 数字**（`3.4`、`5`），偶尔是空串 `""`；
 * `XF`（学分）返回字符串。因此这两个字段用 [LenientStringSerializer] 兼容两种类型。
 * 这一条是实测踩出来的：早期版本把 `JD` 声明成 `String`，导致 9 个计划点、
 * 91 门课的明细全部反序列化失败（详见该序列化器的注释）。
 *
 * ## 其它字段
 *
 * 原始字段为全大写；此处已映射为可读命名。
 * 未使用的原始字段（`KCZT`、`KCZYXXS`、`JYXDXQM`、`KCLBDM`、`ZYZGKCBJ`）
 * 依赖 `ignoreUnknownKeys = true` 被安全忽略。
 */
@Serializable
data class AcademicCourseDto(
    @SerialName("KCH") val courseCode: String = "",
    @SerialName("KCMC") val courseName: String = "",
    @SerialName("KCYWMC") val courseEnglishName: String = "",
    @SerialName("KCH_ID") val courseId: String = "",
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("XF") val credits: String = "",
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("CJ") val score: String = "",
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("MAXCJ") val maxScore: String = "",
    /** 绩点：实测是**裸数字**（`3.4`、`5`）或空串，必须用宽松序列化器 */
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("JD") val gpa: String = "",
    @SerialName("XDZT") val readStatusCode: String = "",
    /** 成绩学年（仅已修课程有），如 "2024-2025" */
    @SerialName("XNMC") val gradedYear: String = "",
    /** 成绩学期（仅已修课程有），如 "1" */
    @SerialName("XQMMC") val gradedTerm: String = "",
    /** 建议修读学年（全部课程都有），如 "2024-2025" */
    @SerialName("JYXDXNMC") val plannedYear: String = "",
    /** 建议修读学期（全部课程都有），如 "3" / "12" */
    @SerialName("JYXDXQMC") val plannedTerm: String = "",
    @SerialName("KCXZMC") val courseNature: String = "",
    @SerialName("KCLBMC") val courseCategory: String = "",
    @SerialName("XSXXXX") val hoursComposition: String = "",
    @SerialName("SFJHKC") val isInPlan: String = ""
) {
    /**
     * 展示用学年：优先成绩学年，回退到建议修读学年。
     *
     * 已修课程两者一致；在修/未修课程只有建议修读学年。
     */
    val academicYear: String
        get() = gradedYear.trim().ifEmpty { plannedYear.trim() }

    /**
     * 展示用学期。
     *
     * 注意教务的学期编码有两套：成绩学期是 `"1"`/`"2"`，
     * 建议修读学期是 `"3"`(第一学期)/`"12"`(第二学期)。两者在 UI 上
     * 都会经 [com.xingheyuzhuan.shiguangschedule.ui.campus.AcademicTreeBuilder.formatYearTerm]
     * 的 unknown 分支显示成「第3学期」——为避免这种误导，
     * 这里做一次编码归一：`3`→`1`、`12`→`2`。
     */
    val term: String
        get() {
            val graded = gradedTerm.trim()
            if (graded.isNotEmpty()) return graded
            return when (plannedTerm.trim()) {
                "3" -> "1"
                "12" -> "2"
                else -> plannedTerm.trim()
            }
        }

    /** 中文化的修读状态，供 UI 直接展示。 */
    val readStatus: String get() = translateReadStatus(readStatusCode)
}

/**
 * 计划内 / 计划外课程合并器。
 *
 * ## 为什么必须有这一步
 *
 * 教务把同一个计划点的课程拆在**两个接口**里返回：
 *
 * | 接口 | 含义 |
 * |---|---|
 * | `Kcxx`（计划内） | 培养方案内的课 |
 * | `FKcxx`（计划外） | 方案外的课 —— **部分节点的课只在这里** |
 *
 * 最典型的是「通识选修」：`Kcxx` 返回空数组，课程（博弈策略思维、
 * 创新创业之创意技术）只在 `FKcxx` 里。该节点要求 6 学分 / 已获 4 学分，
 * 只查一个接口就会看到「有学分但没课」，被误判成数据缺失。
 *
 * 实测修复后课程记录从 135 → 137 条，并新出现「普通公选课」类别。
 *
 * ## 去重是必须的
 *
 * 两个接口在多数节点上返回**重叠**内容（实测思政军事与体育 `Kcxx` 13 条 /
 * `FKcxx` 12 条几乎完全重叠），不去重课程数会翻倍。
 *
 * 去重键与参考实现一致：`课程号 + 成绩学年 + 学期 + 成绩`。
 * **计划内优先** —— 同一门课两个接口都有时保留计划内那条。
 */
object AcademicCourseMerger {

    /** 去重键，对应参考实现里的 `('课程号','成绩学年','学期','成绩')`。 */
    fun dedupKey(course: AcademicCourseDto): String =
        "${course.courseCode}|${course.academicYear}|${course.term}|${course.score}"

    /**
     * 合并计划内与计划外课程，保持顺序稳定：先全部计划内，再追加计划外新增的。
     *
     * @return 去重后的合并列表
     */
    fun merge(
        inPlan: List<AcademicCourseDto>,
        extra: List<AcademicCourseDto>
    ): List<AcademicCourseDto> {
        if (extra.isEmpty()) return inPlan
        if (inPlan.isEmpty()) return extra

        val merged = LinkedHashMap<String, AcademicCourseDto>(inPlan.size + extra.size)
        inPlan.forEach { merged[dedupKey(it)] = it }
        // 计划内优先：已存在的键不覆盖
        extra.forEach { merged.putIfAbsent(dedupKey(it), it) }
        return merged.values.toList()
    }
}

/**
 * 第二类课（非正式学时）记录。
 *
 * 对应接口 `POST /cjcx/cjcx_cxFzskXscj.html?doType=query&gnmkdm=N305012`，
 * 返回 jqGrid 包装结构 [NonFormalCourseResponse]。
 *
 * ## 两层分数（重要）
 *
 * 实测「阳光体育」一条记录同时返回 `cj="通过"` 与 `bfzcj="75"`。
 * 若只映射 [result] 会误以为该课程没有分数，故 [rawScore] / [scoreNote] 一并保留。
 *
 * ## 学分为 0 是正常的
 *
 * 第二类课属于非正式课程，不计学分，实测 5 条记录的 `xf` 全为 "0"。
 * 「时长」应取 [hours]（教务字段 `xssh`）。
 *
 * ## 数字/字符串混用
 *
 * 与培养计划课程接口同样的风险：`xf` / `xssh` / `bfzcj` / `cj` 都会在不同记录上
 * 返回数字或字符串。因此这些字段一律用 [LenientStringSerializer]，
 * 避免一条记录的 `cj: 75`（数字）把整个学期的查询结果打挂。
 */
@Serializable
data class NonFormalCourseDto(
    @SerialName("kch") val courseCode: String = "",
    @SerialName("kcmc") val courseName: String = "",
    /** 结论：数值或「通过」/「不通过」 */
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("cj") val result: String = "",
    /** 真正的报告分，可能为空 */
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("bfzcj") val rawScore: String = "",
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("cjbz") val scoreNote: String = "",
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("xf") val credits: String = "",
    /** 学时数 —— UI 上的「时长」 */
    @Serializable(with = LenientStringSerializer::class)
    @SerialName("xssh") val hours: String = "",
    @SerialName("kclbmc") val category: String = "",
    @SerialName("kcgsmc") val ownership: String = "",
    @SerialName("jsxm") val teacher: String = "",
    @SerialName("xnmmc") val academicYear: String = "",
    @SerialName("xqmmc") val term: String = ""
)

/**
 * 第二类课查询的 jqGrid 响应包装。
 *
 * `totalResult` 为总记录数，用于翻页终止判断（累加 `items` 直到
 * 收集数 ≥ [totalResult] 或返回空数组）。
 */
@Serializable
data class NonFormalCourseResponse(
    val items: List<NonFormalCourseDto> = emptyList(),
    val totalResult: Int = 0,
    val totalPage: Int = 0,
    val currentPage: Int = 1,
    val pageSize: Int = 0
)
