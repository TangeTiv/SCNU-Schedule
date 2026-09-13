package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.ui.theme.LocalIsDarkTheme

// ═══════════════════════════════════════════════════════════════════════════
// 【学业情况】页面（校园页卡片标题仍为「学业情况」，页面标题为「学分与非正学时」）
//
// 层级展示方式：
//   · 第一级 = 胶囊标签行（两字简称）—— 通识 / 大类 / 专业 / 实践 / 非正
//   · 第二级 = iOS 分段控件 —— 各教育类下的必修 / 选修等
//   · 第三级及更深 = 折叠展开，同级面板共用一个容器、内部用分隔线区分
//
// 性能约束落地：
//   · 状态收集一律 collectAsStateWithLifecycle（红线 1）
//   · 整页内容用**一个扁平 LazyColumn**，折叠区域内部课程用 Column + forEach，
//     绝不嵌套同方向 Lazy（红线 4）
//   · 展开状态用 SnapshotStateMap，按节点 id 局部重组（红线 5）
//   · 树构建在 ViewModel 的 Dispatchers.Default 完成，本文件只做纯展示（红线 2）
// ═══════════════════════════════════════════════════════════════════════════

// ── 配色：浅色沿用项目暖白风格，深色切换主题语义色（与 GradeScreen 同一套做法）──

