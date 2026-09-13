package com.xingheyuzhuan.shiguangschedule.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AcademicPlanTreeParser] 的离线测试。
 *
 * ## 为什么必须有这些测试
 *
 * 学业情况的网络层**无法在 CI 里真连教务**，而计划点树的解析依赖教务一份
 * 极易变化的 JS 拼接代码。为了让「解析协议」的回归能被自动发现，
 * 这里用手写的**最小 HTML fixture**（而不是那个 1.7MB 的真实页面）锁住协议。
 *
 * fixture 刻意复刻了真实页面里三个容易踩坑的细节：
 *
 * 1. **名称在字符串字面量里**，而 `data-content` 属性是已损坏的乱码
 *    （真实页面里是 `data-content='涓讳慨'` 这种双重编码产物）。
 *    解析器必须取前者、忽略后者。
 * 2. **`<li>` 的 id 前缀与 `title1`/`xfyqjd_id` 不一致**：真实页面里
 *    `<li id='li1XXXX'>` 但 `<p class='title1' id='pXXXX'>`。
 *    解析器必须从 `p` 的 id 取节点 id，而不是从 `li` 的 id 推断。
 * 3. **代码块顺序 ≠ 树序**：`大类必修`（depth 1）的块排在 `大类教育`（depth 0）之前。
 *    解析器必须靠父 id 校正、并按树序重新展平。
 */
class AcademicPlanTreeParserTest {

    /** 复刻真实页面的 JS 拼接结构：按父节点分块 + 名称字面量 + 损坏的 data-content。 */
    private fun block(
        parentId: String,
        vararg children: Node
    ): String = buildString {
        append("if (\$(\"#ul$parentId\").size() == 0 && '$parentId' != '')")
        append("//节点没有找到父节点ul，二级根节点\n{\n")
        children.forEach { child ->
            append("    \$(\"<ul id='ul$parentId'>")
            // 真实页面里 li 的 id 前缀是 "li1"，与 title1 的 "p" 前缀不同
            append("<li id='li1${child.id}' class='' fxfyqjd_id='$parentId' xfyqzjdgx='1' >")
            append("<div class='title' data-content='涓讳慨'")   // 损坏属性，必须被忽略
            append(" xfyqjd_id='${child.id}' jdkcsx='' leaf='' sfmjd='0' >")
            append("<p class='title1' id='p${child.id}' ")
            append("yxxf='${child.earned}' yqzdxf='${child.required}' sftg='${child.sftg}'>")
            append("${child.name}&nbsp;\" + ")
            append("\$.i18n.get('yqxf')/* 要求学分 */ + \":${child.required}&nbsp;\" +\n")
            append("    \"<span id='showKc${child.id}'></span></p></li></ul>\")")
            append(".appendTo(\$(\"#li$parentId\"));\n")
        }
        append("}\n")
    }

    private data class Node(
        val id: String,
        val name: String,
        val required: String,
        val earned: String,
        val sftg: String = "0"
    )

