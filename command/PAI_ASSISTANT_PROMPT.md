# P-AI · 本地数据问答助手 —— 开发提示词

> 本文件是给**新 session** 的施工说明，自包含。
> **方案依据**：`command/AUTH_AND_COMMUNITY_PLAN.md`（定稿 v1.2），
> 重点读 **§9（AI 功能设计）**、**§13（P-AI 补充决策）**、**§11.1（已定决策）**。
> **冲突时以方案文档为准**，并回报冲突点，不要自行取舍。

---

## 1. 角色与任务

你是「师陶学程」（SCNU Schedule，华师特供课程表）Android 项目的开发助手。
本次任务：**实现本地数据问答助手（P-AI）**。

**一句话目标**：用户在【校园】页进一个 AI 对话页，能用自然语言问自己的
**课表 / 考试 / 成绩 / 学分 / 培养计划 / 非正式学时**，
AI 通过**调用本地工具**取到真实数据后回答——**不靠模型瞎编**。

---

## 2. 项目基本信息

- 仓库：`D:\Android\shiguangschedule`
- 技术栈：Kotlin + Jetpack Compose(Material3) + MVVM + Repository + Hilt DI + Room +
  DataStore + Navigation3 + Coil3 + WorkManager
- 构建：JDK 21（Temurin），Gradle 9.5，AGP 9.2.1
  - **AGP 9 已启用内置 Kotlin**：app 模块**不要**显式应用 `org.jetbrains.kotlin.android`
  - 插件统一在根 `build.gradle.kts` 用 `apply false` 声明
  - 依赖版本统一在 `gradle/libs.versions.toml` 管理
- 常用命令：
  - `./gradlew :app:compileDevDebugKotlin`
    ⚠️ `./gradlew` 与 `:app` 之间**必须有空格**。该任务会**先执行 `:app:kspDevDebugKotlin`**，
    **Hilt / Room 的 KSP 错误在这一步暴露**。
  - `./gradlew assembleDevDebug` / `./gradlew assembleProdRelease`
  - `./gradlew test`
- 当前状态：`main` = **v1.7.1（versionCode 14）**
- **从 `main` 切新分支再动手**（命名沿用版本号风格，与用户确认；不要改版本号与 `update.json`）

---

## 3. 现状基线（已核实，**不要重新猜**）

| 项 | 现状 |
|---|---|
| `data/ai/` | **不存在**，需新建 |
| P0 登录/凭据 | **已完成并发布**（v1.7.0）。`data/auth/` 已有 `CredentialStore` / `ScnuAuthManager` / `AuthStateRepository` / `AuthModels` / `BiometricAvailabilityChecker`；`ui/account/` 已有 `AccountScreen` / `AccountViewModel` / `BiometricUnlocker` |
| Ktor | **已在依赖中且已有使用先例**：`data/ApiDateImporter.kt` 用 `HttpClient { install(Logging); install(ContentNegotiation); install(HttpTimeout) }`，`object` 单例 + `private val client` |
| `okhttp-sse` | **未引入**（只有 `okhttp` 与 `logging-interceptor`） |
| `NetworkModule` | **只提供 `@Named("scnu")` 的实例**，没有任何通用 client |
| 【校园】页 | `ui/campus/CampusScreen.kt`：`LazyColumn` 3 个 `item {}`；`TertiaryServiceGrid` 有 **2 个空预留位**（`Spacer(weight(1f))`） |
| 卡片组件 | `ServiceCard` / `SmallServiceCard` 都是 **`private fun`**，作用域仅在 `CampusScreen.kt` 内 |
| 页面分发 | `MainActivity.kt` 的 `ScreenContent` 里 `when (targetDest)`；**`Destination` 加项后不补分支会编译失败**（有意设计） |
| 多语言 | 新文案需同步 **4 份** `strings.xml`：`values/`、`values-zh-rCN/`、`values-zh-rTW/`、`values-en/` |
| 可参考的写法 | `AuthModels.kt` 的 `sealed interface SessionResult`（成功/失败/需解锁/已锁定）——AI 的结果类型可照此设计；`ExamViewModel` 暴露 4 个独立 `StateFlow` 的细粒度写法 |

