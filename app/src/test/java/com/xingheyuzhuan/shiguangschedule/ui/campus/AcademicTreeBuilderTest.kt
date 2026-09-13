package com.xingheyuzhuan.shiguangschedule.ui.campus

import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicNonFormalCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicPlanNodeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AcademicTreeBuilder] / [AcademicUiState] 的离线测试。
 *
 * 这组测试锁住的是**标签页划分规则**——本功能最核心、也最容易被后续改动破坏的逻辑：
 *
 * | 树的 depth | 渲染方式 |
 * |---|---|
 * | 0 | 一级标签 |
 * | 1 | 二级标签 |
 * | ≥2 | 折叠面板（逐级嵌套） |
 *
 * 同时覆盖两个真实数据里出现过的特殊分支：
 * - **空壳分支**：「外语类」自身 0 门课但有子节点，必须能展开看到「英语 / 日语」
 * - **空叶节点**：「通识选修」既无课程也无子节点，要保留卡片显示学分缺口
 */
class AcademicTreeBuilderTest {

    // ─────────────────────────────────────────────────────────────────────────
    // 测试数据：复刻真实账号的培养计划形状（精简版）
    // ─────────────────────────────────────────────────────────────────────────

    private fun node(
        id: String,
        parentId: String?,
        name: String,
        required: Float,
        earned: Float,
        sortOrder: Int,
        passed: Boolean = false
    ) = AcademicPlanNodeEntity(
        id = id,
        parentId = parentId,
        name = name,
        requiredCredits = required,
        earnedCredits = earned,
        passed = passed,
        sortOrder = sortOrder
    )

    private fun course(
        id: Long,
        nodeId: String,
        name: String,
        credits: String,
        score: String = "",
        readStatusCode: String = "4",
        year: String = "2024-2025",
        term: String = "1"
    ) = AcademicCourseEntity(
        id = id,
        nodeId = nodeId,
        courseCode = "C$id",
        courseName = name,
        credits = credits,
        score = score,
        readStatusCode = readStatusCode,
        readStatus = translateStatus(readStatusCode),
        academicYear = year,
        term = term
    )

    private fun translateStatus(code: String) = when (code) {
        "4" -> "\u5df2\u4fee"
        "1" -> "\u5728\u4fee"
        else -> "\u672a\u4fee"
    }

    /**
     * 树形：
     * ```
     * 通识教育            depth0
     *  ├ 通识必修         depth1
     *  │  ├ 思政军事与体育 depth2  ← 2 门课
     *  │  └ 外语类        depth2  ← 0 门课，2 个子节点
     *  │     ├ 英语       depth3  ← 1 门课
     *  │     └ 日语       depth3  ← 1 门课（未修）
     *  └ 通识选修         depth1  ← 空叶节点
     * 大类教育            depth0
     *  └ 大类必修         depth1  ← 1 门课
     * 专业教育            depth0
     *  └ 专业选修         depth1  ← 0 门课，2 个子节点
     *     ├ 分模块选修    depth2  ← 0 门课，1 个子节点
     *     │  └ D人工智能  depth3  ← 1 门课
     *     └ 任选模块      depth2  ← 1 门课
     * ```
     */
    private val nodes = listOf(
        node("GENERAL", null, "\u901a\u8bc6\u6559\u80b2", 43.5f, 38.5f, 0),
        node("GEN_REQ", "GENERAL", "\u901a\u8bc6\u5fc5\u4fee", 32.5f, 30.5f, 1),
        node("POLITICS", "GEN_REQ", "\u601d\u653f\u519b\u4e8b\u4e0e\u4f53\u80b2", 24.5f, 22.5f, 2),
        node("LANG", "GEN_REQ", "\u5916\u8bed\u7c7b", 8f, 8f, 3, passed = true),
        node("ENGLISH", "LANG", "\u82f1\u8bed", 8f, 8f, 4, passed = true),
        node("JAPANESE", "LANG", "\u65e5\u8bed", 8f, 0f, 5),
        node("GEN_SEL", "GENERAL", "\u901a\u8bc6\u9009\u4fee", 6f, 4f, 6),
        node("CATEGORY", null, "\u5927\u7c7b\u6559\u80b2", 39.5f, 35.5f, 7),
        node("CAT_REQ", "CATEGORY", "\u5927\u7c7b\u5fc5\u4fee", 34.5f, 33.5f, 8),
        node("MAJOR", null, "\u4e13\u4e1a\u6559\u80b2", 56f, 35f, 9),
        node("MAJOR_SEL", "MAJOR", "\u4e13\u4e1a\u9009\u4fee", 17f, 2f, 10),
        node("MAJOR_MOD", "MAJOR_SEL", "\u5206\u6a21\u5757\u9009\u4fee", 9.5f, 0f, 11),
        node("MOD_D", "MAJOR_MOD", "D\u4eba\u5de5\u667a\u80fd", 9.5f, 0f, 12),
        node("MAJOR_OPT", "MAJOR_SEL", "\u4efb\u9009\u6a21\u5757", 0f, 2f, 13, passed = true)
    )

