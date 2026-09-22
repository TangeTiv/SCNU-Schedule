package com.xingheyuzhuan.shiguangschedule.ui.account

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.xingheyuzhuan.shiguangschedule.R
import javax.crypto.Cipher

/**
 * 生物识别弹窗的薄封装。
 *
 * ## 为什么必须在 UI 层
 *
 * `BiometricPrompt` 需要一个 [FragmentActivity]，而数据层（`data/auth/`）
 * 刻意不持有任何 Activity 引用 —— 那会造成内存泄漏，也让数据层无法单测。
 * 所以分工是：**数据层产出 `Cipher`，UI 层负责弹指纹，再把授权后的 `Cipher` 交回数据层**。
 *
 * ## 为什么只用 `BIOMETRIC_STRONG`
 *
 * `CryptoObject` 模式**不能**用设备密码（PIN/图案）兜底，且密码密钥在 Keystore 里
 * 要求 Class 3 生物识别。混入 `DEVICE_CREDENTIAL` 只会让"用户过了设备密码验证、
 * 却在 `doFinal` 时抛 `UserNotAuthenticatedException`"这种更难排查的失败。
 */
class BiometricUnlocker(
    private val activity: FragmentActivity?,
    private val title: String,
    private val subtitle: String,
    private val negativeButtonText: String
) {

    /** 当前 Context 能否找到宿主 Activity；找不到时无法弹窗 */
    val isSupported: Boolean get() = activity != null

    /**
     * 弹出生物识别。
     *
     * @param cipher 已 `init` 但**尚未授权**的 cipher
     * @param onSucceeded 认证通过，回调里给出**已授权**的 cipher（同一个实例）
     * @param onFailed 用户取消 / 认证不可用 / 认证错误
     */
    fun authenticate(
        cipher: Cipher,
        onSucceeded: (Cipher) -> Unit,
        onFailed: (String) -> Unit
    ) {
        val host = activity
        if (host == null) {
            onFailed("当前界面无法调起生物识别")
            return
        }

        val prompt = BiometricPrompt(
            host,
            ContextCompat.getMainExecutor(host),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated == null) {
                        onFailed("生物识别未返回可用的密钥句柄")
                    } else {
                        onSucceeded(authenticated)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // ERROR_NEGATIVE_BUTTON / ERROR_USER_CANCELED / ERROR_CANCELED 等都走这里
                    onFailed(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // 单次指纹不匹配。系统弹窗会自己提示"再试一次"，
                    // 此处**刻意不回调** —— 否则一次误触就会把流程打断。
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}

/**
 * 在 Compose 里拿到一个配置好文案的 [BiometricUnlocker]。
 *
 * 文案在这里一次性取好（`stringResource` 只能在 Composable 里调用），
 * 之后 [BiometricUnlocker] 就是一个普通的对象，可以在回调里使用。
 */
@Composable
fun rememberBiometricUnlocker(): BiometricUnlocker {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val title = stringResource(R.string.account_biometric_prompt_title)
    val subtitle = stringResource(R.string.account_biometric_prompt_subtitle)
    val negative = stringResource(R.string.account_biometric_prompt_cancel)
    return remember(activity, title, subtitle, negative) {
        BiometricUnlocker(activity, title, subtitle, negative)
    }
}

/** 沿着 `ContextWrapper` 链向上找宿主 [FragmentActivity]。 */
private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