@Composable
private fun academicPageBackground(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.surface else Color(0xFFFCF9F8)

@Composable
private fun academicCardBackground(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White

@Composable
private fun academicPrimaryText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.onSurface else Color(0xFF333333)

@Composable
private fun academicSecondaryText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF757575)

@Composable
private fun academicHintText(isDark: Boolean) =
    if (isDark) MaterialTheme.colorScheme.outline else Color(0xFF9E9E9E)

/** 学分缺口的红色（浅/深色各一套，深色下提亮保证对比度） */
private val GapRedLight = Color(0xFFE11D48)
private val GapRedDark = Color(0xFFFB7185)

/** 已获学分的绿色 */
private val EarnedGreenLight = Color(0xFF10B981)
private val EarnedGreenDark = Color(0xFF34D399)

/** 要求学分的蓝色 */
private val RequirementBlueLight = Color(0xFF2563EB)
private val RequirementBlueDark = Color(0xFF60A5FA)

/** 通用强调蓝（分段控件选中文字等） */
private val AccentBlue = Color(0xFF2563EB)

@Composable
private fun gapRed(isDark: Boolean) = if (isDark) GapRedDark else GapRedLight

@Composable
private fun gapRedContainer(isDark: Boolean) =
    (if (isDark) GapRedDark else GapRedLight).copy(alpha = if (isDark) 0.22f else 0.10f)

@Composable
private fun earnedGreen(isDark: Boolean) = if (isDark) EarnedGreenDark else EarnedGreenLight

@Composable
private fun earnedGreenContainer(isDark: Boolean) =
    (if (isDark) EarnedGreenDark else EarnedGreenLight).copy(alpha = if (isDark) 0.22f else 0.12f)

@Composable
private fun requirementBlue(isDark: Boolean) =
    if (isDark) RequirementBlueDark else RequirementBlueLight

/**
 * 学业情况主页面。
 *
 * 无底部导航栏（二级页面），与成绩查询 / 考试安排保持一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicScreen(
    onBack: () -> Unit,
    viewModel: AcademicViewModel = hiltViewModel()
) {
    val academicState by viewModel.academicState.collectAsStateWithLifecycle()
    val nonFormalState by viewModel.nonFormalState.collectAsStateWithLifecycle()
    val selectedLevel1 by viewModel.selectedLevel1.collectAsStateWithLifecycle()
    val selectedLevel2Map by viewModel.selectedLevel2ByLevel1.collectAsStateWithLifecycle()

    val isDark = LocalIsDarkTheme.current

    // 折叠展开状态：key = 节点 id。用 SnapshotStateMap 保证按 key 局部重组（红线 5）
    val expandedNodes = remember { mutableStateMapOf<String, Boolean>() }

    val isEmpty = academicState.isEmpty && nonFormalState.isEmpty

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.academic_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = academicPageBackground(isDark)
                )
            )
        },
        containerColor = academicPageBackground(isDark)
    ) { innerPadding ->
        if (isEmpty) {
            EmptyAcademicState(
                isDark = isDark,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            AcademicBody(
                academicState = academicState,
                nonFormalState = nonFormalState,
                selectedLevel1 = selectedLevel1,
                selectedLevel2Map = selectedLevel2Map,
                expandedNodes = expandedNodes,
                isDark = isDark,
                onSelectLevel1 = viewModel::selectLevel1,
                onSelectLevel2 = viewModel::selectLevel2,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}

/**
 * 页面主体：一级胶囊标签行 + 当前标签的内容区。
 *
 * 拆成独立函数是为了让「切标签」时的重组范围限制在主体内，
 * 顶部 TopAppBar 与 Scaffold 不参与重组（性能红线 6）。
 */
@Composable
private fun AcademicBody(
    academicState: AcademicUiState,
    nonFormalState: NonFormalUiState,
    selectedLevel1: Int,
    selectedLevel2Map: Map<String, Int>,
    expandedNodes: MutableMap<String, Boolean>,
    isDark: Boolean,
    onSelectLevel1: (Int) -> Unit,
    onSelectLevel2: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 「非正式学时」是最后一个一级标签
    val nonFormalTabIndex = academicState.level1Nodes.size

    Column(modifier = modifier) {
        AcademicLevel1ChipRow(
            tabs = academicState.level1Nodes,
            nonFormalTitle = stringResource(R.string.academic_tab_nonformal),
            selectedIndex = selectedLevel1,
            onSelect = onSelectLevel1
        )

        if (selectedLevel1 == nonFormalTabIndex) {
            // ── 非正式学时：单一扁平列表 ──
            NonFormalContent(state = nonFormalState, isDark = isDark)
            return@Column
        }

        val level1Node = academicState.level1Nodes.getOrNull(selectedLevel1)
        if (level1Node == null) {
            EmptyAcademicState(isDark = isDark)
            return@Column
        }

        // 该一级标签下的二级标签。用 remember 缓存，避免每帧重算（红线 2/3）
        val level2Tabs = remember(level1Node.id, academicState) {
            academicState.level2TabsOf(level1Node.id)
        }
        // 只有一个子项时隐藏分段控件，直接展示内容
        val showLevel2 = level2Tabs.size > 1

        val selectedLevel2 = (selectedLevel2Map[level1Node.id] ?: 0)
            .coerceIn(0, (level2Tabs.size - 1).coerceAtLeast(0))

        if (showLevel2) {
            AcademicLevel2SegmentedControl(
                tabs = level2Tabs,
                selectedIndex = selectedLevel2,
                onSelect = { index -> onSelectLevel2(level1Node.id, index) }
            )
        }

        val activeLevel2 = level2Tabs.getOrNull(selectedLevel2)
        if (activeLevel2 == null) {
            EmptyAcademicState(isDark = isDark)
            return@Column
        }

        val panelNodes = remember(activeLevel2.id, academicState) {
            academicState.panelNodesOf(activeLevel2.id)
        }
        Level2Content(
            level2Node = activeLevel2,
            panelNodes = panelNodes,
            state = academicState,
            expandedNodes = expandedNodes,
            isDark = isDark
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 一级标签：胶囊行
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一级标签的**两字简称**。
 *
 * 完整名称（通识教育 / 大类教育 / 专业教育 / 实践教育 / 非正式学时）挤在一行里
 * 既放不下、彼此也难以区分，因此一级标签统一用两字简称。
 * 完整名称仍会在二级分段控件与学分汇总条上出现，信息不丢失。
 *
 * 用显式映射而不是「取前两字」的通用规则，是为了表达这是**有意的产品决策**：
 * 将来教务若新增教育类，这里必须显式补一条，而不是静默地取前两字给出
 * 一个可能不通顺的简称。
 */
private val LEVEL1_SHORT_NAMES = mapOf(
    "通识教育" to "通识",
    "大类教育" to "大类",
    "专业教育" to "专业",
    "实践教育" to "实践",
    "非正式学时" to "非正"
)

/** 取一级标签简称；未在映射表中的名称回退为「前两字」。 */
private fun level1ShortName(fullName: String): String =
    LEVEL1_SHORT_NAMES[fullName] ?: fullName.take(2)

/**
 * 第一级标签行：四个教育类 + 非正式学时，渲染为**胶囊标签**。
 *
 * ## 为什么不用 Material3 的 `PrimaryTabRow`
 *
 * 原来的 TabRow 只靠一根细下划线区分选中项，五个标签并排时「哪个被选中」
 * 很不明显。改成胶囊后，每个标签自带边界（选中=实心主色块、未选中=浅灰块），
 * 区分度大幅提升，同时也与第二级的 iOS 分段控件在形态上明确分层。
 *
 * 一级标签数量固定（最多 5 个），但仍用 `LazyRow` 承载：小屏 / 大字体下
 * 胶囊会变宽，需要横向滚动而不是挤压换行。
 */
@Composable
private fun AcademicLevel1ChipRow(
    tabs: List<AcademicNodeUi>,
    nonFormalTitle: String,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val totalCount = tabs.size + 1
    if (totalCount <= 0) return

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            count = totalCount,
            key = { index -> tabs.getOrNull(index)?.id ?: "nonformal" }
        ) { index ->
            val label = tabs.getOrNull(index)
                ?.let { level1ShortName(it.name) }
                ?: level1ShortName(nonFormalTitle)
            Level1Chip(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) }
            )
        }
    }
}

