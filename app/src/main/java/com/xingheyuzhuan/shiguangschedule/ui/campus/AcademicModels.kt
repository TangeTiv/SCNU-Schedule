package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.runtime.Immutable
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicNonFormalCourseEntity
import com.xingheyuzhuan.shiguangschedule.data.db.main.AcademicPlanNodeEntity

// ═══════════════════════════════════════════════════════════════════════════
// 【学业情况】UI 模型与建树逻辑
//
// 设计要点（对应性能红线 2、5、6）：
//
// 1. **标签页划分不落库**。数据库里只有「节点 + parentId + depth 无关的原始事实」，
//    「哪些节点当一级标签、哪些当二级标签、哪些当折叠面板」全部在**这里现算**。
//    换一个年级/学院（培养计划层级不同）时无需改任何代码、也无需重新同步。
//
// 2. **建树是纯函数，且必须跑在后台线程**。33 个节点 + 135 条课程的分组、
//    排序、字符串格式化都是 CPU 工作，调用方（ViewModel）负责用
//    `.flowOn(Dispatchers.Default)` 把它挪出主线程。
//
// 3. 所有对外暴露的数据类都标 [Immutable]，让 Compose 能正确跳过重组。
// ═══════════════════════════════════════════════════════════════════════════

// ─────────────────────────────────────────────────────────────────────────────
// 标签层级常量
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 标签页划分规则的开关。
 *
 * - depth 0 → 一级标签（通识教育 / 大类教育 / 专业教育 / 实践教育）
 * - depth 1 → 二级标签（专业必修 / 专业选修 / …）
 * - depth ≥ 2 → 折叠面板，逐层嵌套
 *
 * 这些常量只作为**默认值**；[AcademicTreeBuilder] 内部对「深度 1 但自身没有课程、
 * 只有子节点」的分支会额外提升为可展开项，见 [AcademicNodeUi.needsInlineRender]。
 */
private const val LEVEL1_DEPTH = 0
private const val LEVEL2_DEPTH = 1

// ─────────────────────────────────────────────────────────────────────────────
// UI 模型
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 单门计划内课程的展示模型。
 *
 * @param score 成绩；未修课程为空串，UI 需显示占位符
 * @param readStatusCode 教务原始状态码（1/2/3/4），用于选择徽章配色
 * @param yearTermLabel 已拼好的「学年 学期」文案，如 "2024-2025 第1学期"；无数据时为空串
 */
@Immutable
data class AcademicCourseUi(
    val id: Long,
    val courseCode: String,
    val courseName: String,
    val creditsLabel: String,
    val score: String,
    val gpa: String,
    val readStatusCode: String,
    val readStatus: String,
    val yearTermLabel: String,
    val courseNature: String
)

/**
 * 培养计划点的展示模型（扁平化，带 [depth] 与 [parentId]）。
 *
 * 刻意**不做成递归 children 结构**：UI 用一棵「扁平列表 + 深度缩进」渲染手风琴，
 * 既避免深层嵌套的 Compose 重组开销，也让 LazyColumn 能以节点 id 稳定做 key。
 *
 * @param depth 0 = 一级标签；1 = 二级标签；≥2 = 折叠面板内容
 * @param gapCredits 未获得学分，已 clamp 到 ≥0
 * @param needsInlineRender 该节点自身无课程、但有子节点，因此必须作为可展开项渲染，
 *        不能因为它「没有课程」而被当成空叶节点。典型例子：「外语类」「专业选修」
 * @param isEmptyLeaf 真正的空节点：无课程、无子节点。保留卡片并显示「暂无课程明细」
 * @param totalCourseCount 该节点**及其全部后代**的课程总数
 * @param hasNestedCourses 课程是否来自子节点（即自身无课但有后代有课）。
 *        用于把计数文案显示成「含 60 门」而不是「60 门」，避免用户误以为
 *        这些课程直接挂在当前节点下
 */
@Immutable
data class AcademicNodeUi(
    val id: String,
    val parentId: String?,
    val name: String,
    val depth: Int,
    val requiredCreditsLabel: String,
    val earnedCreditsLabel: String,
    val gapCreditsLabel: String,
    val requiredCredits: Float,
    val earnedCredits: Float,
    val gapCredits: Float,
    val passed: Boolean,
    /** 仅本节点自身的课程（教务只在叶子节点上挂课，多数父节点这里是空的） */
    val courses: List<AcademicCourseUi>,
    val childIds: List<String>,
    val isEmptyLeaf: Boolean,
    /** 自身 + 全部后代的课程总数 */
    val totalCourseCount: Int,
    val hasNestedCourses: Boolean
)

