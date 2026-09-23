package com.xingheyuzhuan.shiguangschedule.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xingheyuzhuan.shiguangschedule.R
import com.xingheyuzhuan.shiguangschedule.data.ai.AiFailure
import com.xingheyuzhuan.shiguangschedule.data.ai.DegradeReason

/**
 * 把 [AiFailure] 映射成本地化文案。
 *
 * ## 为什么失败要分这么细
 *
 * 用户能采取的行动完全不同：
 * - [AiFailure.Unauthorized] → 去改 Key
 * - [AiFailure.InsufficientBalance] → 去充值
 * - [AiFailure.Network] → 检查网络
 *
 * 若统一显示服务端返回的英文原文（`Insufficient Balance`），
 * 大部分学生用户不知道该做什么。
 */
@Composable
fun AiFailure.userMessage(): String = when (this) {
    is AiFailure.Network -> stringResource(R.string.ai_error_network)
    is AiFailure.Timeout -> stringResource(R.string.ai_error_timeout)
    is AiFailure.Unauthorized -> stringResource(R.string.ai_error_unauthorized)
    is AiFailure.RateLimited -> stringResource(R.string.ai_error_rate_limited)
    is AiFailure.InsufficientBalance -> stringResource(R.string.ai_error_insufficient_balance)
    is AiFailure.BadRequest -> stringResource(R.string.ai_error_bad_request)
    is AiFailure.Server -> stringResource(R.string.ai_error_server)
    is AiFailure.BadResponse -> stringResource(R.string.ai_error_bad_response)
    is AiFailure.NotConfigured -> stringResource(R.string.ai_error_not_configured)
    is AiFailure.Unknown -> stringResource(R.string.ai_error_unknown, detail.orEmpty())
}

/** 把降级原因映射成本地化文案。 */
@Composable
fun DegradeReason.userMessage(): String = when (this) {
    DegradeReason.NO_TOOL_FOR_INTENT -> stringResource(R.string.ai_degraded_no_tool)
    DegradeReason.MODEL_WITHOUT_TOOL_SUPPORT ->
        stringResource(R.string.ai_degraded_no_tool_support)
}
