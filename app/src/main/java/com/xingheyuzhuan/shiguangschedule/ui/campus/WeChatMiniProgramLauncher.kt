package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * 微信小程序唤起工具类（单例）。
 *
 * 使用 weixin:// 标准 URL Scheme 唤起微信小程序，
 * 无需微信 SDK 和开放平台关联权限。
 */
object WeChatMiniProgramLauncher {

    private const val WECHAT_SCHEME = "weixin://dl/business/?appid=wxf220360f39f47707&path=pages/scnu/scnu"

    /**
     * 唤起校园地图微信小程序。
     *
     * 通过系统 Intent 打开 weixin:// URL Scheme，
     * 已安装微信的用户直接跳转小程序，未安装则 Toast 提示。
     *
     * @param context 用于启动 Activity 及展示 Toast 的 Context
     */
    fun launchMap(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(WECHAT_SCHEME))
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "请先安装微信", Toast.LENGTH_SHORT).show()
        }
    }
}