/**
 * 【学业情况】页面的一次性完整状态。
 *
 * 用 `by lazy` 缓存派生结果：`level1Tabs` 与 `childrenOf` 都是纯函数推导，
 * 缓存后每次重组不再重算，同时保持数据类本身不可变（[Immutable] 语义成立）。
 */
@Immutable
class AcademicUiState(
    /** 全部节点，按树序（根顺序 → 深度优先），带 depth */
    val flatNodes: List<AcademicNodeUi>,
    /** 节点 id → depth 1 的节点，即一级标签；顺序即标签顺序 */
    val level1Nodes: List<AcademicNodeUi>,
    /** 节点 id → 节点，供 O(1) 查父/查子 */
    val nodeById: Map<String, AcademicNodeUi>
) {
    /** 是否还没有任何学业数据（用于空状态判断）。 */
    val isEmpty: Boolean get() = flatNodes.isEmpty()

    /**
     * 取某个一级标签下的二级标签列表（即它的直接子节点，按树序）。
     *
     * 若某一级标签没有任何子节点（某专业「大类教育」下没有分项），
     * 则退化为返回它自身 —— 此时页面上不显示二级标签行，直接展示它的课程。
     */
    fun level2TabsOf(level1Id: String): List<AcademicNodeUi> {
        val node = nodeById[level1Id] ?: return emptyList()
        val children = node.childIds.mapNotNull { nodeById[it] }
            .filter { child -> child.depth == LEVEL2_DEPTH }
        return children.ifEmpty { listOf(node) }
    }

    /**
     * 取某个二级标签下需要在内容区里渲染的顶层折叠面板。
     *
     * ## 划分规则：严格按树的 depth，不做任何"提升"
     *
     * - depth 0 → 一级标签
     * - depth 1 → 二级标签（本函数的入参）
     * - depth ≥ 2 → 折叠面板，展开父面板时递归渲染下一层
     *
     * 实测（某账号 33 节点）「严格按 depth 划分」与需求描述的结构完全吻合：
     * 思政军事与体育 / 英语 / 日语 / 四史 / 劳动 / 健康 全都在 depth ≥ 2，
     * 本来就该是折叠面板，因此**不需要**把"自身无课的空壳分支"提升一级。
     *
     * 二级标签自身也可能挂课（如「大类必修」自身 14 门且是叶子），
     * 此时它自己就是唯一的面板。
     */
    fun panelNodesOf(level2Id: String): List<AcademicNodeUi> {
        val level2 = nodeById[level2Id] ?: return emptyList()
        // 有子节点 → 列出子节点作为面板；否则它自己就是面板
        val children = level2.childIds.mapNotNull { nodeById[it] }
        return children.ifEmpty { listOf(level2) }
    }

    /**
     * 递归收集某节点下全部后代节点（按树序展开，不区分深度）。
     *
     * 供测试与调试使用；UI 侧不需要它 —— 折叠渲染是边展开边取子节点的。
     */
    fun descendantsOf(nodeId: String): List<AcademicNodeUi> {
        val out = ArrayList<AcademicNodeUi>()
        fun walk(id: String) {
            nodeById[id]?.childIds.orEmpty().forEach { childId ->
                nodeById[childId]?.let {
                    out += it
                    walk(childId)
                }
            }
        }
        walk(nodeId)
        return out
    }

    /**
     * 取某节点下、深度恰好为 [depth] 的直接子节点。
     */
    fun childrenAtDepth(parentId: String, depth: Int): List<AcademicNodeUi> =
        nodeById[parentId]?.childIds.orEmpty()
            .mapNotNull { nodeById[it] }
            .filter { it.depth == depth }

    companion object {
        /** 空状态单例，供 ViewModel 的 stateIn 初值使用。 */
        val Empty = AcademicUiState(emptyList(), emptyList(), emptyMap())
    }
}

/**
 * 非正式学时（第二类课）一行的展示模型。
 *
 * @param yearTermLabel 如 "2024-2025 第1学期"
 * @param hoursLabel 如 "16 学时"；教务返回 0 时显示 "0 学时"（实测确实存在 0 学时的记录）
 */
@Immutable
data class NonFormalCourseUi(
    val id: Long,
    val courseName: String,
    val yearTermLabel: String,
    val hoursLabel: String,
    val result: String
)