/** 单个一级胶囊标签。 */
@Composable
private fun Level1Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current

    val containerColor = when {
        selected -> MaterialTheme.colorScheme.primary
        isDark -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color(0xFFE8E4E1)
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        isDark -> MaterialTheme.colorScheme.onSurface
        else -> Color(0xFF5A5A5A)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            // 未选中胶囊在浅色下与页面底色接近，补一圈描边划出边界；
            // 选中态是实心主色块，本身就是边界，不需要描边。
            .then(
                if (!selected) {
                    Modifier.border(
                        width = 1.dp,
                        color = if (isDark) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            Color(0xFFD9D2CC)
                        },
                        shape = RoundedCornerShape(50)
                    )
                } else {
                    Modifier
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 二级标签：iOS 分段控件
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 第二级标签，渲染为 **iOS 风格分段控件（Segmented Control）**。
 *
 * 一个浅灰圆角容器把所有同级子标签**包在同一个整体里**，选中项是容器内部
 * 浮起的小白块（主色文字），未选中项透明。相比各自独立的 Tab，
 * 这种形态天然表达了「这几项是一组互斥的同级选项」，也就是需求里要强化的同级关系；
 * 同时它与一级的胶囊标签形态不同，两级层级一眼可分。
 *
 * ## 滑动动画
 *
 * 白色选中块是**一个共享图形**（量出整条轨道的宽度后按等分计算偏移），
 * 而不是每个标签各画各的白底 —— 只有同一个图形才能「滑过去」。
 * 切换时用 `animateDpAsState` 把偏移量做成弹簧动画。
 *
 * ## 为什么没有涟漪（ripple）
 *
 * 标签用的是 `pointerInput + detectTapGestures` 而非 `clickable`：
 * `clickable` 默认带 Material 涟漪，点击未选中项时会画一片扩散的灰色阴影，
 * **正好盖住滑块的滑动动画**，而且涟漪的「扩散」与滑块的「位移」是两套
 * 互相冲突的反馈语言。这里改为按住时文字轻微加深 + 抬手触发触感，
 * 视觉重心完全留给滑块。
 *
 * ## 高度必须显式指定
 *
 * 外层 Box 若不给确定高度，滑块用的 `fillMaxHeight()` 会量出 0 高，
 * 进而把同级的课程列表（LazyColumn）挤成 0 高、整页不可见。
 * 详见下方 `⚠️` 注释。
 *
 * 只有 1 个子项时调用方会跳过本控件。
 */
@Composable
private fun AcademicLevel2SegmentedControl(
    tabs: List<AcademicNodeUi>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    if (tabs.size <= 1) return

    val isDark = LocalIsDarkTheme.current
    val haptics = LocalHapticFeedback.current

    // 轨道色：iOS 是浅灰底；深色模式下用比 surface 稍亮的容器色，保证能看出「凹槽」
    val trackColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color(0xFFEDEBE9)
    }
    val thumbColor = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.White
    }
    val selectedTextColor = if (isDark) {
        MaterialTheme.colorScheme.primary
    } else {
        AccentBlue
    }
    val unselectedTextColor = academicSecondaryText(isDark)

    // 轨道宽度用 px 记录，供等分计算；0 表示尚未测量，首帧先不画白块
    var trackWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 白块滑动的目标偏移 = 段宽 × 选中下标。
    // 宽度未测得时为 0，测到后 animateDpAsState 会自动从 0 滑到正确位置。
    val targetOffset = with(density) {
        ((trackWidthPx.toFloat() / tabs.size) * selectedIndex).toDp()
    }

    val animatedOffset by animateDpAsState(
        targetValue = targetOffset,
        // 弹簧动画：轻微回弹，比 tween 更接近 iOS 系统控件的手感
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "segmentThumbOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            // 内边距 3dp，让选中的白块像「嵌在凹槽里」
            .padding(3.dp)
            // ⚠️ 必须显式给高度。白色滑块要用 fillMaxSize 撑满凹槽，
            // 而 Box 对子项施加的是 matchParentSize 约束；若这里不给确定高度，
            // 滑块的高度就是不确定的，整个 Box 会量出 0 高 ——
            // 结果不只是控件看不见，下面同级兄弟（LazyColumn）也会被挤成 0 高、
            // 整页课程全部不可见。36dp = 文字行高 + 上下各 7dp 内边距 + 少量余量。
            .height(36.dp)
            .onSizeChanged { trackWidthPx = it.width }
    ) {
        // ── 共享的白色选中块（在标签文字下层）──
        if (trackWidthPx > 0) {
            val segmentWidthDp = with(density) {
                (trackWidthPx.toFloat() / tabs.size).toDp()
            }
            Box(
                modifier = Modifier
                    .width(segmentWidthDp)
                    .fillMaxHeight()
                    .offset(x = animatedOffset)
                    .clip(RoundedCornerShape(8.dp))
                    .background(thumbColor)
            )
        }

        // ── 标签文字层 ──
        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEachIndexed { index, node ->
                val selected = index == selectedIndex

                // 自定义按压反馈：记录是否正被按住。
                // 刻意**不用** Material 的涟漪（ripple）——它在分段控件里会画一片
                // 扩散的灰色阴影，既盖住滑块的滑动动画，也和 iOS 分段的反馈语言冲突。
                // 这里改为「按住时文字颜色加深一点」，不干扰滑块。
                var pressed by remember { mutableStateOf(false) }
                val pressAlpha by animateFloatAsState(
                    targetValue = if (pressed) 0.55f else 1f,
                    animationSpec = tween(durationMillis = 90),
                    label = "segmentPressAlpha"
                )

                val baseColor = if (selected) selectedTextColor else unselectedTextColor

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(8.dp))
                        // 用 pointerInput 取代 clickable：clickable 必然带 indication，
                        // 且会额外引入一层交互语义节点；这里只需要「点一下」。
                        .pointerInput(index, selectedIndex) {
                            detectTapGestures(
                                onPress = {
                                    pressed = true
                                    // 等待抬手或取消，保证按住时一直保持加深
                                    tryAwaitRelease()
                                    pressed = false
                                },
                                onTap = {
                                    if (index != selectedIndex) {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.LongPress
                                        )
                                        onSelect(index)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = node.name,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = baseColor.copy(alpha = baseColor.alpha * pressAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 二级标签内容区
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 某个二级标签的内容区：**一个扁平 LazyColumn**（红线 4）。
 *
 * 两项内容：
 * 1. 该模块的学分汇总条（要求 / 已获 / 缺口）
 * 2. **一个共同的分组容器**，内部装该模块下所有同级可折叠卡片，卡片之间用分隔线
 *
 * 第 2 点是需求里「强化同级关系」的落地方式：比起几张各自独立的卡片靠间距排列，
 * 把它们包进同一个圆角容器、内部只留细分隔线，能直观表达「它们属于同一组」。
 * 深层嵌套时容器会再套容器，层级关系自然浮现。
 */
@Composable
private fun Level2Content(
    level2Node: AcademicNodeUi,
    panelNodes: List<AcademicNodeUi>,
    state: AcademicUiState,
    expandedNodes: MutableMap<String, Boolean>,
    isDark: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "summary-${level2Node.id}") {
            ModuleSummaryBar(node = level2Node, isDark = isDark)
        }

        item(key = "group-${level2Node.id}") {
            // 同级分组容器：所有面板共用一张卡片底，靠分隔线区分彼此
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = academicCardBackground(isDark)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    panelNodes.forEachIndexed { index, node ->
                        if (index > 0) {
                            DividerLine(isDark = isDark)
                        }
                        NodePanel(
                            node = node,
                            state = state,
                            expandedNodes = expandedNodes,
                            isDark = isDark,
                            depth = 0
                        )
                    }
                }
            }
        }
    }
}

/**
 * 卡片详情行文案：`N 门 · 要求 X | 已获 Y`。
 *
 * ## 为什么要有课程门数
 *
 * 教务**只在叶子计划点上挂课程**，父节点自身查询返回空数组。
 * 例如「专业教育」自身 0 门，但整棵子树共 60 门（专业必修 15 + 专业选修 45）。
 * 如果卡片上不显示汇总门数，父节点展开后看着像「没有数据」。
 * 因此这里展示**递归汇总后的门数**；[AcademicNodeUi.hasNestedCourses] 为真时
 * （自身无课、课程都在子节点里）文案写成「含 N 门」，避免误以为
 * 这些课程直接挂在本节点下。
 *
 * ## 配色
 *
 * 门数是中性灰；`要求` 的数字为蓝、`已获` 的数字为绿，与整体配色一致。
 *
 * 注意 `stringResource` 必须在 `@Composable` 上下文先取出来，
 * 不能写进 `buildAnnotatedString` 的 lambda（那是普通 lambda，不是 @Composable）。
 */
@Composable
private fun nodeDetailText(
    node: AcademicNodeUi,
    isDark: Boolean,
    numberWeight: FontWeight
): AnnotatedString {
    val requiredLabel = stringResource(R.string.academic_required)
    val earnedLabel = stringResource(R.string.academic_earned)
    val countText = if (node.hasNestedCourses) {
        stringResource(R.string.academic_course_count_nested, node.totalCourseCount)
    } else {
        stringResource(R.string.academic_course_count, node.totalCourseCount)
    }

    val labelColor = academicHintText(isDark)

    return buildAnnotatedString {
        withStyle(SpanStyle(color = labelColor)) {
            append(countText)
            append(" · ")
            append(requiredLabel)
            append(' ')
        }
        withStyle(SpanStyle(color = requirementBlue(isDark), fontWeight = numberWeight)) {
            append(node.requiredCreditsLabel)
        }
        withStyle(SpanStyle(color = labelColor)) {
            append(" | ")
            append(earnedLabel)
            append(' ')
        }
        withStyle(SpanStyle(color = earnedGreen(isDark), fontWeight = numberWeight)) {
            append(node.earnedCreditsLabel)
        }
    }
}

/**
 * 模块学分汇总条：`要求 39 | 已获 33` + 右侧缺口徽章。
 *
 * 缺额为 0 时用绿色「已修满」，否则用红色强调缺多少学分。
 */
@Composable
private fun ModuleSummaryBar(node: AcademicNodeUi, isDark: Boolean) {
    val done = node.gapCredits <= 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = nodeDetailText(
                node = node,
                isDark = isDark,
                numberWeight = FontWeight.Bold
            ),
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (done) earnedGreenContainer(isDark) else gapRedContainer(isDark))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (done) {
                    stringResource(R.string.academic_status_completed)
                } else {
                    stringResource(R.string.academic_gap_credits, node.gapCreditsLabel)
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (done) earnedGreen(isDark) else gapRed(isDark)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 折叠面板（三级及更深）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 一个可折叠的计划点面板。
 *
 * ## 卡片外观
 *
 * 本组件**不自带 Card 背景** —— 同级面板由 [Level2Content] 或上层 [NodePanel]
 * 统一包在一个共用的容器里，靠分隔线区分彼此。这样「它们属于同一组」由容器表达，
 * 而不是靠各自的卡片边框，层级关系更清楚。
 *
 * ## 展开箭头
 *
 * **所有面板都显示箭头**，包括既无课程也无子节点的空叶节点：
 * 一方面是箭头位置固定后视觉更整齐，另一方面箭头本身就是「可点」的提示，
 * 避免用户以为卡片坏了。空叶节点展开后显示「暂无课程明细」，
 * 而不是把提示常显在卡片下方。
 *
 * @param depth 额外的左缩进层级（嵌套面板会再缩进，强化从属关系）
 */
@Composable
private fun NodePanel(
    node: AcademicNodeUi,
    state: AcademicUiState,
    expandedNodes: MutableMap<String, Boolean>,
    isDark: Boolean,
    depth: Int
) {
    val expanded = expandedNodes[node.id] == true

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "academicPanelArrow"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedNodes[node.id] = !expanded }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = academicPrimaryText(isDark),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                // 门数是中性灰；要求=蓝、已获=绿
                Text(
                    text = nodeDetailText(
                        node = node,
                        isDark = isDark,
                        numberWeight = FontWeight.SemiBold
                    ),
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    stringResource(R.string.academic_collapse)
                } else {
                    stringResource(R.string.academic_expand)
                },
                tint = academicHintText(isDark),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation)
            )
        }

        // ── 展开内容 ──
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            ) {
                when {
                    // 1. 有课程 → 课程明细表（每模块课程数在个位到十几条，用 Column 避免嵌套 Lazy）
                    node.courses.isNotEmpty() -> {
                        InsetGroupContainer(isDark = isDark) {
                            node.courses.forEachIndexed { index, course ->
                                if (index > 0) {
                                    DividerLine(isDark = isDark)
                                }
                                CourseRow(course = course, isDark = isDark)
                            }
                        }
                    }

                    // 2. 无课程但有子节点 → 递归渲染下一级面板。
                    //    子级同样包在一个共用容器里，形成「容器套容器」的层级感。
                    //    空叶节点（如「其他课程」）也照常渲染，与顶层规则一致，不静默吞掉。
                    node.childIds.isNotEmpty() -> {
                        InsetGroupContainer(isDark = isDark) {
                            val children = node.childIds.mapNotNull { state.nodeById[it] }
                            children.forEachIndexed { index, child ->
                                if (index > 0) {
                                    DividerLine(isDark = isDark)
                                }
                                NodePanel(
                                    node = child,
                                    state = state,
                                    expandedNodes = expandedNodes,
                                    isDark = isDark,
                                    depth = 0
                                )
                            }
                        }
                    }

                    // 3. 真正的空叶节点 → 教务没有下发课程明细
                    else -> {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.academic_no_courses),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = academicSecondaryText(isDark)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.academic_no_courses_hint),
                                fontSize = 11.sp,
                                color = academicHintText(isDark)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 嵌套内容的容器。
 *
 * ## 为什么需要边框
 *
 * 嵌套容器的底色与父级、与卡片底色都比较接近（浅色下分别是
 * `#FAF9F8` / 白 / `#FCF9F8`），仅靠填充色区分不够。加一圈描边后，
 * 「英语 / 日语」这类子卡片的边界就清楚了，不会再和父级「外语类」糊在一起。
 *
 * 边框同时向上兼容：最外层的 [Level2Content] 分组容器用 1dp 更深的边，
 * 嵌套层用略浅的边，形成「越深越轻」的层次。
 */
@Composable
private fun InsetGroupContainer(
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isDark) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color(0xFFFAF9F8)
                }
            )
            .border(
                width = 1.dp,
                color = if (isDark) {
                    MaterialTheme.colorScheme.outlineVariant
                } else {
                    Color(0xFFE4DED9)
                },
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        content()
    }
}

