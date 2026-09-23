package com.xingheyuzhuan.shiguangschedule.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.ai.AiChatMessage
import com.xingheyuzhuan.shiguangschedule.data.ai.AiRole
import kotlinx.coroutines.flow.StateFlow

private val PAGE_PADDING = 16.dp
private val BUBBLE_CORNER = 16.dp

/**
 * 【校园 → AI 助手】对话页。
 *
 * ## 挂载方式（已定）
 *
 * AI 是【校园】页的**一个独立模块页**（像「教务同步」那样挂卡片进入），
 * 不是每个模块内嵌一个 AI 入口（方案文档 §13.2 决策 41）。
 *
 * ## 过程反馈是硬要求
 *
 * 工具调用期间必须显示「正在查询你的课表…」（§9.2 坑 3）。
 * 3 轮工具 = 4 次 API 请求，等待明显更长，不给反馈用户会以为卡死。
 *
 * ## 重组范围的控制（性能红线 5）
 *
 * 流式文本每个 token 变一次。因此**不在本函数顶层**收集 `streamingText`
 * 与 `toolTrace`，而是把 StateFlow 传进"正在生成"那一条 item 里就地收集 ——
 * Compose 的重组会收敛到那个 item，不会波及历史消息、引导卡片与输入框。
 *
 * ## ⚠️ 键盘与布局（真机实测踩过，改动前先读）
 *
 * 输入栏**放在内容区的 Column 里**，而不是 `Scaffold` 的 `bottomBar` 槽：
 * 实测（小米 / Android 16 / 边到边）用 bottomBar 时，输入栏在窗口最底部会让系统
 * 把**整个窗口向上平移约 900px**（且只算一次、之后不复位），表现为顶栏被推出屏幕、
 * 输入栏与键盘之间空出一大块。
 *
 * 与之配套：`MainActivity.ScreenContent` 在进入本页时把窗口设为
 * `SOFT_INPUT_ADJUST_NOTHING`（禁止系统平移），由本页自己用
 * `windowInsetsPadding(safeDrawing.only(Bottom))` 处理键盘内边距。
 * **因此这里不能再加 `Modifier.imePadding()`** —— 那是二次内边距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    viewModel: AiAssistantViewModel = hiltViewModel()
) {
    // 低频状态在顶层收集（每轮问答才变一次）
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val degraded by viewModel.degraded.collectAsStateWithLifecycle()
    val showGreeting by viewModel.showGreeting.collectAsStateWithLifecycle()
    val privacyAccepted by viewModel.privacyAccepted.collectAsStateWithLifecycle()
    val configured by viewModel.configured.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // 有新内容时滚到底部。依赖 messages.size 与 running —— **刻意不依赖流式文本**，
    // 否则每个 token 都会触发一次滚动计算。
    LaunchedEffect(messages.size, running) {
        val target = messages.size + if (running) 1 else 0
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    Scaffold(
        // 边到边 + IME 的组合在这个项目里只有本页有（其它页没有底部输入栏）。
        // 这里**刻意不用 Scaffold 的 bottomBar 槽**，改为把输入栏放进内容区自己排版：
        // bottomBar 与 IME 内边距的交互在真机上不可预测（实测出现「输入栏多抬一个
        // 键盘高度」与「顶部栏被挤掉」两个问题），而下面的写法在结构上是确定的。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.ai_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // 与项目其它页面一致（AccountScreen / ExamScreen 都用 a11y_back）
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.newChat() }) {
                        Icon(
                            imageVector = Icons.Filled.AddComment,
                            contentDescription = stringResource(R.string.ai_action_new_chat)
                        )
                    }
                    IconButton(onClick = { onNavigate(Destination.AiSettings) }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.ai_action_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 顶部：让开 topBar（含状态栏，由 TopAppBar 自己消费）
                .padding(innerPadding)
                // 底部：键盘与导航栏取**并集**，避免两者相加导致多抬一条导航栏
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = PAGE_PADDING, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!configured) {
                    item(key = "not_configured") {
                        NotConfiguredBanner(onNavigate = onNavigate)
                    }
                }

                degraded?.let { reason ->
                    item(key = "degraded") {
                        InfoBanner(
                            text = reason.userMessage(),
                            icon = Icons.Filled.Info,
                            container = MaterialTheme.colorScheme.tertiaryContainer,
                            onContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onDismiss = { viewModel.consumeDegraded() }
                        )
                    }
                }

                error?.let { failure ->
                    item(key = "error") {
                        InfoBanner(
                            text = failure.userMessage(),
                            icon = Icons.Filled.ErrorOutline,
                            container = MaterialTheme.colorScheme.errorContainer,
                            onContentColor = MaterialTheme.colorScheme.onErrorContainer,
                            onDismiss = { viewModel.consumeError() }
                        )
                    }
                }

                if (showGreeting) {
                    item(key = "greeting") {
                        GreetingSection(
                            onSuggestion = { viewModel.send(it) },
                            enabled = privacyAccepted && configured && !running
                        )
                    }
                }

                items(items = messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                if (running) {
                    item(key = "streaming") {
                        // 高频状态在这里就地收集 → 重组只发生在本 item 内
                        StreamingBubble(
                            streamingText = viewModel.streamingText,
                            toolTrace = viewModel.toolTrace
                        )
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            AiInputBar(
                value = input,
                onValueChange = { input = it },
                running = running,
                enabled = privacyAccepted && configured,
                onSend = {
                    val text = input
                    input = ""
                    viewModel.send(text)
                },
                onStop = { viewModel.stop() }
            )
        }
    }

    // ── 首次隐私弹窗（已定：同意后方可使用） ──
    if (!privacyAccepted) {
        PrivacyDialog(
            onAccept = { viewModel.acceptPrivacy() },
            onDecline = onBack
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 引导区
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 空态引导。
 *
 * 入口在【校园】页会让用户**默认以为"这是教务相关的 AI"**，
 * 因此这里既给可直接点的问题示例，也把"能回答什么、依据什么数据"讲清楚。
 */
