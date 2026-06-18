package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.network.GradeItem
import com.xingheyuzhuan.shiguangschedule.data.network.ExamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SCNU 教务数据抓取调试验证页面。
 * 从 CampusScreen 的「成绩查询」卡片进入，用于端到端测试
 * login → fetchGrades → fetchExams 完整流程。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScnuVerificationScreen(
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit
) {
    val viewModel: ScnuVerificationViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf("202421315041") }
    var password by remember { mutableStateOf("") }
    var isLoggedIn by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<LogEntry>()) }
    var grades by remember { mutableStateOf<List<GradeItem>>(emptyList()) }
    var exams by remember { mutableStateOf<List<ExamItem>>(emptyList()) }

    val listState = rememberLazyListState()

    fun addLog(msg: String, isError: Boolean = false) {
        logs = logs + LogEntry(msg, isError)
    }

    // 自动滚动到最新日志
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("教务数据调试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 凭据输入 ──
            OutlinedTextField(
                value = account,
                onValueChange = { account = it },
                label = { Text("学号 / 账号") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            // ── 操作按钮 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            addLog("══════ SSO 登录 ══════")
                            try {
                                withContext(Dispatchers.IO) {
                                    viewModel.scraper.login(account, password)
                                }
                                addLog("✅ 登录成功 — Session 已建立")
                                isLoggedIn = true
                            } catch (e: Exception) {
                                addLog("❌ ${e.message}", isError = true)
                            }
                            isLoading = false
                        }
                    },
                    enabled = !isLoading && account.isNotBlank() && password.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("登录")
                }

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            addLog("══════ 获取成绩 ══════")
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    viewModel.scraper.fetchGrades()
                                }
                                grades = result
                                addLog("✅ 共 ${result.size} 条成绩")
                                result.take(20).forEach { g ->
                                    addLog("  ${g.xnmmc} | ${g.xqmmc} | ${g.kcmc} | ${g.cj} | 绩点${g.jd}")
                                }
                                if (result.size > 20) addLog("  ... 还有 ${result.size - 20} 条")
                            } catch (e: Exception) {
                                addLog("❌ ${e.message}", isError = true)
                            }
                            isLoading = false
                        }
                    },
                    enabled = isLoggedIn && !isLoading
                ) {
                    Text("获取成绩")
                }

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            addLog("══════ 获取考试安排 ══════")
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    viewModel.scraper.fetchExams()
                                }
                                exams = result
                                addLog("✅ 共 ${result.size} 条考试安排")
                                result.forEach { e ->
                                    addLog("  ${e.kssj} | ${e.kcmc} | ${e.cdmc}${e.cdbh} | ${e.cdxqmc}")
                                }
                            } catch (e: Exception) {
                                addLog("❌ ${e.message}", isError = true)
                            }
                            isLoading = false
                        }
                    },
                    enabled = isLoggedIn && !isLoading
                ) {
                    Text("获取考试")
                }
            }

            // ── 状态摘要 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val statusColor = if (isLoggedIn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
                Text("状态: ${if (isLoggedIn) "已登录" else "未登录"}", color = statusColor)
                if (grades.isNotEmpty()) Text("成绩: ${grades.size} 条")
                if (exams.isNotEmpty()) Text("考试: ${exams.size} 条")
            }

            // ── 日志输出 ──
            Text("日志输出", style = MaterialTheme.typography.labelLarge)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.small
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { entry ->
                        Text(
                            text = entry.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (entry.isError)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private data class LogEntry(val message: String, val isError: Boolean = false)