/** 一条用于分隔同级项 / 课程行的细线（项目未使用 material3 Divider，这里保持同样观感）。 */
@Composable
private fun DividerLine(isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                if (isDark) {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                } else {
                    Color(0xFFF0F0F0)
                }
            )
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 课程行
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 单门课程行。
 *
 * 左侧：课程名 + 「学分 · 学年学期」
 * 右侧：成绩（醒目）+ 修读状态徽章
 *
 * 未修课程没有成绩，显示占位符 `—`。
 */
@Composable
private fun CourseRow(course: AcademicCourseUi, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.courseName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = academicPrimaryText(isDark),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOf(course.creditsLabel, course.yearTermLabel)
                    .filter { it.isNotEmpty() && it != "—" }
                    .joinToString(" · ")
                    .ifEmpty { course.creditsLabel },
                fontSize = 11.sp,
                color = academicHintText(isDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 成绩
        Text(
            text = course.score.ifBlank { "—" },
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (course.score.isBlank()) {
                academicHintText(isDark)
            } else {
                academicPrimaryText(isDark)
            }
        )

        Spacer(modifier = Modifier.width(10.dp))

        StatusBadge(course = course, isDark = isDark)
    }
}

/**
 * 修读状态徽章。
 *
 * 配色语义：已修=绿、在修=蓝、未修=灰。
 * 状态码取值实测为 1/2/3/4（见 `XDZT_MAP`）。
 */