---

## 4. ⚠️ 两条安全硬约束（**先看这里**）

### 4.1 绝不能复用 `@Named("scnu")` 的 OkHttpClient

已核实 `data/di/NetworkModule.kt` 里 `@Named("scnu")` 的 client 配置为：

```kotlin
val trustAllCerts = arrayOf<TrustManager>(ScnuTrustAllManager)
val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
OkHttpClient.Builder()
    .sslSocketFactory(sslContext.socketFactory, ScnuTrustAllManager)  // 信任所有证书
    .hostnameVerifier { _, _ -> true }                                // 不校验主机名
```

**它信任所有证书、不校验主机名**（为了兼容校园自签证书）。

**用它调 LLM API 等于关掉证书校验** → 用户的 **API Key 会在可被中间人劫持的信道上传输**。

✅ **正确做法**：照 `data/ApiDateImporter.kt` 的先例**自建 client**（推荐 Ktor，已在依赖中）；
若用 OkHttp，则新建 `@Named("ai")` 的 client，**不要继承 scnu 的任何配置**。

> 另注：scnu client 的 `readTimeout` 是 30 秒。
> **流式（SSE）响应可能持续更久**，AI client 需要更长的读超时，否则会被中途掐断。

### 4.2 API Key 只存在设备本地

- BYOK 模式下 Key 存在用户设备（加密），**绝不打进 APK、绝不上传任何服务器**。
- **日志中绝对不得打印 API Key、学号、密码**。

---

## 5. 本次任务范围

### 要做（对应方案文档 §9）

1. **AI Provider 层**：抽象接口 + DeepSeek 预设 + 自定义厂商
2. **Key 管理**：加密存储、一键测试连通性、图文教程引导
3. **本地工具集**：5 个查询工具 + 分组预留
4. **规则前置意图路由** + **Agent 循环**（工具调用，上限 5 轮）
5. **对话界面**：流式输出、引导卡片、过程反馈、首次隐私弹窗
6. **挂载**：`Destination.AiAssistant` + 【校园】页卡片 + `ScreenContent` 注册

### 不做（明确排除）

- ❌ **不做"AI 替代搜索"**（方案文档 §9.6 用法 A 已否决：搜索能做且更快更准，收益为负）
- ❌ **不做二手集市 / 美食街的具体工具**（模块未设计，参数必改）——**只预留接口**，见 §7.4
- ❌ **不做官方 AI 网关**（BYOK 已定；也**不要**在客户端内置任何官方 Key）
- ❌ **不做多模态 / 截图导入**（已定暂不做）
- ❌ **不改动已有模块的 UI 视觉呈现**

---

## 6. 实现要求

### 6.1 模块划分（建议）

| 模块 | 职责 |
|---|---|
| `data/ai/AiProvider.kt` | **抽象接口** + OpenAI 兼容实现（`/v1/chat/completions`） |
| `data/ai/AiProviderPresets.kt` | 厂商预设：**只放 DeepSeek + 自定义** |
| `data/ai/AiKeyStore.kt` | API Key 的加密存取（见 §6.2） |
| `data/ai/LocalQueryTools.kt` | 5 个本地工具的**声明 + 实现 + 参数校验** |
| `data/ai/IntentRouter.kt` | **规则前置**的意图路由（决定加载哪组工具） |
| `data/ai/AgentLoop.kt` | 工具调用循环 + **5 轮上限** + 降级 |
| `data/ai/AiContextBuilder.kt` | 分层上下文组装（摘要注入） |
| `ui/ai/` | 对话页 + 引导卡片 + 隐私弹窗 + 过程反馈 |

### 6.2 API Key 存储（复用 P0 的加密机制）

**已核实的 P0 资产**（`data/auth/CredentialStore.kt`）：

- 私有工具：`getOrCreateKey(alias, requireUserAuth)`、`encode(iv, ct)`、`decodeRaw`、`decodeIv`
- 密钥别名：`scnu_account_key_v1`（不要求认证）、`scnu_credential_key_v1`（要求认证）
- 密文格式：`Base64(IV(12 字节) ‖ ciphertext)`，`AES/GCM/NoPadding`，256 位