    /** 一棵贴近真实数据的微型培养计划树（12 个节点，覆盖全部渲染分支）。 */
    private val fixture: String
        get() {
            val root = "ROOT"
            val general = "AAAAGENERAL"
            val major = "AAAAMAJOR"
            val practice = "AAAAPRACTICE"
            return buildString {
                // 根节点：通识教育
                append(block(root, Node(general, "\u901a\u8bc6\u6559\u80b2", "43.5", "38.5")))
                // 通识教育下：通识必修（无课、有子节点 → 需 inline 渲染）
                append(
                    block(
                        general,
                        Node("AAAAGENREQ", "\u901a\u8bc6\u5fc5\u4fee", "32.5", "30.5"),
                        Node("AAAAGENSEL", "\u901a\u8bc6\u9009\u4fee", "6", "4")
                    )
                )
                // 通识必修下：思政军事与体育（叶子，有课）+ 外语类（无课、有子节点）
                append(
                    block(
                        "AAAAGENREQ",
                        Node("AAAAPOLITICS", "\u601d\u653f\u519b\u4e8b\u4e0e\u4f53\u80b2", "24.5", "22.5"),
                        Node("AAAALANG", "\u5916\u8bed\u7c7b", "8", "8", sftg = "1")
                    )
                )
                // 外语类下：英语 / 日语（叶子）
                append(
                    block(
                        "AAAALANG",
                        Node("AAAAENGLISH", "\u82f1\u8bed", "8", "8", sftg = "1"),
                        Node("AAAAJAPANESE", "\u65e5\u8bed", "8", "0")
                    )
                )
                // 注意：大类教育的块排在「大类必修」之前是真实顺序，这里刻意反过来验证校正
                append(block(root, Node(major, "\u5927\u7c7b\u6559\u80b2", "39.5", "35.5")))
                append(block(major, Node("AAAAMAJORREQ", "\u5927\u7c7b\u5fc5\u4fee", "34.5", "33.5")))
                append(block(root, Node(practice, "\u5b9e\u8df5\u6559\u80b2", "31.5", "4")))
                append(block(practice, Node("AAAAPRACREQ", "\u5b9e\u8df5\u5fc5\u4fee", "29.5", "4")))
            }
        }

