package com.xingheyuzhuan.shiguangschedule.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存级别的 OkHttp [CookieJar] 实现。
 *
 * 使用 [ConcurrentHashMap] 按 host 存储 Cookie 列表，线程安全。
 * 匹配 Python 参考代码中 `http.cookiejar.CookieJar` 的行为 ——
 * 仅在内存中持有，进程重启后丢失，不会持久化到磁盘。
 *
 * 用于保证从 SSO 登录到请求教务系统的过程中 Session (Cookie) 一直保持有效。
 */
class ScnuCookieJar : CookieJar {

    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        store.getOrPut(host) { mutableListOf() }.let { existing ->
            // 移除同名的旧 Cookie，用新的覆盖
            cookies.forEach { newCookie ->
                existing.removeAll { it.name == newCookie.name }
                existing.add(newCookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostCookies = store[url.host] ?: return emptyList()
        // 过滤出未过期的 Cookie
        val now = System.currentTimeMillis()
        return hostCookies.filter { cookie ->
            cookie.expiresAt > now
        }
    }

    /** 清空所有 Cookie（用于登出/重置会话） */
    fun clear() {
        store.clear()
    }
}