@Composable
private fun GreetingSection(
    onSuggestion: (String) -> Unit,
    enabled: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ai_greeting_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_greeting_scope),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        val suggestions = listOf(
            R.string.ai_suggestion_courses,
            R.string.ai_suggestion_exams,
            R.string.ai_suggestion_credits,
            R.string.ai_suggestion_grades
        )
        suggestions.forEach { res ->
            val text = stringResource(res)
            AssistChip(
                onClick = { onSuggestion(text) },
                enabled = enabled,
                label = { Text(text, fontSize = 13.sp) },
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 消息气泡
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageBubble(message: AiChatMessage) {
    val isUser = message.role == AiRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Text(
                text = stringResource(
                    if (isUser) R.string.ai_role_you else R.string.ai_role_assistant
                ),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(BUBBLE_CORNER),
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    fontSize = 15.sp,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // 回看时仍能看到"这条回答查过什么" —— 流式结束后过程反馈会消失，
            // 只留一个答案会让用户觉得它是凭空冒出来的。
            if (message.toolNoteRes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                // 注意：必须用 `map`（inline）而不是 `joinToString(transform=...)`
                // —— 后者的 transform 不是 inline lambda，里面不能调 @Composable。
                val noteText = message.toolNoteRes
                    .map { stringResource(it) }
                    .joinToString(" · ")
                Text(
                    text = noteText,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 正在生成的气泡：工具过程反馈 + 流式文本。
 *
 * `streamingText` 与 `toolTrace` 在**本函数内**收集 —— 这是控制重组范围的关键，
 * 详见 [AiAssistantScreen] 的说明。
 */
@Composable
private fun StreamingBubble(
    streamingText: StateFlow<String>,
    toolTrace: StateFlow<List<AiToolTraceItem>>
) {
    val text by streamingText.collectAsStateWithLifecycle()
    val trace by toolTrace.collectAsStateWithLifecycle()

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(modifier = Modifier.fillMaxWidth(0.9f)) {
            Text(
                text = stringResource(R.string.ai_role_assistant),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = RoundedCornerShape(BUBBLE_CORNER),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    trace.forEach { item ->
                        ToolTraceRow(item = item)
                    }
                    if (trace.isNotEmpty()) Spacer(modifier = Modifier.height(6.dp))

                    if (text.isBlank()) {
                        // 还没有正文：显示"正在思考"，避免用户看到一片空白
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.ai_tool_loading_generic),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            text = text,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolTraceRow(item: AiToolTraceItem) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!item.done) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (item.ok) Icons.Filled.AutoAwesome else Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(item.hintRes) +
                if (item.done) {
                    " · " + stringResource(
                        if (item.ok) R.string.ai_tool_done_ok else R.string.ai_tool_done_empty
                    )
                } else {
                    ""
                },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 提示条
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun InfoBanner(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: Color,
    onContentColor: Color,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = onContentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                color = onContentColor
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ai_settings_cancel), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NotConfiguredBanner(onNavigate: (Destination) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ai_not_configured_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.ai_not_configured_desc),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(onClick = { onNavigate(Destination.AiSettings) }) {
                Text(stringResource(R.string.ai_not_configured_action))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 输入栏
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 输入栏。
 *
 * ## ⚠️ 这里**不能**加 `Modifier.imePadding()`（真机实测踩过）
 *
 * 本页把输入栏放在内容区 Column 的末尾，并已用
 * `windowInsetsPadding(safeDrawing.only(Bottom))` 处理键盘内边距（见 [AiAssistantScreen]）。
 * 再加一层 `imePadding()` 会让内容**多抬一个键盘高度**，在输入栏与键盘之间留下空白。
 */
@Composable
private fun AiInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PAGE_PADDING, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(stringResource(R.string.ai_input_hint), fontSize = 13.sp, maxLines = 1)
                },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() })
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (running) {
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Filled.StopCircle,
                        contentDescription = stringResource(R.string.ai_action_stop),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            } else {
                IconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.ai_action_send),
                        tint = if (enabled && value.isNotBlank()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 首次隐私弹窗
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 首次进入的隐私告知（已定，必须做）。
 *
 * 必须讲清的一件事：**工具在本地执行 ≠ 数据不出设备**。
 * 工具的执行结果仍要回传给模型才能生成回答，本地执行的真正收益是
 * 省上传量、省 token、省延迟，**不是隐私**（§6.10）。
 */
@Composable
private fun PrivacyDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 必须明确选择，点外部不关闭 */ },
        title = { Text(stringResource(R.string.ai_privacy_title)) },
        text = {
            Text(
                text = stringResource(R.string.ai_privacy_body),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(onClick = onAccept) {
                Text(stringResource(R.string.ai_privacy_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.ai_privacy_decline))
            }
        }
    )
}