@Composable
private fun StatusBadge(course: AcademicCourseUi, isDark: Boolean) {
    val (bg: Color, fg: Color) = when (course.readStatusCode) {
        "4" -> earnedGreenContainer(isDark) to earnedGreen(isDark)

        "1" -> requirementBlue(isDark).copy(alpha = if (isDark) 0.22f else 0.10f) to
                requirementBlue(isDark)

        else -> {
            val gray = if (isDark) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                Color(0xFF9E9E9E)
            }
            gray.copy(alpha = 0.18f) to gray
        }
    }

    val label = when (course.readStatusCode) {
        "4" -> stringResource(R.string.academic_status_completed)
        "1" -> stringResource(R.string.academic_status_in_progress)
        "2", "3" -> stringResource(R.string.academic_status_not_started)
        else -> course.readStatus.ifBlank { stringResource(R.string.academic_status_unknown) }
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 非正式学时
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「非正式学时」标签页内容。
 *
 * 单一扁平列表（不做折叠）：顶部一行汇总「共 N 门 · 合计 M 学时」，
 * 其后每条记录显示「课程名 / 学年学期 / 学时」。
 */
@Composable
private fun NonFormalContent(
    state: NonFormalUiState,
    isDark: Boolean
) {
    if (state.isEmpty) {
        EmptyAcademicState(isDark = isDark)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "nonformal-summary") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.academic_nonformal_summary,
                        state.courses.size,
                        state.totalHours
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = academicPrimaryText(isDark)
                )
            }
        }

        item(key = "nonformal-list") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = academicCardBackground(isDark)),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.courses.forEachIndexed { index, course ->
                        if (index > 0) {
                            DividerLine(isDark = isDark)
                        }
                        NonFormalCourseRow(course = course, isDark = isDark)
                    }
                }
            }
        }
    }
}

/**
 * 非正式学时单行。
 *
 * 按需求只展示「课程名 / 学年学期 / 学时」三项；
 * 返回的 `result`（通过/不通过）保留在数据模型里，但不在本行显示。
 */
@Composable
private fun NonFormalCourseRow(course: NonFormalCourseUi, isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.courseName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = academicPrimaryText(isDark),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (course.yearTermLabel.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = course.yearTermLabel,
                    fontSize = 11.sp,
                    color = academicHintText(isDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = course.hoursLabel,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = academicSecondaryText(isDark)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 空状态
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 友好空状态：数据库里没有任何学业数据时展示，
 * 提示用户先到【教务同步】执行一次同步（沿用成绩页的同款做法）。
 */
@Composable
private fun EmptyAcademicState(
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.School,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = if (isDark) MaterialTheme.colorScheme.outline else Color(0xFFBDBDBD)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.academic_no_data),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = academicSecondaryText(isDark)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.academic_no_data_hint),
            fontSize = 14.sp,
            color = academicHintText(isDark)
        )
    }
}