**要求**：

1. AI Key 用**新别名 `ai_key_v1`**，且 **`requireUserAuth = false`**。

   > 理由：它不是教务凭据（泄露后用户可在厂商后台自行重置）；若要求生物识别，
   > **每次对话都要验一次指纹**，体验不可接受。

2. **建议把加密工具抽成共享的 `data/auth/KeystoreCipher.kt`**，供 `CredentialStore` 与 `AiKeyStore` 共用。
   ⚠️ **抽取时必须保证**：`CredentialStore` 的**对外接口不变**、**密文格式不变**
   （P0 已发布，有存量用户数据，改坏会导致用户凭据解不开）。抽取后**先跑一遍现有编译与测试**。

   > 若你判断动已发布代码风险过高，可让 `AiKeyStore` 自行管理密钥，
   > 但**算法与密文格式必须与上面完全一致**，且加密实现只允许存在一份，不得复制粘贴。

3. AI 的 Key 与设置存**独立的 DataStore**（如 `ai_settings`），
   与 `auth_credentials` 分开 —— 避免「一键清除教务凭据」误伤 AI Key，反之亦然。

### 6.3 厂商预设：DeepSeek + 自定义（已定）

**已核实的 DeepSeek 参数（2026-09-22 查官方文档）**：

| 模型 | Tool Calls | 图像理解 | 上下文 | 价格（元/百万 token，空闲时段） |
|---|---|---|---|---|
| `deepseek-flash` | ✅ 支持 | ✅ 支持 | 1M | 输入 1 / 输出 4 |
| `deepseek-v4-pro` | ✅ 支持 | ❌ 不支持 | 1M | 输入 4.5 / 输出 13.5 |

- **base_url（OpenAI 格式）**：`https://api.deepseek.com`
- **模型名**：`deepseek-flash`（推荐，便宜）/ `deepseek-v4-pro`
  （旧名 `deepseek-v4-flash` 仍可调用但**模型已下线**，会被路由到 V4.1-Flash 计费 —— 预设里别用旧名）
- ⚠️ **空闲 / 高峰分时定价**：北京时间工作日 9:00–12:00、14:00–18:00 为高峰，**价格翻倍**。
  做成本提示时要说明。
- ⚠️ **有思考模式**：课表问答这类任务应**显式关闭思考模式**，降低延迟与成本
  （输出 token 比输入贵 4 倍）。
- **两个模型都支持 Tool Calls** → §6.6 的降级路径**只对「自定义」接的第三方模型生效**。

**「自定义」项必须让用户填全**：`base_url` + `model` + `key`，
并**明确提示**"需支持 OpenAI 兼容格式与 Tool Calls，否则只能回答摘要类问题"。

**必须配套的 5 件事**（缺一件这个功能就等于没人用）：

1. **图文教程**：分步截图教"去哪注册、在哪生成 Key、怎么充值"
2. **厂商预设**：DeepSeek + 自定义（已定）
3. **统一 OpenAI 兼容格式**（`/v1/chat/completions`）
4. **一键测试连通性**：填完立刻验证"可用 / 不可用 + 原因"
5. **`AiProvider` 抽象接口**：不写死 OpenAI client，以后接官方网关时不必重写

### 6.4 本地工具集（5 个）+ 分组预留

| 工具 | 参数 | 典型提问 |
|---|---|---|
| `get_courses_by_date_range` | `start_date`, `end_date` | "明天有什么课""下周课表" |
| `get_exams` | `only_upcoming` | "下周期中在哪考""还有几门没考" |
| `get_grades` | `course_name`（可选） | "高数考了多少分" |
| `get_credit_summary` | 无 | "还差多少学分""绩点多少" |
| `get_plan_progress` | `category`（可选） | "还差哪几类学分" |

**工具 description 的写法（决定模型选得对不对，很关键）**：
最有效的写法是**在描述里写清"用户会怎么问"**，例如：

> `get_courses_by_date_range` —— 查询指定日期范围内的课程安排。
> **当用户问"明天有什么课""下周课表""这周三下午有课吗"时调用。**

这远比写"获取课程数据"准确。

