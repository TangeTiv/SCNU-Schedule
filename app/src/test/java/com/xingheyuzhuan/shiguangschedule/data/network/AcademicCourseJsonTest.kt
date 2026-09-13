package com.xingheyuzhuan.shiguangschedule.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学业情况接口的 JSON 反序列化测试。
 *
 * ## 为什么必须有这组测试
 *
 * 教务对「值看起来像数字」的字段会返回**两种 JSON 类型**，而且不稳定。
 * 实测踩到的真实故障：`JD`（绩点）返回裸数字 `3.4`，
 * 而 DTO 当时声明为 `String`，导致
 *
 * ```
 * Unexpected JSON token at offset 390: Expected quotation mark '"', but had '3'
 * instead at path: $[0].JD
 * ```
 *
 * **9 个计划点、91 门课的课程明细全部反序列化失败**，界面上表现为
 * 「暂无课程明细」。因为整段 JSON 解析是原子的，一条记录的一个字段类型不对，
 * 整个计划点的数据就全丢了 —— 这类故障必须在单元测试层拦死。
 *
 * 这些测试用**教务真实返回的片段**作为输入，覆盖 `JD` 的三种形态：
 * 小数数字、整数数字、空字符串。
 */
class AcademicCourseJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ═══════════════════════════════════════════════════════════════════════
    // JD（绩点）：数字与字符串混用
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parses gpa returned as a decimal number`() {
        // 实测原文：{"JD":3.4,...}
        val raw = """[{"KCH":"TSC45560","KCMC":"思想道德与法治","XF":"3.0","JD":3.4}]"""
        val items = json.decodeFromString<List<AcademicCourseDto>>(raw)
        assertEquals(1, items.size)
        assertEquals("TSC45560", items[0].courseCode)
        assertEquals("3.4", items[0].gpa)
    }

    @Test
    fun `parses gpa returned as an integer number`() {
        // 实测原文：{"JD":5,...} 与 {"JD":3,...}
        val raw = """[{"KCMC":"四史","JD":5},{"KCMC":"专业必修课","JD":3}]"""
        val items = json.decodeFromString<List<AcademicCourseDto>>(raw)
        assertEquals(2, items.size)
        assertEquals("5", items[0].gpa)
        assertEquals("3", items[1].gpa)
    }

    @Test
    fun `parses gpa returned as an empty string`() {
        // 未修/在修课程没有绩点，实测返回空串
        val raw = """[{"KCMC":"大学日语（1）","JD":"","XDZT":"3"}]"""
        val items = json.decodeFromString<List<AcademicCourseDto>>(raw)
        assertEquals("", items[0].gpa)
        assertEquals("3", items[0].readStatusCode)
    }

    @Test
    fun `parses a full real-world record with numeric gpa`() {
        // 完全按实测日志拼出的真实记录：JD 是数字，其余是字符串
        val raw = """
            [{
              "KCH_ID": "C784D20E7B905777E0530C00F80A653C",
              "KCMC": "思想道德与法治",
              "XDZT": "4",
              "KCLBDM": "01",
              "JYXDXNM": "2024",
              "KCYWMC": "Ideological Morality and Rule of Law",
              "XSXXXX": "理论(3.0)",
              "KCXZMC": "必修课",
              "KCLBMC": "通识教育课程",
              "XF": "3.0",
              "KCH": "TSC45560",
              "KCZT": 1,
              "JYXDXNMC": "2024-2025",
              "SFJHKC": "是",
              "JYXDXQMC": "1",
              "CJ": "84",
              "MAXCJ": "84",
              "JD": 3.4,
              "XNMC": "2024-2025",
              "XQMMC": "1",
              "KCZYXXS": "1",
              "JYXDXQM": "3",
              "ZYZGKCBJ": "否"
            }]
        """.trimIndent()

        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("思想道德与法治", item.courseName)
        assertEquals("3.0", item.credits)
        assertEquals("84", item.score)
        assertEquals("3.4", item.gpa)
        assertEquals("4", item.readStatusCode)
        assertEquals("已修", item.readStatus)
        // 已修课程：成绩学年与建议修读学年都有值，优先取成绩学年
        assertEquals("2024-2025", item.academicYear)
        assertEquals("1", item.term)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 学年 / 学期：两套字段的合并策略
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `graded year wins when both graded and planned year exist`() {
        val raw = """[{"KCMC":"课程","XNMC":"2024-2025","XQMMC":"2",
                       "JYXDXNMC":"2023-2024","JYXDXQMC":"3"}]"""
        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("2024-2025", item.academicYear)
        assertEquals("2", item.term)
    }

    @Test
    fun `falls back to planned year when graded year is absent`() {
        // 在修/未修课程实测只有 JYXDXNMC / JYXDXQMC
        val raw = """[{"KCMC":"计算机网络原理","XDZT":"1",
                       "JYXDXNMC":"2025-2026","JYXDXQMC":"1"}]"""
        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("2025-2026", item.academicYear)
        assertEquals("1", item.term)
    }

    @Test
    fun `normalises planned term codes 3 and 12 to semester numbers`() {
        // 建议修读学期的编码是 3(第一学期) / 12(第二学期)，
        // 与成绩学期的 1 / 2 不同，必须归一，否则 UI 会显示「第3学期」
        val raw = """[{"KCMC":"A","JYXDXNMC":"2024-2025","JYXDXQMC":"3"},
                       {"KCMC":"B","JYXDXNMC":"2024-2025","JYXDXQMC":"12"}]"""
        val items = json.decodeFromString<List<AcademicCourseDto>>(raw)
        assertEquals("1", items[0].term)
        assertEquals("2", items[1].term)
    }

    @Test
    fun `both year fields empty yields empty labels`() {
        val raw = """[{"KCMC":"无数据课程"}]"""
        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("", item.academicYear)
        assertEquals("", item.term)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 学分等其它字段的宽松读取
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parses numeric credits and score without failing`() {
        // XF 实测是字符串，但同族字段出现过数字，这里锁住宽容行为
        val raw = """[{"KCMC":"课程","XF":2,"CJ":85,"MAXCJ":90}]"""
        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("2", item.credits)
        assertEquals("85", item.score)
        assertEquals("90", item.maxScore)
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """[{"KCMC":"课程","KCZT":1,"KCZYXXS":"1","JYXDXQM":"3","KCLBDM":"01"}]"""
        val item = json.decodeFromString<List<AcademicCourseDto>>(raw).single()
        assertEquals("课程", item.courseName)
    }

    @Test
    fun `parses empty array as empty list`() {
        assertTrue(json.decodeFromString<List<AcademicCourseDto>>("[]").isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 第二类课（非正式学时）：jqGrid 包装 + 同样的数字/字符串风险
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parses non formal record with numeric hours`() {
        val raw = """
            {"items":[{"kch":"21590501","kcmc":"阳光体育","cj":"通过","bfzcj":"75",
                       "xf":"0","xssh":"16","kcgsmc":"朋辈教育（非正式）",
                       "xnmmc":"2024-2025","xqmmc":"1"}],
             "totalResult":1,"totalPage":1,"currentPage":1,"pageSize":15}
        """.trimIndent()
        val resp = json.decodeFromString<NonFormalCourseResponse>(raw)
        assertEquals(1, resp.totalResult)
        assertEquals("阳光体育", resp.items[0].courseName)
        assertEquals("通过", resp.items[0].result)
        assertEquals("75", resp.items[0].rawScore)
        assertEquals("16", resp.items[0].hours)
    }

    @Test
    fun `parses non formal record with raw numeric fields`() {
        // 防御性：这些字段若返回裸数字也必须能解析，否则一个学期的数据全丢
        val raw = """
            {"items":[{"kcmc":"课程","cj":75,"bfzcj":75,"xf":0,"xssh":16,"cjbz":75}],
             "totalResult":1}
        """.trimIndent()
        val resp = json.decodeFromString<NonFormalCourseResponse>(raw)
        assertEquals("75", resp.items[0].result)
        assertEquals("75", resp.items[0].rawScore)
        assertEquals("0", resp.items[0].credits)
        assertEquals("16", resp.items[0].hours)
    }

    @Test
    fun `parses non formal empty term as zero total`() {
        // 查无数据的学期实测返回 items:[] 且 totalResult:0 —— 不是错误
        val raw = """{"items":[],"totalResult":0,"totalPage":0,"currentPage":1,"pageSize":15}"""
        val resp = json.decodeFromString<NonFormalCourseResponse>(raw)
        assertTrue(resp.items.isEmpty())
        assertEquals(0, resp.totalResult)
    }

    @Test
    fun `missing items key defaults to empty list`() {
        val resp = json.decodeFromString<NonFormalCourseResponse>("{}")
        assertTrue(resp.items.isEmpty())
        assertEquals(0, resp.totalResult)
    }
}
