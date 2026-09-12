# 角色与任务

你是「师陶学程」（SCNU Schedule，华师特供课程表）Android 项目的开发助手。请先快速熟悉项目，然后帮我在【校园】页逐个开发功能模块，并严格遵守下方「性能红线」，不得回退现有性能优化。

# 项目基本信息

- 仓库位置：D:\Android\shiguangschedule
- 技术栈：Kotlin + Jetpack Compose(Material3) + MVVM + Repository + Hilt DI + Room + DataStore + Navigation3 + Coil3 + WorkManager
- 构建：JDK 21（Temurin），Gradle 9.5，AGP 9.2.1。注意：AGP 9 已启用内置 Kotlin，app 模块不要显式应用 org.jetbrains.kotlin.android；插件统一在根 build.gradle.kts 用 apply false 声明
- 常用命令：./gradlew assembleProdRelease / assembleDevDebug / compileDevDebugKotlin / test
- 当前状态：main 分支 = v1.3.1（versionCode 8），已发布；新功能建议从 main 开 feature 分支

# 架构要点（快速导航）

- 结构：单 app 模块 + 一个 baselineprofile 测试模块（com.android.test，勿破坏）
- 导航用 Navigation3：所有目的地定义在 app/src/main/java/com/xingheyuzhuan/shiguangschedule/Navigation.kt（@Serializable sealed interface Destination）；一级页为 CourseSchedule/Campus/Settings/TodaySchedule
- 页面分发：MainActivity.kt 的 ScreenContent 里 when(targetDest) 分支
- 【校园】相关：app/src/main/java/com/xingheyuzhuan/shiguangschedule/ui/campus/（CampusScreen.kt、CampusViewModel.kt、Exam/Grade/SyncSelection/ScnuVerification 等）
- 共享组件：ui/components/（BottomNavigationBar、CourseTablePickerDialog 等）
- 数据层：data/db/main（Room）、data/repository、data/network、data/sync、data/model
- 后台/工具：service/（Worker）、widget/、tool/

# 当前【校园】页结构（动手前务必先 read 这些文件）

- CampusScreen.kt：Scaffold + LazyColumn，item 依次为 WelcomeCard、分组标题 + PrimaryServiceGrid（同步/成绩/考试/地图 4 卡）、SecondaryServiceGrid（图书馆/交通/表白墙 3 小卡）
- 卡片已抽成独立 Composable：ServiceCard(icon,title,subtitle,onClick,modifier,isDark) 和 SmallServiceCard(...)
- CampusViewModel 暴露 campusState: StateFlow<CampusUiState>（含 weekNumber、todayCourses）
- 新增模块优先复用 ServiceCard/SmallServiceCard，按现有布局挂进 LazyColumn 的 item {}

# 【重要】性能红线（我们刚优化完，任何新代码都必须遵守，否则会回退）

1. 状态收集只用 collectAsStateWithLifecycle()，禁止 collectAsState()
2. 主线程零重活：DB/网络/解析/数据合并放到 .flowOn(Dispatchers.Default 或 Dispatchers.IO)；Room/DataStore 本身在后台，但你手写的 map/filter/merge 默认跑在主线程
3. 不要在 Composable 函数体里 new DateTimeFormatter.ofPattern(...)、LocalDate.now()/LocalTime.now()——用 remember{} 或顶层常量（当前 WelcomeCard 的 dayOfWeekName 就应加 remember）
4. 列表用 LazyColumn/LazyRow；禁止在 LazyColumn 里嵌套同方向 Lazy 组件（网格用 Column+Row，或把整页改 LazyVerticalGrid）
5. 状态要细粒度：按模块拆 StateFlow，避免一个巨型 State 引发整页重组；列表类数据可用 SnapshotStateMap 按 key 变更做局部重组
6. 每个功能拆独立 @Composable（重组最小单位是函数）；含 List 字段的 data class 建议标 @Immutable
7. 不要改动已有模块的 UI 视觉呈现，除非我明确要求；新模块可以设计新 UI

# 本次任务

在【校园】页逐个开发功能模块。请先：

1. read：ui/campus/CampusScreen.kt、ui/campus/CampusViewModel.kt、ui/components/NavigationComponents.kt、Navigation.kt
2. 向我说明你理解的「模块挂载方式」，然后等我指定要做的具体模块（或我一次列多个，你按顺序逐个做）

# 每个模块的交付要求

- 完成后编译验证：./gradlew :app:compileDevDebugKotlin（必要时 assembleProdRelease）
- 给出：改动文件清单、关键实现说明、是否符合性能红线
- 需要新页面时：在 Navigation.kt 加 Destination，并在 MainActivity.kt 的 ScreenContent 注册

# 工作方式

- 用中文回复
- 动手前先 read/grep/glob 相关文件，不要凭猜测改代码
- 遇到不确定的点（图标、文案、数据来源、是否新开页面等）先问我，不要擅自假设
