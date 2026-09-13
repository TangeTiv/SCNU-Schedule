package com.xingheyuzhuan.shiguangschedule.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * 只针对**瞬时故障**的退避重试拦截器。
 *
 * ## 为什么需要它
 *
 * 教务系统有偶发的 TLS 断连（参考实现实测到
 * `<urlopen error TLS/SSL connection has been closed (EOF)>`），
 * 同一个请求重试一次通常就成功。而学业情况同步需要连打 **33 个计划点 + 6~8 个学期**
 * 共约 40 次请求，任何一次抖动都会让整轮同步失败 —— 逐项容错只能保住已抓到的数据，
 * 重试才能把失败率真正压下去。
 *
 * ## 重试边界（刻意保守）
 *
 * - **只重试幂等请求**：GET/HEAD/OPTIONS，以及显式标注过 [RETRY_HEADER] 的请求。
 *   学业情况的课程/成绩查询虽然用 POST，但它们都是**纯只读查询**，
 *   所以由调用方打上 [RETRY_HEADER] 显式开启 —— 不靠「POST 就重试」这种危险猜测，
 *   避免将来有人复用同一个 client 做写操作时被静默重发。
 * - **只重试传输层异常与 5xx**：4xx（尤其是教务的业务错误码，如 911
 *   「未登录/无权限」）一律直接返回，重试没有意义。
 * - **不重试 TLS 握手失败**：`SSLHandshakeException` 属于证书/协议配置问题，
 *   重试无益，直接抛出。
 *
 * 退避间隔 1s / 2s，与参考 Python 实现的 `time.sleep(1.0 * (attempt + 1))` 一致。
 */
class RetryInterceptor(
    private val maxAttempts: Int = 3,
    private val initialBackoffMillis: Long = 1000L
) : Interceptor {

    companion object {
        /**
         * 请求头标记：带上它的请求即使方法是 POST 也允许重试。
         *
         * 学业情况抓取器用它标注只读查询请求。
         */
        const val RETRY_HEADER = "X-DSH-Retryable"

        /** 需要重试的 HTTP 方法（天然幂等）。 */
        private val IDEMPOTENT_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val retryableMethod = request.method.uppercase() in IDEMPOTENT_METHODS ||
                request.header(RETRY_HEADER) != null
        if (!retryableMethod || maxAttempts <= 1) {
            return chain.proceed(request)
        }

        var attempt = 0
        while (true) {
            val isLastAttempt = attempt >= maxAttempts - 1
            val response: Response? = try {
                chain.proceed(request)
            } catch (e: IOException) {
                // TLS 握手失败是配置问题，不属于瞬时故障
                if (e is SSLHandshakeException) throw e
                if (isLastAttempt) throw e
                null
            }

            if (response != null) {
                // 仅 5xx 视为服务端瞬时故障；4xx 直接返回交由调用方判断
                if (response.code !in 500..599 || isLastAttempt) return response
                response.close()
            }

            attempt++
            try {
                Thread.sleep(initialBackoffMillis * attempt)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("重试等待被中断", interrupted)
            }
        }
    }
}
