package com.xingheyuzhuan.shiguangschedule.ui.settings.additional

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.tool.UpdateStatus

/**
 * 启动页面选择弹窗
 */
@Composable
fun StartScreenSelectionDialog(
    showDialog: Boolean,
    currentSelected: StartScreen,
    onDismiss: () -> Unit,
    onConfirm: (StartScreen) -> Unit
) {
    if (!showDialog) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_select_start_screen)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StartScreen.entries.forEach { screen ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(screen) },
                        headlineContent = { Text(stringResource(screen.labelRes)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            RadioButton(selected = screen == currentSelected, onClick = null)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 更新检查结果弹窗（含应用内下载进度）
 */
@Composable
fun UpdateResultDialog(
    showDialog: Boolean,
    updateStatus: UpdateStatus,
    downloadProgress: Int?,
    downloadError: String?,
    onDismiss: () -> Unit,
    onDownloadClick: (String, String?) -> Unit
) {
    if (!showDialog || updateStatus is UpdateStatus.Idle) return

    // 检查中状态
    if (updateStatus is UpdateStatus.Checking) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.dialog_checking_update)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.tip_please_wait))
                }
            },
            confirmButton = {}
        )
        return
    }

    when (updateStatus) {
        is UpdateStatus.Found -> {
            val downloading = downloadProgress != null
            val failed = downloadError != null
            val changelog = updateStatus.flavorInfo.changelog
            val text = if (failed) {
                changelog + "\n\n" + stringResource(R.string.dialog_download_failed, downloadError)
            } else {
                changelog
            }

            AlertDialog(
                onDismissRequest = { if (!downloading) onDismiss() },
                title = { Text(stringResource(R.string.dialog_new_version_found, updateStatus.flavorInfo.latestVersionName)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        if (downloading) {
                            Spacer(Modifier.height(16.dp))
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.dialog_downloading_update, downloadProgress),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    if (!downloading) {
                        Button(onClick = { onDownloadClick(updateStatus.downloadUrl, updateStatus.checksum) }) {
                            Text(stringResource(if (failed) R.string.action_retry else R.string.btn_download_update))
                        }
                    }
                },
                dismissButton = {
                    if (!downloading) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            )
        }

        is UpdateStatus.Latest -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_current_version_latest)) },
            text = { Text(stringResource(R.string.label_version_prefix, updateStatus.versionName)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
            }
        )

        is UpdateStatus.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.dialog_update_check_failed)) },
            text = { Text(stringResource(R.string.label_error_message, updateStatus.message)) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_confirm)) }
            }
        )
    }
}