    private val courses = listOf(
        course(1, "POLITICS", "\u601d\u60f3\u9053\u5fb7\u4e0e\u6cd5\u6cbb", "3.0", "84"),
        course(2, "POLITICS", "\u5f62\u52bf\u4e0e\u653f\u7b56", "2.0", "", readStatusCode = "1"),
        course(3, "ENGLISH", "\u57fa\u7840\u82f1\u8bed\uff081\uff09", "2.0", "87"),
        course(4, "JAPANESE", "\u5927\u5b66\u65e5\u8bed\uff081\uff09", "2.0", "", readStatusCode = "3"),
        course(5, "CAT_REQ", "\u9ad8\u7b49\u6570\u5b66", "6.0", "84"),
        course(6, "MOD_D", "\u4eba\u5de5\u667a\u80fd\u5bfc\u8bba", "3.0", "", readStatusCode = "1"),
        course(7, "MAJOR_OPT", "\u6570\u5b66\u5efa\u6a21\u65b9\u6cd5", "2.0", "92")
    )

    private fun build() = AcademicTreeBuilder.build(nodes, courses)

    // ═══════════════════════════════════════════════════════════════════════
    // 一级标签 = depth 0
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `level one tabs are exactly the depth zero nodes in order`() {
        val state = build()
        assertEquals(
            listOf("\u901a\u8bc6\u6559\u80b2", "\u5927\u7c7b\u6559\u80b2", "\u4e13\u4e1a\u6559\u80b2"),
            state.level1Nodes.map { it.name }
        )
        assertTrue(state.level1Nodes.all { it.depth == 0 })
    }

    @Test
    fun `assigns depths by tree shape not by source order`() {
        val state = build()
        val byId = state.nodeById
        assertEquals(0, byId.getValue("GENERAL").depth)
        assertEquals(1, byId.getValue("GEN_REQ").depth)
        assertEquals(2, byId.getValue("POLITICS").depth)
        assertEquals(2, byId.getValue("LANG").depth)
        // 「英语」比「外语类」深一层 —— 这一点曾在美国参考实现的 depth 标注里出错
        assertEquals(3, byId.getValue("ENGLISH").depth)
        assertEquals(3, byId.getValue("JAPANESE").depth)
        assertEquals(3, byId.getValue("MOD_D").depth)
    }

