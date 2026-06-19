package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.content.Context
import android.widget.Toast
import com.tencent.mm.opensdk.modelbiz.WXLaunchMiniProgram
import com.tencent.mm.opensdk.openapi.WXAPIFactory

/**
 * 微信小程序唤起工具类（单例）。
 *
 * 当前用于启动校园地图小程序，将地图渲染压力转移给微信生态。
 * 使用时需替换 [WECHAT_APP_ID] 和 [SCNU_MAP_GH_ID] 为真实值。
 */
object WeChatMiniProgramLauncher {

    /** 微信开放平台申请的 App ID */
    private const val WECHAT_APP_ID = "YOUR_APP_ID"

    /** 华南师范大学校园地图微信小程序的原始 gh_id */
    private const val SCNU_MAP_GH_ID = "gh_xxxxxxxxxxxx"

    /**
     * 唤起校园地图微信小程序。
     *
     * 自动检测微信是否安装，未安装时通过 Toast 提示用户。
     *
     * @param context 用于创建 WXAPI 及展示 Toast 的 Context
     */
    fun launchMap(context: Context) {
        val api = WXAPIFactory.createWXAPI(context, WECHAT_APP_ID)

        // 检测微信是否已安装（Android 11+ 需 AndroidManifest 中声明 queries）
        if (!api.isWXAppInstalled) {
            Toast.makeText(context, "请先安装微信", Toast.LENGTH_SHORT).show()
            return
        }

        val req = WXLaunchMiniProgram.Req().apply {
            userName = SCNU_MAP_GH_ID
            path = "" // 进入小程序默认主页
            miniprogramType = WXLaunchMiniProgram.Req.MINIPTOGRAM_TYPE_RELEASE
        }
        api.sendReq(req)
    }
}
