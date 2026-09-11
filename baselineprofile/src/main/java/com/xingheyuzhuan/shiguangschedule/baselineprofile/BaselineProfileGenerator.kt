package com.xingheyuzhuan.shiguangschedule.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集应用关键路径的基线配置文件。
 *
 * 运行方式（需连接真机/模拟器）：
 *   ./gradlew :baselineprofile:collectNonMinifiedReleaseBaselineProfile
 * 生成的 profile 会自动写入 baselineprofile 模块，应用 release 构建会自动打包，
 * 重新运行 :app:assembleProdRelease 即可生效。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generateBaselineProfile() {
        baselineProfileRule.collect(
            packageName = "com.xingheyuzhuan.shiguangschedule",
        ) {
            pressHome()
            startActivityAndWait()

            // 等待首屏（课表）渲染完成
            device.waitForIdle()

            val w = device.displayWidth
            val h = device.displayHeight
            val midY = h / 2

            // 周课表左右滑动：捕获 HorizontalPager 滚动与课程块绘制路径
            repeat(2) {
                device.swipe((w * 0.85f).toInt(), midY, (w * 0.15f).toInt(), midY, 200)
                device.waitForIdle()
            }
            repeat(2) {
                device.swipe((w * 0.15f).toInt(), midY, (w * 0.85f).toInt(), midY, 200)
                device.waitForIdle()
            }

            // 依次切换底部导航页，捕获各页面首帧与初始化路径
            listOf("校园", "今日课表", "我的", "课表").forEach { label ->
                device.wait(Until.findObject(By.text(label)), 5_000)?.click()
                device.waitForIdle()
            }
        }
    }
}