    @Test
    fun `flat nodes are ordered depth first`() {
        val state = build()
        assertEquals("GENERAL", state.flatNodes.first().id)
        val names = state.flatNodes.map { it.name }
        assertTrue(
            names.indexOf("\u901a\u8bc6\u6559\u80b2") < names.indexOf("\u901a\u8bc6\u5fc5\u4fee")
        )
        assertTrue(
            names.indexOf("\u5916\u8bed\u7c7b") < names.indexOf("\u82f1\u8bed")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 二级标签 = depth 1
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `level two tabs are the depth one children of a level one tab`() {
        val state = build()
        val tabs = state.level2TabsOf("GENERAL").map { it.name }
        assertEquals(
            listOf("\u901a\u8bc6\u5fc5\u4fee", "\u901a\u8bc6\u9009\u4fee"),
            tabs
        )
    }

    @Test
    fun `level two tabs of major education excludes deeper nodes`() {
        val state = build()
        assertEquals(
            listOf("\u4e13\u4e1a\u9009\u4fee"),
            state.level2TabsOf("MAJOR").map { it.name }
        )
    }

    @Test
    fun `level two tabs fall back to the node itself when it has no children`() {
        // 「大类教育」下只有一个子节点，UI 会隐藏二级标签行直接展示内容
        val state = build()
        val tabs = state.level2TabsOf("CATEGORY")
        assertEquals(1, tabs.size)
        assertEquals("\u5927\u7c7b\u5fc5\u4fee", tabs[0].name)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 折叠面板 —— 严格按 depth 划分（不做任何"提升"）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `leaf level two tab that owns courses is its own single panel`() {
        val state = build()
        // 二级标签「大类必修」自己有课且是叶子 → 它自己就是唯一面板
        assertEquals(
            listOf("\u5927\u7c7b\u5fc5\u4fee"),
            state.panelNodesOf("CAT_REQ").map { it.name }
        )
    }

    @Test
    fun `level two tab with children lists its direct children as panels`() {
        val state = build()
        // 「专业选修」自身 0 门但有 2 个子节点 → 列出**直接子节点**作为面板。
        // 注意与旧行为的关键差异：不再把孙节点提升上来，
        // 「分模块选修」保留自己的层级，展开它才能看到 D人工智能。
        assertEquals(
            listOf("\u5206\u6a21\u5757\u9009\u4fee", "\u4efb\u9009\u6a21\u5757"),
            state.panelNodesOf("MAJOR_SEL").map { it.name }
        )
    }

    @Test
    fun `panels of a node are exactly its direct children regardless of depth`() {
        val state = build()
        // 「分模块选修」(depth2) 的子节点是 depth3 的 D人工智能 —— 层级不影响划分规则
        assertEquals(
            listOf("D\u4eba\u5de5\u667a\u80fd"),
            state.panelNodesOf("MAJOR_MOD").map { it.name }
        )
    }

    @Test
    fun `empty leaf is still rendered as a panel so its gap stays visible`() {
        val state = build()
        // 「通识选修」既无课程也无子节点 —— 仍要返回它，以便展示「缺 2 学分」
        val panels = state.panelNodesOf("GEN_SEL")
        assertEquals(1, panels.size)
        assertEquals("\u901a\u8bc6\u9009\u4fee", panels[0].name)
        assertTrue(panels[0].isEmptyLeaf)
    }

    @Test
    fun `descendants of a node are collected depth first`() {
        val state = build()
        assertEquals(
            listOf(
                "\u5206\u6a21\u5757\u9009\u4fee",
                "D\u4eba\u5de5\u667a\u80fd",
                "\u4efb\u9009\u6a21\u5757"
            ),
            state.descendantsOf("MAJOR_SEL").map { it.name }
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 课程数递归汇总（教务只在叶子上挂课）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `rolls up course counts from descendants to parents`() {
        val state = build()
        // 专业教育：自身 0 门，子树 = 分模块选修(D人工智能 1 门) + 任选模块 1 门 = 2 门
        val major = state.nodeById.getValue("MAJOR")
        assertTrue("专业教育自身无课", major.courses.isEmpty())
        assertEquals(2, major.totalCourseCount)
        assertTrue("课程来自子节点，文案应显示「含 N 门」", major.hasNestedCourses)

        // 专业选修：自身 0 门，子树同上 = 2 门
        val majorSel = state.nodeById.getValue("MAJOR_SEL")
        assertEquals(2, majorSel.totalCourseCount)

        // 分模块选修：自身 0 门，子树 = D人工智能 1 门
        val mod = state.nodeById.getValue("MAJOR_MOD")
        assertEquals(1, mod.totalCourseCount)
        assertTrue(mod.hasNestedCourses)
    }

    @Test
    fun `rollup does not double count and matches leaf sum`() {
        val state = build()
        // 全树课程总数 = 测试数据里的 7 门（每个叶子只挂一次）
        val expected = courses.size
        val rootTotal = state.level1Nodes.sumOf { it.totalCourseCount }
        assertEquals(expected, rootTotal)
    }

    @Test
    fun `rollup sums a level two tab across its subtree`() {
        val state = build()
        // 通识必修：自身 0 门，子树 = 思政 2 + 英语 1 + 日语 1 = 4 门
        // （注意不能只加直接子节点：外语类自身 0 门，课程在它的子节点上）
        val genReq = state.nodeById.getValue("GEN_REQ")
        assertEquals(4, genReq.totalCourseCount)
        assertTrue(genReq.hasNestedCourses)
    }

    @Test
    fun `leaf with own courses has nested flag false`() {
        val state = build()
        val politics = state.nodeById.getValue("POLITICS")
        assertEquals(2, politics.totalCourseCount)
        assertFalse("自身就有课，不该显示「含」", politics.hasNestedCourses)

        val english = state.nodeById.getValue("ENGLISH")
        assertEquals(1, english.totalCourseCount)
        assertFalse(english.hasNestedCourses)
    }

    @Test
    fun `empty leaf counts as zero courses`() {
        val state = build()
        val genSel = state.nodeById.getValue("GEN_SEL")
        assertEquals(0, genSel.totalCourseCount)
        assertFalse(genSel.hasNestedCourses)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 学分与状态标记
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `computes credit gap and clamps it at zero`() {
        val state = build()
        val political = state.nodeById.getValue("POLITICS")
        assertEquals(2f, political.gapCredits, 0.001f)

        // 已超出要求的节点（任选模块 要求0 获得2）缺口必须是 0 而不是 -2
        val optional = state.nodeById.getValue("MAJOR_OPT")
        assertEquals(0f, optional.gapCredits, 0.001f)
    }

    @Test
    fun `formats credits without trailing zeros`() {
        assertEquals("8", AcademicTreeBuilder.formatCredits(8f))
        assertEquals("43.5", AcademicTreeBuilder.formatCredits(43.5f))
        assertEquals("0", AcademicTreeBuilder.formatCredits(0f))
        assertEquals("31.5", AcademicTreeBuilder.formatCredits(31.5f))
    }

    @Test
    fun `flags empty leaf distinctly from a node that has nested courses`() {
        val state = build()
        val lang = state.nodeById.getValue("LANG")
        assertTrue("外语类自身无课但后代有课", lang.hasNestedCourses)
        assertFalse("外语类不是空叶节点", lang.isEmptyLeaf)

        val genSel = state.nodeById.getValue("GEN_SEL")
        assertFalse(genSel.hasNestedCourses)
        assertTrue("通识选修是空叶节点", genSel.isEmptyLeaf)

        val politics = state.nodeById.getValue("POLITICS")
        assertFalse(politics.hasNestedCourses)
        assertFalse(politics.isEmptyLeaf)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 课程
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attaches courses to their own node only`() {
        val state = build()
        assertEquals(2, state.nodeById.getValue("POLITICS").courses.size)
        assertEquals(1, state.nodeById.getValue("ENGLISH").courses.size)
        // 空壳分支自身不带课程
        assertTrue(state.nodeById.getValue("LANG").courses.isEmpty())
        assertTrue(state.nodeById.getValue("GEN_REQ").courses.isEmpty())
    }

    @Test
    fun `unsynced course has blank score and shows placeholder later`() {
        val state = build()
        val japanese = state.nodeById.getValue("JAPANESE").courses.single()
        assertEquals("", japanese.score)
        assertEquals("3", japanese.readStatusCode)
        assertEquals("2.0 \u5b66\u5206", japanese.creditsLabel)
    }

    @Test
    fun `unsynced course has empty year term label`() {
        val state = build()
        val japanese = state.nodeById.getValue("JAPANESE").courses.single()
        assertEquals("", japanese.yearTermLabel)
    }

    @Test
    fun `formats year and term into readable label`() {
        assertEquals(
            "2024-2025 \u7b2c1\u5b66\u671f",
            AcademicTreeBuilder.formatYearTerm("2024-2025", "1")
        )
        assertEquals(
            "2025-2026 \u7b2c2\u5b66\u671f",
            AcademicTreeBuilder.formatYearTerm("2025-2026", "2")
        )
        assertEquals("", AcademicTreeBuilder.formatYearTerm("", ""))
        // 只有学年没有学期时不能多出一个分隔空格
        assertEquals("2024-2025", AcademicTreeBuilder.formatYearTerm("2024-2025", ""))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 空数据与非正式学时
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `returns empty state when there are no nodes`() {
        val state = AcademicTreeBuilder.build(emptyList(), emptyList())
        assertTrue(state.isEmpty)
        assertTrue(state.level1Nodes.isEmpty())
        assertTrue(state.level2TabsOf("anything").isEmpty())
    }

    @Test
    fun `courses without a matching node are ignored`() {
        val orphan = listOf(course(99, "NO_SUCH_NODE", "\u5b64\u513f\u8bfe\u7a0b", "1.0", "90"))
        val state = AcademicTreeBuilder.build(nodes, orphan)
        assertEquals(nodes.size, state.flatNodes.size)
        assertTrue(state.flatNodes.all { it.courses.none { c -> c.courseName == "\u5b64\u513f\u8bfe\u7a0b" } })
    }

    @Test
    fun `builds non formal state and sums hours`() {
        val entities = listOf(
            nonFormal(1, "\u9633\u5149\u4f53\u80b2", "2024-2025", "1", "16", "\u901a\u8fc7"),
            nonFormal(2, "\u804c\u4e1a\u751f\u6daf\u89c4\u5212\u5927\u8d5b", "2024-2025", "1", "16", "\u901a\u8fc7"),
            nonFormal(3, "\u65c5\u884c\u5206\u4eab\u4f1a", "2024-2025", "2", "0", "\u4e0d\u901a\u8fc7"),
            nonFormal(4, "\u521b\u65b0\u521b\u4e1a\u4e0e\u521b\u65b0\u52b3\u52a8\u5468", "2025-2026", "1", "32", "\u901a\u8fc7")
        )
        val state = AcademicTreeBuilder.buildNonFormal(entities)

        assertEquals(4, state.courses.size)
        assertEquals(64, state.totalHours)
        assertEquals("16 \u5b66\u65f6", state.courses[0].hoursLabel)
        assertEquals("2024-2025 \u7b2c1\u5b66\u671f", state.courses[0].yearTermLabel)
        assertEquals("\u65c5\u884c\u5206\u4eab\u4f1a", state.courses[2].courseName)
    }

    @Test
    fun `non formal handles blank and malformed hours without crashing`() {
        val entities = listOf(
            nonFormal(1, "\u7a7a\u5b66\u65f6", "2024-2025", "1", "", "\u901a\u8fc7"),
            nonFormal(2, "\u975e\u6570\u5b57\u5b66\u65f6", "2024-2025", "1", "abc", "\u901a\u8fc7")
        )
        val state = AcademicTreeBuilder.buildNonFormal(entities)
        assertEquals(2, state.courses.size)
        // 无法解析的学时计入 0，而不是抛异常
        assertEquals(0, state.totalHours)
        assertEquals("\u2014", state.courses[0].hoursLabel)
    }

    @Test
    fun `non formal empty input returns empty state`() {
        val state = AcademicTreeBuilder.buildNonFormal(emptyList())
        assertTrue(state.isEmpty)
        assertEquals(0, state.totalHours)
    }

    private fun nonFormal(
        id: Long,
        name: String,
        year: String,
        term: String,
        hours: String,
        result: String
    ) = AcademicNonFormalCourseEntity(
        id = id,
        courseCode = "N$id",
        courseName = name,
        result = result,
        rawScore = "75",
        credits = "0",
        hours = hours,
        academicYear = year,
        term = term
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 健壮性：父子关系异常的工单数据
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `node pointing at missing parent is treated as root and not lost`() {
        val broken = listOf(
            node("A", "GONE", "\u5b64\u513f\u8282\u70b9", 1f, 0f, 0)
        )
        val state = AcademicTreeBuilder.build(broken, emptyList())
        assertEquals(1, state.flatNodes.size)
        assertEquals(0, state.flatNodes[0].depth)
        assertNull(state.flatNodes[0].parentId)
    }

    @Test
    fun `cyclic parent references terminate and keep every node`() {
        // A 的父是 B，B 的父是 A —— 畸形数据不能导致死循环或丢节点
        val cyclic = listOf(
            node("A", "B", "A", 1f, 0f, 0),
            node("B", "A", "B", 1f, 0f, 1)
        )
        val state = AcademicTreeBuilder.build(cyclic, emptyList())
        assertEquals(2, state.flatNodes.size)
    }
}
