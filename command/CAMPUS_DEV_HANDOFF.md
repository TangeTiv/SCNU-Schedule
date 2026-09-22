# 角色与任务

你是「师陶学程」（SCNU Schedule，华师特供课程表）Android 项目的开发助手。请先快速熟悉项目，然后帮我在【校园】页逐个开发功能模块，并严格遵守下方「性能红线」，不得回退现有性能优化。

> **方案依据**：后续功能的完整方案（登录/凭据、社区功能、AI 助手）见
> `command/AUTH_AND_COMMUNITY_PLAN.md`（**定稿 v1.0**）。
> 动手前先读它对应的章节（文末有阅读指引），**不要凭本文档的摘要自行发挥**。

# 项目基本信息

- 仓库位置：D:\Android\shiguangschedule
- 技术栈：Kotlin + Jetpack Compose(Material3) + MVVM + Repository + Hilt DI + Room + DataStore + Navigation3 + Coil3 + WorkManager
- 构建：JDK 21（Temurin），Gradle 9.5，AGP 9.2.1。
  注意：**AGP 9 已启用内置 Kotlin，app 模块不要显式应用 `org.jetbrains.kotlin.android`**；
  插件统一在根 `build.gradle.kts` 用 `apply false` 声明。
- 常用命令：
  - `./gradlew :app:compileDevDebugKotlin`
    ⚠️ `./gradlew` 与 `:app` 之间**必须有空格**。该任务会先跑 `:app:kspDevDebugKotlin`，
    **Hilt / Room 的 KSP 错误也在这一步暴露**。
  - `./gradlew assembleDevDebug` / `./gradlew assembleProdRelease`
  - `./gradlew test`
- 当前状态：`main` 分支 = **v1.6.0（versionCode 12）**，已发布。
  **本次新功能开发分支：`v1.7.0`**（从 main 切出）。

# 架构要点（快速导航）

- 结构：单 `app` 模块 + 一个 `baselineprofile` 测试模块（`com.android.test`，**勿破坏**）
- 导航用 Navigation3：所有目的地定义在
  `app/src/main/java/com/xingheyuzhuan/shiguangschedule/Navigation.kt`
  （`@Serializable sealed interface Destination`）；一级页为
  `CourseSchedule` / `Campus` / `Settings` / `TodaySchedule`
- 页面分发：`MainActivity.kt` 的 `ScreenContent` 里 `when (targetDest)` 分支
  （**`Destination` 加新目的地后不补分支会编译失败**，这是有意设计）
- 【校园】相关：`ui/campus/`（`CampusScreen.kt`、`CampusViewModel.kt`、
  `Exam*`、`Grade*`、`Academic*`、`CourseSelection*`、`SyncSelection*`、`ScnuVerification*`）
- 共享组件：`ui/components/`（`BottomNavigationBar`、`CourseTablePickerDialog` 等）
- 数据层：`data/db/main`（Room）、`data/db/widget`（独立 Room 库）、`data/repository`、
  `data/network`、`data/sync`、`data/model`、`data/di`
- 后台/工具：`service/`（Worker）、`widget/`、`tool/`

# 当前【校园】页结构（动手前务必先 read 这些文件）

`ui/campus/CampusScreen.kt` = `Scaffold`（TopAppBar「发现」+ 齿轮进设置 / 底部 4 个一级 Tab）
+ 一个 `LazyColumn`（`verticalArrangement = spacedBy(24.dp)`），只有 **3 个 `item {}`**：

| item | 内容 |
|---|---|
| ① | `WelcomeCard`（周次 + 星期、校名、`todayCourses` 胶囊行 `LazyRow`） |
| ② | 分组标题「教务与学业」+ `PrimaryServiceGrid` |
| ③ | `Column { SecondaryServiceGrid; Spacer(12dp); TertiaryServiceGrid }` |

**三块网格的实际内容（注意与早期文档不同）**：

| 网格 | 卡片 | 规格 |
|---|---|---|
| `PrimaryServiceGrid` | 教务同步 / 成绩查询 / 考试安排 / **学业情况** | `ServiceCard`，84dp，2×2 |
| `SecondaryServiceGrid` | 图书馆资源 / **校园地图** / 校园渠道 | `SmallServiceCard`，96dp，3 列 |
| `TertiaryServiceGrid` | **选课** + **2 个空预留位** | `SmallServiceCard`，96dp，3 列 |

