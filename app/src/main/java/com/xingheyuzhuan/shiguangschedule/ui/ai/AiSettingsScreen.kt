package com.xingheyuzhuan.shiguangschedule.ui.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.ai.AiModelOption
import com.xingheyuzhuan.shiguangschedule.data.ai.AiPeakWindow
import com.xingheyuzhuan.shiguangschedule.data.ai.AiProviderPreset
import com.xingheyuzhuan.shiguangschedule.data.ai.AiProviderPresets
import com.xingheyuzhuan.shiguangschedule.data.ai.AiTutorialIllustration
import com.xingheyuzhuan.shiguangschedule.data.ai.AiTutorialStep
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

private val PAGE_PADDING = 16.dp
private val CARD_SPACING = 16.dp

/**
 * 【AI 设置】页。
 *
 * ## 为什么单独一页而不是对话页里的弹层
 *
 * 内容量不小：厂商选择、模型、base_url、Key、测试结果、成本提示、图文教程、
 * 工具能力状态。塞进 `ModalBottomSheet` 会非常挤，也不便滚动阅读教程。
 * 项目里「账号」「通知设置」等也都是独立 Destination，保持一致。
 *
 * ## 图文教程为什么是"自绘"
 *
 * 我们无法产出厂商官网的真实截图（截图会随对方改版过期，且涉及版权）。
 * 因此用 Compose 画的**步骤示意图** + 文字说明 + 官网直达链接代替，
 * 并把关键信息（Key 只显示一次、需要先充值）写在描述里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel()
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val maskedKey by viewModel.maskedKey.collectAsStateWithLifecycle()
    val testState by viewModel.testState.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    val preset = AiProviderPresets.byId(form.providerId)

    LaunchedEffect(toast) {
        val res = toast ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(res), Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.ai_settings_title), fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // 与项目其它页面一致（AccountScreen / ExamScreen 都用 a11y_back）
                            contentDescription = stringResource(R.string.a11y_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                horizontal = PAGE_PADDING,
                vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(CARD_SPACING)
        ) {
            item(key = "provider") {
                ProviderCard(
                    form = form,
                    preset = preset,
                    maskedKey = maskedKey,
                    onSelectProvider = viewModel::selectProvider,
                    onUpdateBaseUrl = viewModel::updateBaseUrl,
                    onUpdateModel = viewModel::updateModel,
                    onUpdateKey = viewModel::updateApiKeyInput,
                    onToggleKeyVisible = viewModel::toggleKeyVisible
                )
            }

            item(key = "actions") {
                ActionsCard(
                    testState = testState,
                    onSave = viewModel::save,
                    onTest = viewModel::testConnection,
                    onClearKey = { showClearConfirm = true },
                    hasKey = snapshot.hasKey
                )
            }

            item(key = "tool") {
                ToolSupportCard(
                    preset = preset,
                    toolUseUnsupported = snapshot.toolUseUnsupported,
                    onRecheck = viewModel::recheckToolSupport
                )
            }

            if (preset.hasTimeOfDayPricing) {
                item(key = "cost") {
                    CostCard(preset = preset)
                }
            }

            if (preset.tutorialSteps.isNotEmpty()) {
                item(key = "tutorial") {
                    TutorialCard(preset = preset)
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.ai_settings_clear_confirm_title)) },
            text = { Text(stringResource(R.string.ai_settings_clear_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearKey()
                        showClearConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.ai_settings_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.ai_settings_cancel))
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 厂商与凭据
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ProviderCard(
    form: AiSettingsForm,
    preset: AiProviderPreset,
    maskedKey: String?,
    onSelectProvider: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateKey: (String) -> Unit,
    onToggleKeyVisible: () -> Unit
) {
    SettingsCard(title = stringResource(R.string.ai_settings_section_provider)) {
        // ── 厂商选择 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProviderPresets.ALL.forEach { item ->
                FilterChip(
                    selected = form.providerId == item.id,
                    onClick = { onSelectProvider(item.id) },
                    label = {
                        Text(
                            text = stringResource(
                                if (item.id == AiProviderPresets.DEEPSEEK_ID) {
                                    R.string.ai_settings_provider_deepseek
                                } else {
                                    R.string.ai_settings_provider_custom
                                }
                            )
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── 模型 ──
        Text(
            text = stringResource(R.string.ai_settings_model),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (preset.models.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                preset.models.forEach { option ->
                    FilterChip(
                        selected = form.model == option.id,
                        onClick = { onUpdateModel(option.id) },
                        label = {
                            Text(
                                text = if (option.recommended) {
                                    "${option.id} ★"
                                } else {
                                    option.id
                                },
                                fontSize = 12.sp
                            )
                        }
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = form.model,
                onValueChange = onUpdateModel,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("deepseek-flash", fontSize = 13.sp) }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── base_url ──
        Text(
            text = stringResource(R.string.ai_settings_base_url),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(6.dp))
        if (preset.allowsCustomBaseUrl) {
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = onUpdateBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://api.example.com/v1", fontSize = 13.sp) }
            )
        } else {
            Text(
                text = preset.baseUrl,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.ai_settings_base_url_fixed),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (AiProviderPresets.isInsecureBaseUrl(form.baseUrl)) {
            Spacer(modifier = Modifier.height(6.dp))
            InlineWarning(text = stringResource(R.string.ai_settings_insecure_warning))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── API Key ──
        Text(
            text = stringResource(R.string.ai_settings_api_key),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        if (maskedKey != null) {
            Text(
                text = stringResource(R.string.ai_settings_api_key_saved, maskedKey),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = form.apiKeyInput,
            onValueChange = onUpdateKey,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    text = if (maskedKey != null) {
                        stringResource(R.string.ai_settings_api_key_replace_hint)
                    } else {
                        stringResource(R.string.ai_settings_api_key_hint)
                    },
                    fontSize = 13.sp
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (form.keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onToggleKeyVisible) {
                    Icon(
                        imageVector = if (form.keyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (form.keyVisible) R.string.ai_settings_hide else R.string.ai_settings_show
                        )
                    )
                }
            }
        )

        if (preset.id == AiProviderPresets.CUSTOM_ID) {
            Spacer(modifier = Modifier.height(8.dp))
            InlineWarning(text = stringResource(R.string.ai_settings_custom_warning))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 操作与测试结果
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ActionsCard(
    testState: AiTestState,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClearKey: () -> Unit,
    hasKey: Boolean
) {
    SettingsCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ai_settings_save))
            }
            OutlinedButton(
                onClick = onTest,
                modifier = Modifier.weight(1f),
                enabled = testState != AiTestState.Testing
            ) {
                if (testState == AiTestState.Testing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_settings_testing))
                } else {
                    Text(stringResource(R.string.ai_settings_test))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        when (testState) {
            AiTestState.Idle -> Unit

            AiTestState.Testing -> Unit

            is AiTestState.Ok -> ResultRow(
                ok = true,
                text = testState.modelEcho?.let {
                    stringResource(R.string.ai_settings_test_ok, it, testState.latencyMillis)
                } ?: stringResource(R.string.ai_settings_test_ok_no_model, testState.latencyMillis)
            )

            is AiTestState.Failed -> ResultRow(
                ok = false,
                text = stringResource(
                    R.string.ai_settings_test_failed,
                    testState.failure.userMessage()
                )
            )
        }

        if (hasKey) {
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onClearKey) {
                Text(
                    text = stringResource(R.string.ai_settings_clear_key),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun ResultRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 工具调用能力
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ToolSupportCard(
    preset: AiProviderPreset,
    toolUseUnsupported: Boolean,
    onRecheck: () -> Unit
) {
    val supported = preset.declaredToolCalls || !toolUseUnsupported

    SettingsCard(title = stringResource(R.string.ai_settings_section_tool)) {
        ResultRow(
            ok = supported,
            text = stringResource(
                if (supported) R.string.ai_settings_tool_supported
                else R.string.ai_settings_tool_unsupported
            )
        )
        if (!supported) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRecheck) {
                Text(stringResource(R.string.ai_settings_tool_recheck), fontSize = 13.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 成本提示
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CostCard(preset: AiProviderPreset) {
    // 性能红线 3：不在 Composable 函数体里直接调 LocalTime.now() / LocalDate.now()。
    // remember 之后整场只求值一次 —— 峰值状态本来也不需要秒级刷新。
    val isPeakNow = remember {
        val now = LocalTime.now()
        val weekday = LocalDate.now().dayOfWeek
        val isWeekday = weekday != DayOfWeek.SATURDAY && weekday != DayOfWeek.SUNDAY
        isWeekday && AiProviderPresets.DEEPSEEK_PEAK_WINDOWS.any {
            now.hour in it.startHour until it.endHour
        }
    }

    SettingsCard(title = stringResource(R.string.ai_settings_section_cost)) {
        val inputLabel = stringResource(R.string.ai_settings_cost_input)
        val outputLabel = stringResource(R.string.ai_settings_cost_output)
        val perMillion = stringResource(R.string.ai_settings_cost_per_million)
        val offPeak = stringResource(R.string.ai_settings_cost_off_peak)

        preset.models.forEach { option ->
            PriceRow(
                option = option,
                inputLabel = inputLabel,
                outputLabel = outputLabel,
                perMillion = perMillion,
                offPeakLabel = offPeak
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ai_settings_cost_peak_title),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        val windows = AiProviderPresets.DEEPSEEK_PEAK_WINDOWS
        if (windows.size >= 2) {
            Text(
                text = stringResource(
                    R.string.ai_settings_cost_peak_windows,
                    windows[0].label(),
                    windows[1].label()
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isPeakNow) {
            Spacer(modifier = Modifier.height(4.dp))
            InlineWarning(text = stringResource(R.string.ai_settings_cost_peak_now))
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.ai_settings_cost_note),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PriceRow(
    option: AiModelOption,
    inputLabel: String,
    outputLabel: String,
    perMillion: String,
    offPeakLabel: String
) {
    val price = option.price ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = option.id,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = buildString {
                append(inputLabel)
                append(" $")
                append(formatPrice(price.inputCacheMissOffPeak))
                append(" / ")
                append(outputLabel)
                append(" $")
                append(formatPrice(price.outputOffPeak))
                append(" · ")
                append(perMillion)
                append("（")
                append(offPeakLabel)
                append("）")
            },
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 价格显示：货币惯例保留两位小数（`$0.15` / `$0.60` / `$1.98`）。
 *
 * 不用"去掉多余尾零"的写法 —— 那会让同一行里出现 `$0.15` 与 `$0.6` 两种精度，
 * 看起来像数据不一致。
 */