#### 三条预留（为后续社区模块，**只预留接口**）

1. **工具定义成 `suspend fun`** + 统一结果包装（成功 / 失败 / 超时）。

   > 后续的二手集市 / 美食街工具读**服务器**，是异步、会失败、有超时的；
   > 若只按同步本地函数设计，以后必须重构 `AgentLoop`。
   > **即使本地工具立即返回，也照此定义。**

2. **工具按来源分组**（`ToolGroup`），支持"按上下文只加载某一组"（见 §6.5）。

3. **`ToolSpec`（声明）与 `ToolHandler`（实现）分离** —— 以后新增模块只需加一对。

#### 明确**不**预留

- ❌ 二手集市 / 美食街的**具体工具签名**（模块未设计，参数必改）
- ❌ 那些模块的 UI 入口与文案

#### 数据边界

- 集市/美食街的**联系方式、定位/打卡记录** —— **不得**作为工具返回值交给模型
- AI 只做**聚合与转述**，不做新论断；摘要**必须标注来源**

### 6.5 路径策略：规则前置 + Agent 兜底（已定）

```
用户提问
  ├ 规则命中（课表 / 考试 / 成绩 / 学分 等明确意图）
  │    → 本地直接查好 → 交给模型翻译
  │      优点：零成本、零延迟、100% 准确、任何模型都能用
  └ 规则未命中（模糊 / 组合 / 需要明细）
       → 走 Agent 循环，让模型自己选工具
```

**规则前置能把约 80% 的提问压成"一次调用"**，体验提升显著。

**工具加载策略（入口在【校园】页，必须按意图路由）**：

```
进 AI 页 → 默认只加载【教务组】（约 5 个工具）
规则命中"二手/卖/买/交易" → 换【二手组】（3 个）
规则命中"美食/吃/店"     → 换【美食组】（3 个）
命中跨模块（如"没课 + 交易"）→ 加载 2 组（6 个）
未命中 → Agent 兜底，但只带**当前已加载的组**，绝不带全部
```

**工具数必须始终 ≤ 6**，不随模块数量线性膨胀。

### 6.6 Agent 循环与降级

- **最大工具调用轮数 = 5**（防死循环烧用户的钱）。
- **降级策略（已定）**：模型不支持 tool use 时，**自动降级**为「规则直查 + 摘要注入」模式，
  并在界面提示「当前模型不支持工具调用，建议换用 DeepSeek」。
  > 注：DeepSeek 两个模型都支持 Tool Calls，因此本降级路径**实际只对「自定义」的第三方模型生效**。
- **参数校验（安全）**：日期格式、课程名长度等**必须校验**，不能让模型传什么就执行什么。
- **工具返回空结果时**：如实说明"没查到"，不要用常识补全。

### 6.7 上下文构建（分层，因为"全覆盖"≠"全塞 prompt"）

```
基础层（必做，任何模型都能用）
  └ 注入精简摘要：本周+下周课表 / 未来考试 / 学分与绩点统计
增强层（模型支持 tool use 时启用）
  └ 本地查询函数：查某天课程明细 / 查某门课成绩 / 查培养计划进度
```

| 数据 | 注入什么 | 不注入什么 |
|---|---|---|
| 课表 | **本周 + 下周** | 全学期 |
| 考试 | **未来未考的** | 已考完的历史 |
| 成绩 | **全部课程明细**（约 50 行，token 可接受） | — |
| 学分 / 绩点 | **本地算好的汇总** | 原始数据 |
| 培养计划 | **类别级汇总**（每类"要求 X / 已修 Y"） | 全部节点明细 |
| 非正式学时 | 汇总 + 明细 | — |

### 6.8 核心设计原则：让模型只做"翻译"，不做"检索"和"计算"

**这是防幻觉的关键**：

- **检索与计算交给本地 Kotlin 代码**（确定性）：学分汇总、绩点、培养计划树遍历**全部在本地算好**。
- **模型只负责把结构化结果翻译成人话**（不确定但无害）。
- 若让模型自己去读整棵培养计划树并汇总，**几乎必然算错**。

**必须写进 system prompt 的硬约束**：