- 「校园地图」自 v1.6.0 起**已从主网格下沉到次网格**，且是次网格里唯一带 `onClick` 的卡
  （走 `WeChatMiniProgramLauncher.launchMap`）。
- `TertiaryServiceGrid` 右侧两格是 `Spacer(Modifier.weight(1f))`，**为后续模块显式预留**，
  新模块优先挂这里，无需重排已有卡片。

**两条复用约束（读代码得出，不是猜测）**：

1. `ServiceCard` 与 `SmallServiceCard` 都是 **`private fun`**，作用域仅在 `CampusScreen.kt` 内。
   所以「复用」只能在 `CampusScreen.kt` 里加卡片；若别的文件也要用，**必须先放开可见性**——
   这算改动现有文件，**先问**。
2. `SmallServiceCard` 的 `onClick` 是**可空**的（`null` = 纯展示卡）。要新页面就传 lambda。

# 【重要】性能红线（已优化过，任何新代码都必须遵守，否则会回退）

1. 状态收集**只用** `collectAsStateWithLifecycle()`，**禁止** `collectAsState()`
2. **主线程零重活**：DB / 网络 / 解析 / 数据合并放到 `.flowOn(Dispatchers.Default 或 Dispatchers.IO)`。
   Room/DataStore 本身在后台，但你手写的 `map` / `filter` / `merge` 默认跑在主线程
3. **不要在 Composable 函数体里** new `DateTimeFormatter.ofPattern(...)`、
   调 `LocalDate.now()` / `LocalTime.now()` —— 用 `remember {}` 或顶层常量。
   > ✅ **此条已修复，勿回退**：`WelcomeCard` 的 `dayOfWeekName` 已包在 `remember {}` 中，
   > 且 `TextStyle.FULL` / `Locale.CHINESE` 已提为**顶层常量**（`WEEKDAY_TEXT_STYLE` / `WEEKDAY_LOCALE`）。
4. 列表用 `LazyColumn` / `LazyRow`；**禁止在 LazyColumn 里嵌套同方向 Lazy 组件**
   （网格用 `Column + Row`，或把整页改 `LazyVerticalGrid`）
5. **状态要细粒度**：按模块拆 `StateFlow`，避免一个巨型 State 引发整页重组；
   列表类数据可用 `SnapshotStateMap` 按 key 变更做局部重组
   （参考 `ExamViewModel`：暴露 `allExams` / `availableTerms` / `selectedTerm` / `displayedExams`
   四个独立 `StateFlow`，而不是塞进一个 UiState）
6. 每个功能拆独立 `@Composable`（重组最小单位是函数）；含 `List` 字段的 data class 建议标 `@Immutable`
7. **不要改动已有模块的 UI 视觉呈现**，除非我明确要求；新模块可以设计新 UI

# 本次任务

在【校园】页逐个开发功能模块。请先：

1. read：`ui/campus/CampusScreen.kt`、`ui/campus/CampusViewModel.kt`、
   `ui/components/NavigationComponents.kt`、`Navigation.kt`
2. 向我说明你理解的「模块挂载方式」，然后**等我指定要做的具体模块**
   （或我一次列多个，你按顺序逐个做）

# 每个模块的交付要求

- 完成后编译验证：`./gradlew :app:compileDevDebugKotlin`（必要时 `assembleProdRelease`）
- 给出：**改动文件清单、关键实现说明、是否符合性能红线**
- 需要新页面时：在 `Navigation.kt` 加 `Destination`，并在 `MainActivity.kt` 的 `ScreenContent` 注册
- 需要新文案时：**同步 4 份 `strings.xml`**
  （`values/`、`values-zh-rCN/`、`values-zh-rTW/`、`values-en/`）

# 工作方式

- 用中文回复
- **动手前先 read / grep / glob 核实，不要凭猜测改代码**
- 遇到不确定的点（图标、文案、数据来源、是否新开页面等）**先问我**，不要擅自假设
- 声称完成的事，**必须有真实命令与真实输出**，不要说"应该能跑"
