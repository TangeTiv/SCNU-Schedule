package com.xingheyuzhuan.shiguangschedule.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xingheyuzhuan.shiguangschedule.R

/**
 * 首次启动快速入门引导弹窗。
 *
 * ## 只在全新安装后出现
 *
 * 显示条件由 `SettingsViewModel` 的 `appSettings.onboardingCompleted` 控制
 * （见 `MainActivity`）。**升级安装不会看到它**，只有卸载后重装才会再次出现。
 * 因此这里的内容必须跟着版本更新 —— 否则老用户看不到、新用户看到的是过期说明。
 *
 * ## v1.7.1 更新了什么
 *
 * 原文案有三处已过期：
 * 1. 写着「输入教务系统账号密码」是在【教务同步】里 —— v1.7.0 起凭据统一在
 *    【我的 → 账号】输入，教务同步只读取已保存的凭据；
 * 2. 写着「现阶段导入的是下学期课表」—— 教务同步现在拉的就是**本学期**；
 * 3. 写着「【校园】页面当前提供：教务同步、考试安排、成绩查询」——
 *    现在还有学业情况、选课、图书馆、地图、校园渠道。
 *
 * 同时把所有文案搬进了 `strings.xml`（原先写死中文，英文用户看到的也是中文）。
 */
@Composable
fun OnboardingDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* 禁止点击外部关闭，确保用户看到内容 */ },
        title = {
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                StepItem(
                    step = "1",
                    title = stringResource(R.string.onboarding_step1_title),
                    desc = stringResource(R.string.onboarding_step1_desc)
                )
                StepItem(
                    step = "2",
                    title = stringResource(R.string.onboarding_step2_title),
                    desc = stringResource(R.string.onboarding_step2_desc)
                )
                StepItem(
                    step = "3",
                    title = stringResource(R.string.onboarding_step3_title),
                    desc = stringResource(R.string.onboarding_step3_desc)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.onboarding_notice_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )

                NoteItem(stringResource(R.string.onboarding_notice_1))
                NoteItem(stringResource(R.string.onboarding_notice_2))
                NoteItem(stringResource(R.string.onboarding_notice_3))
                NoteItem(stringResource(R.string.onboarding_notice_4))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_confirm))
            }
        }
    )
}

/**
 * 一个步骤：**加粗的标题 + 小字说明**分两行。
 *
 * 原来是一行「• 步骤 1：点击底部【我的】→ 设置开学日期」，
 * 中文长了会折成两三行、层级也看不出来。拆成两行可读性高得多。
 */
@Composable
private fun StepItem(step: String, title: String, desc: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = "$step. $title",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, top = 1.dp)
        )
    }
}

@Composable
private fun NoteItem(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}