private fun formatPrice(value: Double): String = String.format(Locale.US, "%.2f", value)

/** 高峰窗口的展示文案，如 `09:00–12:00`。 */
private fun AiPeakWindow.label(): String =
    String.format(Locale.US, "%02d:00–%02d:00", startHour, endHour)

// ═══════════════════════════════════════════════════════════════════════════
// 图文教程（自绘示意图）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TutorialCard(preset: AiProviderPreset) {
    val context = LocalContext.current

    SettingsCard(title = stringResource(R.string.ai_settings_section_tutorial)) {
        preset.tutorialSteps.forEachIndexed { index, step ->
            TutorialStepRow(index = index + 1, step = step)
            if (index != preset.tutorialSteps.lastIndex) {
                Spacer(modifier = Modifier.height(14.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preset.consoleUrl?.let { url ->
                OutlinedButton(onClick = { openUrl(context, url) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_settings_open_console), fontSize = 12.sp)
                }
            }
            preset.apiKeysUrl?.let { url ->
                OutlinedButton(onClick = { openUrl(context, url) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_settings_open_api_keys), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TutorialStepRow(index: Int, step: AiTutorialStep) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = index.toString(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(step.titleRes),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        TutorialIllustration(
            kind = step.illustration,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(step.descRes),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 步骤示意图。
 *
 * 用 Compose 画而不是放位图：位图会随厂商改版过期，而且多一套资源要维护；
 * 图形只表达"这一步在做什么"，不模仿对方界面细节（避免误导）。
 */
@Composable
private fun TutorialIllustration(
    kind: AiTutorialIllustration,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        when (kind) {
            AiTutorialIllustration.OPEN_SITE -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 浏览器窗口：顶部三个圆点 + 地址栏
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(fg.copy(alpha = if (i == 0) 0.7f else 0.35f))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.25f))
                    )
                }
            }

            AiTutorialIllustration.RECHARGE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(72.dp)
                            .height(34.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.2f))
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 6.dp, top = 5.dp)
                                .width(24.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(fg.copy(alpha = 0.4f))
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.55f))
                    )
                }
            }

            AiTutorialIllustration.CREATE_KEY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fg.copy(alpha = 0.18f))
                    ) {
                        Text(
                            text = "sk-••••••••••••",
                            fontSize = 10.sp,
                            color = fg,
                            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent)
                    )
                }
            }

            AiTutorialIllustration.PASTE_KEY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(28.dp)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(accent.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "→", fontSize = 14.sp, color = fg)
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(fg.copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 6.dp)
                                .width(2.dp)
                                .height(12.dp)
                                .background(accent)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 通用小件
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsCard(
    title: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (title != null) {
                Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun InlineWarning(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

/** 用系统浏览器打开厂商官网（不在 App 内嵌 WebView，避免被误认为官方页面）。 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
