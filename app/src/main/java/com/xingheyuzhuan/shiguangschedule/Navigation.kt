package com.xingheyuzhuan.shiguangschedule

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import kotlinx.serialization.Serializable

/**
 * 导航元数据 Key 定义
 */
object ShiguangNavMetadata {
    /** 作用：标记是否为一级主界面，用于控制切换动画（主界面间无过渡） */
    object IsMainScreenKey : NavMetadataKey<Boolean>
}

/**
 * 应用所有目的地（页面）的定义
 * 采用 Kotlin Serialization 实现类型安全的参数传递
 */
@Serializable
sealed interface Destination : NavKey {

    // --- 一级导航页面（底栏对应页面，通常无滑动动画） ---

    @Serializable data object CourseSchedule : Destination
    @Serializable data object Campus : Destination
    @Serializable data object Settings : Destination
    @Serializable data object TodaySchedule : Destination

    // --- 普通功能页面（二级页面，通常使用标准滑动动画） ---

    @Serializable data object TimeSlotSettings : Destination
    @Serializable data object ManageCourseTables : Destination
    @Serializable data object SchoolSelectionListScreen : Destination
    @Serializable data object CourseTableConversion : Destination
    @Serializable data object NotificationSettings : Destination
    @Serializable data object MoreOptions : Destination
    @Serializable data object OpenSourceLicenses : Destination
    @Serializable data object UpdateRepo : Destination
    @Serializable data object QuickActions : Destination
    @Serializable data object TweakSchedule : Destination
    @Serializable data object QuickDelete : Destination
    @Serializable data object ContributionList : Destination
    @Serializable data object CourseManagementList : Destination
    @Serializable data object StyleSettings : Destination
    @Serializable data object ThemeSettings : Destination
    @Serializable data object SyncSelection : Destination
    @Serializable data object ScnuVerification : Destination

    /**
     * 账号（教务登录凭据管理）。
     *
     * 全 App **唯一**的"输入学号密码"入口 —— 教务同步 / 选课 / 成绩 / 考试 /
     * 学业情况都改为读取这里保存的凭据，无凭据时统一引导到本页。
     */
    @Serializable data object Account : Destination

    @Serializable data object Grades : Destination
    @Serializable data object Exams : Destination

    /**
     * 学业情况（培养计划树 + 学分完成度 + 非正式学时）。
     *
     * 无参数：数据由教务同步写入本地 Room，页面自行从数据库订阅；
     * 层级与标签页划分在读取时按树的 depth 现算，不需要导航传参。
     */
    @Serializable data object Academic : Destination

    /**
     * 自主选课（SCNU 教务 `/xsxk` 选课域）。
     *
     * 无参数：模块自身管理登录态与课程缓存，且**退出即清空**（临时沙盒），
     * 因此不需要通过导航参数传递任何状态。
     */
    @Serializable data object CourseSelection : Destination

    /**
     * AI 助手（本地数据问答）。
     *
     * 无参数：对话历史**只在内存里**（已定，退出即清空，不落盘），
     * 因此不需要导航传参，也不新增 Room 表。
     */
    @Serializable data object AiAssistant : Destination

    /**
     * AI 设置（厂商、模型、API Key、图文教程、成本提示）。
     *
     * 与对话页分开：内容量较大（含教程与价格说明），
     * 塞进弹层会很挤，且项目里「账号」「通知设置」也都是独立页面。
     */
    @Serializable data object AiSettings : Destination

    // --- 动态传参页面 ---

    @Serializable
    data class AdapterSelection(
        val schoolId: String,
        val schoolName: String,
        val categoryNumber: Int,
        val resourceFolder: String
    ) : Destination

    @Serializable
    data class WebView(
        val initialUrl: String? = "about:blank",
        val assetJsPath: String? = null
    ) : Destination

    @Serializable
    data class AddEditCourse(
        val courseId: String? = null
    ) : Destination

    @Serializable
    data class CourseManagementDetail(
        val courseName: String
    ) : Destination
}

/**
 * 作用：快速判断目的地是否属于“一级导航”，供 NavEntry 注入元数据
 */
val Destination.isMainScreen: Boolean
    get() = this is Destination.CourseSchedule ||
            this is Destination.Campus ||
            this is Destination.Settings ||
            this is Destination.TodaySchedule