1. **只根据提供的数据回答**；数据里没有的，**明确说"查不到"**，禁止编造。
2. **数值一律由本地算好后再给模型**，禁止模型自行计算。
3. 答不了的问题**不要圆场**，直接说明能力边界。
4. 工具返回空结果时**如实说明**，不要用常识补全。

### 6.9 界面要求

- **入口**：`Destination.AiAssistant` + 【校园】页挂卡片（用 `TertiaryServiceGrid` 的预留位）+ `ScreenContent` 注册
- **流式输出（SSE）必须做**，否则用户等 5–10 秒无反馈会以为卡死
- **过程反馈（UX 硬要求）**：工具调用期间必须显示「正在查询你的课表…」。
  3 轮工具 = 4 次 API 请求，等待明显更长
- **引导卡片**（"问问课表""问问考试""问问学分"）：
  入口在【校园】页会让用户**默认以为"这是教务相关的 AI"**，
  引导卡片既明确意图，也把"这个 AI 能回答什么"讲清楚
- **首次隐私弹窗**（已定）：说明"**课表/成绩（含成绩明细）会发送给你选定的模型服务**"，
  同意后方可使用
- **上下文轮数限制 6–10 轮**，不无限累积（越聊越慢越贵）
- **降级红线**：**AI 不可用不得影响核心功能**（课表/今日/小组件照常）

### 6.10 数据边界澄清（重要，不要误解）

工具在本地执行，**不等于数据不出设备** —— 工具的执行结果**仍要回传给模型**才能生成回答。

本地执行的真正收益是：**省上传量、省 token、省延迟**，**不是隐私**。
隐私告知（首次弹窗同意）**仍然必须做**。

---

## 7. 性能红线（必须遵守，否则会回退已有优化）

1. 状态收集**只用** `collectAsStateWithLifecycle()`，**禁止** `collectAsState()`
2. **主线程零重活**：网络、JSON 解析、上下文组装、数据合并全部在 `Dispatchers.IO` / `Default`；
   手写的 `map` / `filter` / `combine` 要 `.flowOn(...)`
3. **不要在 Composable 函数体里** new `DateTimeFormatter.ofPattern(...)`、
   调 `LocalDate.now()` / `LocalTime.now()` —— 用 `remember {}` 或顶层常量
4. 列表用 `LazyColumn` / `LazyRow`；**禁止在 LazyColumn 里嵌套同方向 Lazy 组件**
5. **状态要细粒度**：流式文本、工具调用状态、会话列表、Provider 配置**各拆独立 `StateFlow`**，
   避免一个巨型 State 引发整页重组（参考 `ExamViewModel`）
6. 每个功能拆独立 `@Composable`；含 `List` 字段的 data class 标 `@Immutable`
7. **不要改动已有模块的 UI 视觉呈现**

---

## 8. 交付要求

1. **编译验证**：`./gradlew :app:compileDevDebugKotlin` 必须通过；
   提交前再跑一次 `./gradlew assembleProdRelease`。
   **贴出真实命令与真实输出**，不要说"应该能跑"。
2. 给出：
   - **改动文件清单**（新增 / 修改分开列）
   - **关键实现说明**（尤其：工具集与分组、意图路由规则、Agent 循环与轮数上限、降级逻辑、加密复用方式）
   - **是否符合性能红线**（逐条对照 §7）
   - **遗留问题与未验证项**（诚实列出，例如"未接真实 API Key 跑通端到端"）
3. 需要新页面时：在 `Navigation.kt` 加 `Destination`，并在 `MainActivity.kt` 的 `ScreenContent` 注册。
4. 需要新文案时：同步 **4 份** `strings.xml`。

---

## 9. 工作方式

- 用**中文**回复
- **动手前先 read / grep / glob 核实**，不要凭猜测改代码
- 遇到不确定的点（模型参数、Prompt 文案、UI 形态、是否新建页面等）**先问，不要擅自假设**
- 涉及**安全**（密钥、证书校验、数据边界）与**成本**（token、轮数、分时定价）的取舍，
  **先提出、等我确认**
- 若发现方案文档与本提示词冲突，**以方案文档为准并回报冲突点**
