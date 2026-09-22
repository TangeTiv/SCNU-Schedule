package com.xingheyuzhuan.shiguangschedule.data.auth

import android.content.Context
import androidx.biometric.BiometricManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 探测设备当前的生物识别可用性。
 *
 * ## 为什么只认 `BIOMETRIC_STRONG`
 *
 * 密码密钥用 `setUserAuthenticationRequired(true)` 创建，Keystore 要求
 * **Class 3（强）生物识别**才能授权它。只支持 Class 2（弱，部分机型的人脸）
 * 的设备上，`Cipher.init` 能过，但 `BiometricPrompt` 授权后 `doFinal`
 * 仍然会失败 —— 与其让用户在最后一刻失败，不如**提前判定为不可用**，
 * 走"不保存密码、每次手输"的降级路径。
 *
 * 注意 `CryptoObject` 模式**不能**用设备密码（`DEVICE_CREDENTIAL`）替代，
 * 所以这里也不把 `DEVICE_CREDENTIAL` 计入可用性。
 */
@Singleton
class BiometricAvailabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 探测一次可用性。
     *
     * 每次调用都重新探测（不做缓存）—— 用户可能在使用过程中去系统设置里
     * 录了指纹再回来，缓存住会给出错误结论。
     */
    fun check(): BiometricAvailability = when (
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
    ) {
        BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NotEnrolled
        // BIOMETRIC_ERROR_NO_HARDWARE / HW_UNAVAILABLE / SECURITY_UPDATE_REQUIRED /
        // BIOMETRIC_ERROR_UNSUPPORTED / STATUS_UNKNOWN 一律归为"不可用"
        else -> BiometricAvailability.Unsupported
    }
}