/**
 * 「非正式学时」标签页的完整状态。
 *
 * @param totalHours 学时合计，用于顶部汇总行
 */
@Immutable
data class NonFormalUiState(
    val courses: List<NonFormalCourseUi> = emptyList(),
    val totalHours: Int = 0
) {
    val isEmpty: Boolean get() = courses.isEmpty()

    companion object {
        val Empty = NonFormalUiState()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 建树
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 把 Room 里的扁平行还原成页面需要的层级结构。
 *
 * 这是一个**无状态纯对象**，唯一的职责是把「数据库事实」翻译成「展示模型」，
 * 因此可以在后台线程反复安全调用。
 */
object AcademicTreeBuilder {

    /**
     * 构建学业情况页面状态。
     *
     * @param nodes Room 中按 `sortOrder` 升序取出的计划点
     * @param courses Room 中全部计划内课程
     */
    fun build(
        nodes: List<AcademicPlanNodeEntity>,
        courses: List<AcademicCourseEntity>
    ): AcademicUiState {
        if (nodes.isEmpty()) return AcademicUiState.Empty

        // ── 1. 课程按节点分组 ──
        val coursesByNode = courses.groupBy { it.nodeId }

        // ── 2. 计算每个节点的直接子节点（保持 nodes 的树序）──
        val nodeByIdRaw = nodes.associateBy { it.id }
        val childrenOf = LinkedHashMap<String, MutableList<String>>()
        val knownIds = nodeByIdRaw.keys
        nodes.forEach { node ->
            // 父节点不存在时视为根，避免因教务数据异常丢失整棵子树
            val parent = node.parentId?.takeIf { it in knownIds }
            if (parent != null) {
                childrenOf.getOrPut(parent) { mutableListOf() } += node.id
            }
        }

        // ── 3. 自底向上汇总课程数（关键：教务只在叶子节点挂课程）──
        //
        // 实测：专业教育自身 0 门，但整棵子树共 60 门（专业必修 15 + 专业选修 45）。
        // 若父节点只显示自身课程数，用户会看到「专业教育 0 门」而误判成没数据。
        // 因此这里递归汇总「自身 + 全部后代」的课程数。
        //
        // 已实测：聚合后不会产生重复（没有任何课程挂在多个叶子下），
        // 四大类聚合数 28/24/60/23 之和 = 135 = 课程总条数。
        val totalCountOf = HashMap<String, Int>(nodes.size)
        val totalCoursesOf = HashMap<String, List<AcademicCourseUi>>(nodes.size)

        fun rollUp(nodeId: String): Pair<Int, List<AcademicCourseUi>> {
            totalCountOf[nodeId]?.let { cached ->
                return cached to totalCoursesOf.getValue(nodeId)
            }
            var count = coursesByNode[nodeId].orEmpty().size
            val merged = ArrayList<AcademicCourseUi>(count)
            merged += toUiSorted(coursesByNode[nodeId].orEmpty())
            childrenOf[nodeId].orEmpty().forEach { childId ->
                val (childCount, childCourses) = rollUp(childId)
                count += childCount
                merged += childCourses
            }
            totalCountOf[nodeId] = count
            totalCoursesOf[nodeId] = merged
            return count to merged
        }
        nodes.forEach { rollUp(it.id) }

        // ── 4. 自顶向下计算 depth 并构建展示模型 ──
        val nodeById = LinkedHashMap<String, AcademicNodeUi>(nodes.size)
        val flat = ArrayList<AcademicNodeUi>(nodes.size)

        fun buildNode(node: AcademicPlanNodeEntity, depth: Int) {
            val childIds = childrenOf[node.id].orEmpty().toList()
            val nodeCourses = toUiSorted(coursesByNode[node.id].orEmpty())
            val totalCount = totalCountOf[node.id] ?: nodeCourses.size

            val gap = (node.requiredCredits - node.earnedCredits).coerceAtLeast(0f)

            val ui = AcademicNodeUi(
                id = node.id,
                parentId = node.parentId,
                name = node.name,
                depth = depth,
                requiredCreditsLabel = formatCredits(node.requiredCredits),
                earnedCreditsLabel = formatCredits(node.earnedCredits),
                gapCreditsLabel = formatCredits(gap),
                requiredCredits = node.requiredCredits,
                earnedCredits = node.earnedCredits,
                gapCredits = gap,
                passed = node.passed,
                courses = nodeCourses,
                childIds = childIds,
                isEmptyLeaf = nodeCourses.isEmpty() && childIds.isEmpty(),
                totalCourseCount = totalCount,
                // 自身没课但后代有课 → 计数文案显示成「含 N 门」
                hasNestedCourses = nodeCourses.isEmpty() && totalCount > 0
            )

            nodeById[node.id] = ui
            flat += ui
            childIds.forEach { childId ->
                nodeByIdRaw[childId]?.let { buildNode(it, depth + 1) }
            }
        }

        // 根节点：parentId 为 null 或指向不存在的节点
        nodes.filter { it.parentId == null || it.parentId !in knownIds }
            .forEach { buildNode(it, LEVEL1_DEPTH) }

        // 兜底：因父子关系成环而未被访问到的节点，按原顺序追加，确保不丢数据
        nodes.filter { it.id !in nodeById }
            .forEach { buildNode(it, LEVEL1_DEPTH) }

        val level1 = flat.filter { it.depth == LEVEL1_DEPTH }

        return AcademicUiState(
            flatNodes = flat,
            level1Nodes = level1,
            nodeById = nodeById
        )
    }

    /**
     * 构建「非正式学时」标签页状态。
     *
     * [AcademicNonFormalCourseEntity.hours] 是字符串（教务可能返回空串），
     * 因此合计时逐个 `toIntOrNull` 并跳过解析失败的项。
     */
    fun buildNonFormal(
        entities: List<AcademicNonFormalCourseEntity>
    ): NonFormalUiState {
        if (entities.isEmpty()) return NonFormalUiState.Empty

        val items = entities.map { entity ->
            NonFormalCourseUi(
                id = entity.id,
                courseName = entity.courseName.ifBlank { "—" },
                yearTermLabel = formatYearTerm(entity.academicYear, entity.term),
                hoursLabel = formatHours(entity.hours),
                result = entity.result
            )
        }
        val total = entities.sumOf { it.hours.trim().toIntOrNull() ?: 0 }
        return NonFormalUiState(courses = items, totalHours = total)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 格式化工具（私有，全部是纯字符串运算，无 Date/Formatter 实例）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 学分格式化：整数不显示小数点（`8` 而非 `8.0`），小数保留一位（`43.5`）。
     * 与教务页面上的显示习惯一致。
     */
    fun formatCredits(value: Float): String {
        val rounded = Math.round(value * 10f) / 10f
        return if (rounded == rounded.toInt().toFloat()) {
            rounded.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", rounded)
        }
    }

    /**
     * 拼装「学年 学期」文案。
     *
     * 教务的学期字段实测是 `"1"` / `"2"`，转成「第1学期」更易读；
     * 学年字段形如 `"2024-2025"` 已是可读形式。未修课程两者皆空 → 返回空串。
     */
    fun formatYearTerm(academicYear: String, term: String): String {
        val year = academicYear.trim()
        val t = term.trim()
        if (year.isEmpty() && t.isEmpty()) return ""
        val termLabel = when (t) {
            "1" -> "第1学期"
            "2" -> "第2学期"
            "" -> ""
            else -> "第${t}学期"
        }
        return listOf(year, termLabel).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** 学时格式化，空值显示为 "—"。 */
    private fun formatHours(hours: String): String {
        val h = hours.trim()
        return if (h.isEmpty()) "—" else "$h 学时"
    }

    /**
     * 课程实体 → 展示模型，并排序。
     *
     * 排序规则：已修课程（有学年学期）按学年学期倒序在前，其次按课程名，
     * 保证同一份数据每次渲染顺序稳定（`observeCourses` 只按课程名排序，
     * 同一模块内还需要按学期归拢）。
     */
    private fun toUiSorted(entities: List<AcademicCourseEntity>): List<AcademicCourseUi> =
        entities.map { it.toUi() }
            .sortedWith(
                compareByDescending<AcademicCourseUi> { it.yearTermLabel }
                    .thenBy { it.courseName }
            )

    private fun AcademicCourseEntity.toUi(): AcademicCourseUi = AcademicCourseUi(
        id = id,
        courseCode = courseCode,
        courseName = courseName.ifBlank { "—" },
        creditsLabel = credits.trim().takeIf { it.isNotEmpty() }?.let { "$it 学分" } ?: "—",
        score = score.trim(),
        gpa = gpa.trim(),
        readStatusCode = readStatusCode.trim(),
        readStatus = readStatus.trim().ifEmpty { "未知" },
        yearTermLabel = formatYearTerm(academicYear, term),
        courseNature = courseNature.trim()
    )
}
