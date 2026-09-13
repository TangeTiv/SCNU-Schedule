package com.xingheyuzhuan.shiguangschedule.data.network

/**
 * 培养计划点树的 HTML 解析器。
 *
 * ## 为什么要解析 HTML
 *
 * 教务的「学生学业情况查询」首页（`xsxyqk_cxXsxyqkIndex.html`）把**整棵培养计划树**
 * 以 JS 字符串拼装代码的形式内嵌在 HTML 源码里，形如：
 *
 * ```javascript
 * if ($("#ul51AF705881845A11E0630B00F80AD6ED").size() == 0 && '...' != '')
 * {
 *     $("<ul id='ul51AF705881845A11E0630B00F80AD6ED'>" +
 *         "<li id='li51AF705881855A11E0630B00F80AD6ED' ... fxfyqjd_id='<父ID>' ...>" +
 *         "<div class='title' data-content=''" +
 *         " xfyqjd_id='51AF705881855A11E0630B00F80AD6ED' jdkcsx='' leaf='' sfmjd='0' >" +
 *         "<p class='title1' id='p51AF705881855A11E0630B00F80AD6ED' yxxf='38.5' yqzdxf='43.5' sftg='0'>" +
 *         "通识教育&nbsp;" + $.i18n.get('yqxf')/* 要求学分 */ + ":43.5&nbsp;" + ... +
 *         "</li></ul>").appendTo($("#li51AF705881845A11E0630B00F80AD6ED"));
 * }
 * ```
 *
 * 由此可确定三件事：
 *
 * 1. **代码被按父节点分块**。块头 `$("#ul<PID>")` 里的 `<PID>` 就是当前块的父节点 id，
 *    块内出现的每个 `title1` 都是它的直接子节点。
 * 2. **节点名称存在于字符串字面量中**（紧随 `title1` 开标签之后，到
 *    `$.i18n.get('yqxf')` 之前）。注意教务同时写了 `data-content='...'` 属性，
 *    但那个属性在服务端渲染时就已被错误编码，**不可用**。
 * 3. **学分与通过状态**在 `title1` 的属性里：`yxxf`=已获得学分、
 *    `yqzdxf`=要求学分、`sftg`=是否通过（`'1'` 为通过）。
 *
 * ## 为什么不照搬参考 Python 实现
 *
 * 参考实现认为「名称必须用 headless 浏览器渲染 DOM 才能拿到」，因此引入了
 * playwright 依赖。**实测该结论不成立** —— 名称就在源码里，纯正则即可提取，
 * 这也让 Android 端彻底摆脱 WebView 依赖。
 *
 * ## 排序
 *
 * 代码块在源码中**不是**按树序排列的（例如「大类必修」的块出现在「大类教育」之前），
 * 因此不能直接用出现顺序当展示次序。本解析器在还原出父子关系后，
 * 按「根节点出现顺序 → 深度优先」重新遍历生成稳定顺序。
 */
object AcademicPlanTreeParser {

    /** 块头：`if ($("#ul<PID>").size() == 0` —— 捕获当前块的父节点 id。 */
    private val BLOCK_RE = Regex(
        """if\s*\(\s*\$\("#ul([0-9A-Za-z]+)"\)\s*\.\s*size\(\)\s*==\s*0"""
    )

    /**
     * 节点：`title1` 开标签 → 学分属性 → 名称（到 `i18n.get('yqxf')` 为止）。
     *
     * `DOT_MATCHES_ALL` 让 `[\s\S]` 的替代写法可读性更好；
     * 名称段的长度用 `{0,400}` 限幅，避免病态回溯。
     */
    private val NODE_RE = Regex(
        """<p class='title1' id='p([0-9A-Za-z]+)'""" +
                """[^>]*?yxxf='([^']*)'""" +
                """[^>]*?yqzdxf='([^']*)'""" +
                """[^>]*?sftg='([^']*)'[^>]*>""" +
                """([\s\S]{0,400}?)""" +
                """\$\s*\.\s*i18n\s*\.\s*get\('yqxf'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** HTML 实体 → 原字符（教务名称里最常见的是 `&nbsp;`）。 */
    private val ENTITY_MAP = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'"
    )

    /**
     * 解析首页 HTML，还原完整的培养计划点树。
     *
     * @param html 首页原始 HTML（UTF-8 解码后的字符串）
     * @return 按树序（根节点出现顺序 → 深度优先）排列的计划点列表；
     *         解析不到任何节点时返回空列表
     */
    fun parse(html: String): List<PlanNodeDto> {
        if (html.isEmpty()) return emptyList()

        // ── 第 1 步：找出所有块头位置，据此把源码切成数组块 ──
        val blockStarts = BLOCK_RE.findAll(html).map { it.range.first }.toList()
        if (blockStarts.isEmpty()) return emptyList()

        // 用 id 去重：同一节点在源码中会出现多次（多个分支共用模板），
        // 且不同分支给出的学分相同，故「首次出现者胜」。
        val byId = LinkedHashMap<String, PlanNodeDto>()

        blockStarts.forEachIndexed { index, start ->
            val parentId = BLOCK_RE.find(html, start)?.groupValues?.get(1) ?: return@forEachIndexed
            val end = blockStarts.getOrElse(index + 1) { html.length }
            val chunk = html.substring(start, end)

            NODE_RE.findAll(chunk).forEach { m ->
                val nodeId = m.groupValues[1]
                if (byId.containsKey(nodeId)) return@forEach

                val name = cleanName(m.groupValues[5])
                if (name.isEmpty()) return@forEach

                byId[nodeId] = PlanNodeDto(
                    id = nodeId,
                    // 先原样记下块头给出的父 id，合法性在第 2 步统一校正
                    parentId = parentId,
                    name = name,
                    requiredCredits = m.groupValues[3].toFloatOrNull() ?: 0f,
                    earnedCredits = m.groupValues[2].toFloatOrNull() ?: 0f,
                    passed = m.groupValues[4].trim() == "1"
                )
            }
        }

        if (byId.isEmpty()) return emptyList()

        // ── 第 2 步：校正父节点 ──
        // 代码块在源码中的顺序 ≠ 树序（例如「大类必修」的块出现在「大类教育」之前），
        // 所以第 1 步无法判断父节点是否存在。这里统一按「父 id 是否真的在集合里」校正：
        // 自引用或不存在的父 id 一律视为根节点。
        val resolved = byId.mapValues { (id, node) ->
            val parent = node.parentId
            if (parent != null && parent != id && byId.containsKey(parent)) {
                node
            } else {
                node.copy(parentId = null)
            }
        }

        // ── 第 3 步：按树序重排（根顺序 = 首次出现顺序，然后深度优先）──
        return flattenInTreeOrder(resolved)
    }

    /**
     * 从原始字符串片段中抽出干净的计划点名称。
     *
     * 片段形如 `通识教育&nbsp;` 或 `主修&nbsp;`，
     * 但也可能夹带拼接残渣（`" +`）与 JS 注释，需要一并剥掉。
     */
    private fun cleanName(raw: String): String {
        var s = raw
        // 只取第一个字符串字面量：JS 里名称后面会跟 `" + $.i18n.get(...)`
        s = s.substringBefore('"')
        // 去掉 JS 块注释
        s = s.replace(Regex("""/\*[\s\S]*?\*/"""), "")
        // 去掉换行与制表符
        s = s.replace(Regex("""[\r\n\t]+"""), " ")
        // 解开 HTML 实体
        ENTITY_MAP.forEach { (entity, ch) -> s = s.replace(entity, ch) }
        // 压缩连续空白
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * 按「根节点首次出现顺序 → 深度优先」展平树，保证展示次序稳定。
     *
     * 防御：父子关系若因教务数据异常形成环，[visited] 会终止遍历，不会死循环。
     */
    private fun flattenInTreeOrder(nodes: Map<String, PlanNodeDto>): List<PlanNodeDto> {
        val childrenOf = nodes.values
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }

        val roots = nodes.values.filter { it.parentId == null }
        val visited = HashSet<String>(nodes.size)
        val out = ArrayList<PlanNodeDto>(nodes.size)

        fun walk(node: PlanNodeDto) {
            if (!visited.add(node.id)) return
            out.add(node)
            childrenOf[node.id]?.forEach { walk(it) }
        }

        roots.forEach { walk(it) }

        // 兜底：因循环引用而未被访问到的节点，按原顺序追加，确保不丢数据
        nodes.values.forEach { if (it.id !in visited) out.add(it) }

        return out
    }
}
