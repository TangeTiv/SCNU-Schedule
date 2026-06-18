package com.xingheyuzhuan.shiguangschedule.data.network

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 信任所有证书的 [X509TrustManager]。
 *
 * **仅用于 SCNU 教务系统！** `sso.scnu.edu.cn` 和 `jwxt.scnu.edu.cn`
 * 使用了自签名或过期证书，标准的证书校验会拒绝连接。
 * 此实现匹配 Python 参考代码中的 `ssl_ctx.verify_mode = ssl.CERT_NONE`。
 *
 * 通过 Hilt `@Named("scnu")` 限定符将其作用域限制在 [ScnuScraper] 使用的
 * OkHttpClient 中，不会被项目的其他网络模块误用。
 */
object ScnuTrustAllManager : X509TrustManager {

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
        // 信任所有客户端证书
        if (chain.isNotEmpty()) chain[0].checkValidity()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        // 信任所有服务器证书
        if (chain.isNotEmpty()) chain[0].checkValidity()
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}
