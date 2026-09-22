package com.xingheyuzhuan.shiguangschedule.data.auth

/**
 * 从 Keystore 密文中解出的教务凭据。
 *
 * ## 为什么密码是 [CharArray] 而不是 String
 *
 * JVM 的 `String` 不可变，一旦生成就无法擦除，只能等 GC。
 * 用 `CharArray` 才能在使用完毕后主动 `fill('\u0000')`，缩短明文在内存中的停留时间。
 *
 * ## 已知残余风险（**如实记录，不夸大防护效果**）
 *
 * 以下两处仍会存在**不可擦除的 String 副本**，`CharArray` 纪律管不到：
 *
 * 1. **UI 输入框**：Compose 的 `TextField` 值只能是 `String`，
 *    用户输入期间密码就以 String 形式存在于 Compose 状态里。
 * 2. **HTTP 请求体**：`OkHttp` 的 `FormBody.Builder.add(name, value: String)`
 *    只接受 `String`，发请求的瞬间必然产生一份临时副本（生命周期仅限本次请求）。
 *
 * 本类能控制的是**暴露窗口最大的那一份** —— 15 分钟解锁缓存。
 * 把它做成可擦除的 `CharArray`，收益是真实的；但不要因此宣称"内存中零明文"。
 *
 * 持有者用完必须调用 [clear]。
 */
class StoredCredential(
    val account: String,
    password: CharArray
) {
    private var passwordRef: CharArray = password

    /** 密码的**只读**视图。调用方不得修改返回的数组内容。 */
    val password: CharArray get() = passwordRef

    /** 擦除内存中的密码明文。可重复调用。 */
    fun clear() {
        passwordRef.fill('\u0000')
    }
}

/**
 * 设备当前的生物识别可用性。
 *
 * 之所以要区分 [NotEnrolled] 与 [Unsupported]：前者用户自己就能解决
 * （去系统设置录指纹），后者解决不了。账号页要给出不同的提示文案。
 */
enum class BiometricAvailability {
    /** 有可用的 Class 3（强）生物识别，`CryptoObject` 模式可用 */
    Available,

    /** 硬件支持但用户尚未录入任何生物识别 */
    NotEnrolled,

    /** 设备不支持，或只支持 Class 2（弱）生物识别 */
    Unsupported
}

/**
 * [ScnuAuthManager.ensureSession] 的返回值。
 *
 * ## 为什么不返回 Boolean
 *
 * 调用方（教务同步页 / 校园页选课卡 / 选课页）需要**分别处理**这几种情况：
 * 能直接用、要弹指纹、要引导去账号页、被冷却拦住。
 * 压成 Boolean 会让每个调用方各自去猜原因，文案与行为必然发散。
 */
sealed interface SessionResult {

    /** 会话可用，可以立即发教务请求 */
    data object Active : SessionResult

    /** 已保存密码，但需要生物识别解锁（解锁缓存已过 15 分钟） */
    data object NeedsUnlock : SessionResult

    /** 没有已保存的密码（从未保存，或设备不支持生物识别而降级），需手动输入 */
    data object NeedsPassword : SessionResult

    /** 已保存的密码超过 30 天总有效期，已清除，需重新输入 */
    data object Expired : SessionResult

    /** 连续失败触发冷却，[remainingSeconds] 为剩余秒数 */
    data class Locked(val remainingSeconds: Long) : SessionResult

    /** 尝试用已解锁的凭据自动登录，但登录本身失败（网络/凭据失效） */
    data class Failed(val message: String) : SessionResult
}