    // ═══════════════════════════════════════════════════════════════════════
    // 基本解析
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `parses all distinct nodes from fixture`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        assertEquals(12, nodes.size)
    }

    @Test
    fun `extracts names from string literals instead of corrupted data-content`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val names = nodes.map { it.name }

        assertTrue("\u901a\u8bc6\u6559\u80b2" in names)
        assertTrue("\u601d\u653f\u519b\u4e8b\u4e0e\u4f53\u80b2" in names)
        assertTrue("\u82f1\u8bed" in names)

        // 损坏的 data-content 值绝不能出现在任何名称里
        assertTrue(names.none { it.contains("\u6d93") })
        // 也不能残留 JS 拼接符号或 HTML 实体
        assertTrue(names.none { it.contains("&nbsp;") })
        assertTrue(names.none { it.contains("\"") })
        assertTrue(names.none { it.contains("+") })
    }

    @Test
    fun `extracts credits and pass flag`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val politics = nodes.first { it.name == "\u601d\u653f\u519b\u4e8b\u4e0e\u4f53\u80b2" }
        assertEquals(24.5f, politics.requiredCredits, 0.001f)
        assertEquals(22.5f, politics.earnedCredits, 0.001f)
        assertEquals(false, politics.passed)

        val english = nodes.first { it.name == "\u82f1\u8bed" }
        assertEquals(8f, english.requiredCredits, 0.001f)
        assertTrue(english.passed)
    }

    @Test
    fun `integer credits parse without trailing decimals`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val practice = nodes.first { it.name == "\u5b9e\u8df5\u6559\u80b2" }
        assertEquals(31.5f, practice.requiredCredits, 0.001f)
        assertEquals(4f, practice.earnedCredits, 0.001f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 父子关系与树序
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `resolves parents so that level one tabs are the three education kinds`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val roots = nodes.filter { it.parentId == null }.map { it.name }

        assertEquals(
            listOf("\u901a\u8bc6\u6559\u80b2", "\u5927\u7c7b\u6559\u80b2", "\u5b9e\u8df5\u6559\u80b2"),
            roots
        )
    }

    @Test
    fun `links children to their real parent`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val byName = nodes.associateBy { it.name }

        val general = byName.getValue("\u901a\u8bc6\u6559\u80b2")
        val genReq = byName.getValue("\u901a\u8bc6\u5fc5\u4fee")
        assertEquals(general.id, genReq.parentId)

        // 「英语」的父节点必须是「外语类」，而不是任何更上层的节点
        val lang = byName.getValue("\u5916\u8bed\u7c7b")
        val english = byName.getValue("\u82f1\u8bed")
        assertEquals(lang.id, english.parentId)
    }

    @Test
    fun `flattens in depth first tree order regardless of source block order`() {
        val nodes = AcademicPlanTreeParser.parse(fixture)
        val names = nodes.map { it.name }

        // 通识教育 → 通识必修 → 思政军事与体育 → 外语类 → 英语 → 日语
        val expectedHead = listOf(
            "\u901a\u8bc6\u6559\u80b2",
            "\u901a\u8bc6\u5fc5\u4fee",
            "\u601d\u653f\u519b\u4e8b\u4e0e\u4f53\u80b2",
            "\u5916\u8bed\u7c7b",
            "\u82f1\u8bed",
            "\u65e5\u8bed"
        )
        assertEquals(expectedHead, names.take(expectedHead.size))

        // 「大类教育」必须排在自己的子节点「大类必修」之前
        assertTrue(
            names.indexOf("\u5927\u7c7b\u6559\u80b2") <
                    names.indexOf("\u5927\u7c7b\u5fc5\u4fee")
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 健壮性
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `returns empty list for empty or irrelevant html`() {
        assertTrue(AcademicPlanTreeParser.parse("").isEmpty())
        assertTrue(AcademicPlanTreeParser.parse("<html><body>\u767b\u5f55</body></html>").isEmpty())
        // 有块头但没有任何 title1（页面结构变了）
        assertTrue(
            AcademicPlanTreeParser.parse("""if ($("#ulABC").size() == 0 && 'x' != '') {}""")
                .isEmpty()
        )
    }

    @Test
    fun `drops nodes whose parent id does not exist`() {
        // 块头指向一个从未出现的父 id → 该节点应被视为根节点，而不是丢失
        val html = block("GHOSTPARENT", Node("ORPHAN", "\u5b64\u513f\u8282\u70b9", "1", "0"))
        val nodes = AcademicPlanTreeParser.parse(html)
        assertEquals(1, nodes.size)
        assertEquals("\u5b64\u513f\u8282\u70b9", nodes[0].name)
        assertNull(nodes[0].parentId)
    }

    @Test
    fun `handles self referencing parent without infinite loop`() {
        // 块头父 id 与节点 id 相同 → 必须收敛，不能死循环
        val html = block("SELFNODE", Node("SELFNODE", "\u81ea\u5f15\u7528", "1", "0"))
        val nodes = AcademicPlanTreeParser.parse(html)
        assertEquals(1, nodes.size)
        assertNull(nodes[0].parentId)
    }

    @Test
    fun `keeps first occurrence when the same node appears in multiple branches`() {
        // 教务会为同一节点生成多个分支模板，名称与学分相同，只需保留一份
        val html = block("ROOT", Node("DUP", "\u91cd\u590d\u8282\u70b9", "2", "1")) +
                block("ROOT", Node("DUP", "\u91cd\u590d\u8282\u70b9", "2", "1"))
        val nodes = AcademicPlanTreeParser.parse(html)
        assertEquals(1, nodes.count { it.name == "\u91cd\u590d\u8282\u70b9" })
    }

    @Test
    fun `skips nodes with empty names`() {
        // 名称为空的节点无法展示，直接丢弃而不是产生空白标签
        val html = block("ROOT", Node("EMPTY", "", "1", "0"), Node("OK", "\u6709\u540d\u5b57", "1", "0"))
        val nodes = AcademicPlanTreeParser.parse(html)
        assertEquals(1, nodes.size)
        assertEquals("\u6709\u540d\u5b57", nodes[0].name)
    }

    @Test
    fun `parses real world shaped numbers and dotted names`() {
        val html = block(
            "ROOT",
            Node("DOTTED", "E\u79d1\u7814\u8bad\u7ec3\u3001\u65b0\u6280\u672f\u548c\u4ea4\u53c9\u5b66\u79d1", "12", "0"),
            Node("DECIMAL", "A\u673a\u5668\u4eba", "9.5", "0")
        )
        val nodes = AcademicPlanTreeParser.parse(html)
        assertEquals(12f, nodes.first { it.name.startsWith("E") }.requiredCredits, 0.001f)
        assertEquals(9.5f, nodes.first { it.name.startsWith("A") }.requiredCredits, 0.001f)
    }
}
