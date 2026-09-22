# P0 · 登录与教务凭据复用 —— 开发提示词

> 本文件是给**新 session** 的施工说明，自包含。
> **方案依据**：`command/AUTH_AND_COMMUNITY_PLAN.md`（定稿 v1.0），
> 重点读 **§3（一个身份、两个会话）**、**§4（P0 设计）**、**§11.1（已定决策）**。
> **冲突时以方案文档为准**，并回报冲突点，不要自行取舍。

---

## 1. 角色与任务

你是「师陶学程」（SCNU Schedule，华师特供课程表）Android 项目的开发助手。
本次任务：**实现登录与教务凭据复用（P0）**。

**一句话目标**：用户**只在【我的 → 账号】输入一次学号密码**，
之后进「教务同步 / 选课 / 成绩 / 考试 / 学业情况」都**不再要求输入**。

---

## 2. 项目基本信息

- 仓库：`D:\Android\shiguangschedule`
- 技术栈：Kotlin + Jetpack Compose(Material3) + MVVM + Repository + Hilt DI + Room +
  DataStore + Navigation3 + Coil3 + WorkManager
- 构建：JDK 21（Temurin），Gradle 9.5，AGP 9.2.1
  - **AGP 9 已启用内置 Kotlin**：app 模块**不要**显式应用 `org.jetbrains.kotlin.android`
  - 插件统一在根 `build.gradle.kts` 用 `apply false` 声明
  - 依赖版本统一在 `gradle/libs.versions.toml`（version catalog）管理
- 常用命令：
  - `./gradlew :app:compileDevDebugKotlin`
    ⚠️ `./gradlew` 与 `:app` 之间**必须有空格**。该任务会**先执行 `:app:kspDevDebugKotlin`**，
    **Hilt / Room 的 KSP 错误在这一步暴露**。
  - `./gradlew assembleDevDebug` / `./gradlew assembleProdRelease`
  - `./gradlew test`
- 当前状态：`main` = **v1.6.0（versionCode 12）**
- **本次开发分支：`v1.7.0`**（从 main 切出，先建分支再动手）

---

## 3. 现状基线（已核实，**不要重新猜**）

| 项 | 现状 |
|---|---|
| 登录实现 | **两套并存**：<br>`ScnuScraper.login(account, password)` —— 只走 fastlogin 兜底路径（被教务同步使用）<br>`ScnuSsoLogin.login(account, password)` —— auth.html + fastlogin 双路径（被选课使用）<br>后者注释挂着 `TODO(v1.6.0): 评估将 ScnuScraper.login 迁移到本类` |
| 会话 | `ScnuCookieJar` 为**纯内存** `ConcurrentHashMap`，进程重启即丢；两者共用同一个 `@Named("scnu")` OkHttpClient 与 CookieJar |
| 密码持久化 | **零**。`ScnuSsoLogin.login()` 的 KDoc 明确写「密码不做任何持久化」 |
| 学号持久化 | **已有**：DataStore 键 `CampusSyncViewModel.KEY_CAMPUS_ACCOUNT = stringPreferencesKey("campus_account")` |
| ⚠️ 重复写入 | **两处都在写同一个键**：`CampusSyncViewModel.startSync()` 与 `CourseSelectionViewModel.login()` 各写一次 `dataStore.edit { it[KEY_CAMPUS_ACCOUNT] = ... }` —— **本次要收敛为一处** |
| 加密相关代码 | **全项目没有**任何 `AndroidKeyStore` / `EncryptedSharedPreferences` / `BiometricPrompt` 使用 |
| 相关依赖 | **没有** `androidx.biometric`，也**没有** `androidx.security:security-crypto` |
| `MainActivity` | `class MainActivity : AppCompatActivity()`（第 69 行）<br>✅ **已经是 `AppCompatActivity`** → 继承 `FragmentActivity` → **`BiometricPrompt` 可直接用，无需改基类** |
| 【我的】页 | = `Destination.Settings`（`nav_settings` = "我的"）→ `ui/settings/SettingsScreen.kt`（561 行）<br>约 340–380 行是一串 `onClick = { onNavigate(Destination.X) }` 条目，**新入口加在这附近** |
| 页面分发 | `MainActivity.kt` 的 `ScreenContent` 里 `when (targetDest)`；**`Destination` 加项后不补分支会编译失败**（有意设计） |
| 多语言 | 新文案需同步 **4 份** `strings.xml`：`values/`、`values-zh-rCN/`、`values-zh-rTW/`、`values-en/` |

### 现有凭据入口（本次要改造的地方）

| 位置 | 现状 |
|---|---|
| `ui/campus/SyncSelectionScreen.kt` | 页面内**手输** `account` / `password`（第 110–111 行），学号从 `viewModel.savedAccount` 预填（第 114–119 行） |
| `ui/campus/CampusSyncViewModel.kt` | `startSync(account, password, ...)` → 第 144 行 `scraper.login(account, password)`；第 150 行写 `_savedAccount` |
| `ui/campus/CourseSelectionViewModel.kt` | `login(account, password)`（347 行）、`loginFromCampusDialog(account, password)`（409 行）、`reLogin(account, password)`（437 行）、`clearSession(clearUserData)`（1014 行）、`hasActiveSession()`（1052 行）；注入 `ScnuSsoLogin` |
| `ui/campus/CourseSelectionDialogs.kt` | `internal fun CourseSelectionLoginDialog(viewModel, onSuccess, onDismiss)`（624 行），页面内**手输** |

---

## 4. 本次任务范围

### 要做（对应方案文档 §4）

1. **统一两套登录实现**，消化掉 `ScnuSsoLogin` 里挂着的 TODO
2. **Keystore 加密持久化凭据**（学号 + 密码），配套生物识别
3. **【我的】页新增「账号」入口** → 新增 `Destination.Account` 与账号页
4. **改造四处凭据入口**，改为从凭据仓库读取，不再手输
5. **凭据失效 / 失败锁定的完整流程**
6. **风险告知 + 一键清除**

### 不做（明确排除，不要顺手做）

- ❌ **不做全 App 门禁**：课表 / 今日 / 设置**必须保持免登录离线可用**
- ❌ **不做"每次更新要重新登入"**（已否决：Android 更新不清数据，强制清凭据无安全收益）
- ❌ **不做社区账号**（那是 P1，需要后端；本次只做**教务会话**）
- ❌ **不做 AI 助手**（那是 P-AI，另一份提示词）
- ❌ **密码绝不上传任何服务器**（本次也没有服务器，但这条原则要守住）

---

## 5. 实现要求

### 5.1 模块划分（建议，可按需微调）

| 模块 | 职责 |
|---|---|
| `data/auth/CredentialStore.kt` | Keystore + AES-GCM 加解密；读写 DataStore 密文；清除 |
| `data/auth/ScnuAuthManager.kt` | **合并**两套登录实现；对外暴露 `login()` / `ensureSession()` / `logout()` |
| `data/auth/AuthStateRepository.kt` | 暴露 `StateFlow<AuthState>`（未登录 / 已登录 / 凭据失效 / 锁定 / 需生物识别） |
| `ui/account/AccountScreen.kt` + `AccountViewModel.kt` | 账号页（`Destination.Account`） |

### 5.2 CredentialStore —— 加密与生物识别

**技术要点**：

- 密钥：`KeyGenerator.getInstance("AES", "AndroidKeyStore")` +
  `KeyGenParameterSpec`，算法 `AES/GCM/NoPadding`。
- 加密：每次加密生成 **12 字节随机 IV**，密文与 IV 一起 Base64 后存 DataStore
  （**不要复用 IV**）。
- 密钥创建参数建议加 `setInvalidatedByBiometricEnrollment(true)`
  （用户新增指纹时作废旧密钥，需重新输入密码——这是安全上的正确行为）。

**生物识别模式：`CryptoObject` + 应用层会话缓存（方案文档 §4.3 的折中）**

```
会话有效期内（15 分钟）→ 直接用内存中的凭据，不碰 Keystore、不弹指纹
会话过期            → BiometricPrompt + CryptoObject 解密 → 再开 15 分钟
```

- 这样避免"每次取凭据都弹指纹"的骚扰，也避免"后台静默重登"（那是做不到的：
  `setUserAuthenticationRequired(true)` 会使密钥**只能在用户认证后的短窗口内使用**）。
- ⚠️ **必须处理"用户未录入生物识别"**：`CryptoObject` 模式**只能用生物识别，不能用设备密码**。
  此时**降级为"不保存密码，每次手输"**，并在账号页明确提示原因。
  （不要为了"能用"而把密码明文存下来。）

**依赖**（需新增，写进 `gradle/libs.versions.toml` 后由 `app/build.gradle.kts` 引用）：

- `androidx.biometric:biometric:1.1.0`（当前稳定版；1.4.0 系列仍是 alpha，
  其中 `biometric-compose` 提供 Compose API —— 若要更顺的 Compose 写法可评估，
  但**生产建议用稳定版**）
- Keystore / AES-GCM 是平台 API，**不需要额外依赖**
- ⚠️ **不要引入 `androidx.security:security-crypto`**（`EncryptedSharedPreferences`），
  直接手写 Keystore + AES-GCM 更可控，也避免多一个依赖

**其他**：

- 密码字符数组用完后**清零**（`CharArray.fill('\0')`），减少内存中明文停留时间。
- **日志中绝对不得打印学号或密码**。

### 5.3 ScnuAuthManager —— 合并两套登录

- 把 `ScnuScraper.login()` 与 `ScnuSsoLogin.login()` **合并为一处**，
  实现采用 `ScnuSsoLogin` 的**双路径**（auth.html 主路径 + fastlogin 兜底），
  因为它已被验证更完整。
- ⚠️ **迁移风险**：`ScnuScraper` 仍被 `CampusSyncViewModel` 使用，
  `ScnuScraper` 的爬取方法（`fetchCourses` / `fetchGrades` / `fetchExams` 等）**必须保留**，
  只把 `login()` 迁走。**不要删除任何爬取方法。**
- 对外接口建议：
  - `suspend fun login(account, password): Result<Unit>` —— 显式登录并建立会话
  - `suspend fun ensureSession(): Boolean` —— 有会话直接返回；无会话则从 CredentialStore 取凭据自动登录
  - `fun logout()` —— 清 CookieJar + 清内存会话（是否清持久化凭据由调用方决定）
- 两个模块（同步 / 选课）**共用同一个会话**：它们本来就共用 `@Named("scnu")` 的
  OkHttpClient 与 `ScnuCookieJar`，因此**登录一次两边都可用**——这正是本次要利用的点。

### 5.4 AuthStateRepository

- 用 `StateFlow` 暴露状态，**细粒度**（性能红线 5）：
  `isLoggedIn` / `hasStoredCredential` / `biometricAvailable` / `lockRemainingSeconds` 等分开，
  不要塞成一个巨型 `UiState`。
- 所有 `map` / `filter` 等手写变换要 `.flowOn(Dispatchers.Default)`（性能红线 2）。

### 5.5 账号页（`Destination.Account`）

必须包含：

| 内容 | 说明 |
|---|---|
| 登录状态 | 已登录 / 未登录 / 凭据失效 / 已锁定 |
| 学号 | 可显示（**脱敏显示**，如 `2024****41`）；密码**永不显示** |
| 登录 / 重新登录 | 输入学号 + 密码 |
| 退出登录 | 清内存会话；**提供是否同时清除已保存凭据的选项** |
| **风险告知** | 明说"root / 定制 ROM / 备份提取仍可能拿到密文" |
| **一键清除凭据** | 清除 Keystore 密钥 + DataStore 密文 + 内存会话 |
| 生物识别不可用时的说明 | 见 5.2 的降级说明 |

- 在 `SettingsScreen.kt` 约 340–380 行的条目区新增入口。
- **不要改动【我的】页其他条目的视觉与顺序**（性能红线 7）。

### 5.6 现有入口改造

| 文件 | 改法 |
|---|---|
| `SyncSelectionScreen.kt` | 移除手输 `account` / `password`；进页面时 `ensureSession()`；无凭据/失效 → 引导去账号页 |
| `CampusSyncViewModel.kt` | `startSync()` 不再接收明文密码，改为内部经 `ScnuAuthManager`；**移除**写 `KEY_CAMPUS_ACCOUNT` 的代码 |
| `CourseSelectionViewModel.kt` | `login` / `loginFromCampusDialog` / `reLogin` 改为走 `ScnuAuthManager`；**移除**重复写 `KEY_CAMPUS_ACCOUNT` |
| `CourseSelectionDialogs.kt` | 登录对话框优先走已保存凭据；仅无凭据时才展示输入框 |
| `CampusScreen.kt` | 选课卡片点击逻辑**保持不变**（已有 `hasActiveSession()` 判断），只在无凭据时改为引导账号页 |

**`KEY_CAMPUS_ACCOUNT` 迁移**：收敛到 `CredentialStore` 一处管理。
⚠️ 若改动键名或存储形态，**必须考虑已有用户的 DataStore 数据兼容**
（老用户已有 `campus_account`，不要让他们重新输学号）。

### 5.7 凭据失效与失败锁定（方案文档 §4.4）

- **密码会变**：华师可能强制定期改密 → 必须做"凭据失效 → 引导重新输入"的完整流程。
- **失败锁定**：SSO 连续失败**会锁账号** → **禁止无脑重试**。
  连续失败 N 次后进入冷却期，UI 显示剩余时间。
- **凭据总有效期 30 天**（已定决策）：超过 30 天要求重新输入密码。
  **不是**"每次 App 更新重登"。

---

## 6. 已核实的坑（避免踩）

1. **`BiometricPrompt` 需要 `FragmentActivity`** —— 本项目 `MainActivity` 已是
   `AppCompatActivity`（继承链满足），**无需改基类**。
2. **`CryptoObject` 只能配生物识别，不能配设备密码** —— 未录入生物识别时必须降级。
3. **`setUserAuthenticationRequired(true)` 会让后台静默刷新失败** ——
   所以采用"会话缓存 15 分钟"的折中，不要试图做无人值守自动重登。
4. **`ScnuScraper` 与 `ScnuSsoLogin` 共用同一个 CookieJar** ——
   登录态是**共享**的，这也是选课模块退出时要清 CookieJar 的原因。改造时不要破坏这个前提。
5. **`ScnuSsoLogin` 的 `CLIENT_ID` 与 fastlogin 的 `app_id` 是两套不同的授权标识，不可互换**。
6. **编译命令别漏空格**：`./gradlew :app:compileDevDebugKotlin`。

---

## 7. 性能红线（必须遵守，否则会回退已有优化）

1. 状态收集**只用** `collectAsStateWithLifecycle()`，**禁止** `collectAsState()`
2. **主线程零重活**：加解密、DataStore 读写、网络全部在 `Dispatchers.IO`；
   手写的 `map` / `filter` / `combine` 要 `.flowOn(Dispatchers.Default)`
3. **不要在 Composable 函数体里** new `DateTimeFormatter.ofPattern(...)`、
   调 `LocalDate.now()` / `LocalTime.now()` —— 用 `remember {}` 或顶层常量
4. 列表用 `LazyColumn` / `LazyRow`；**禁止在 LazyColumn 里嵌套同方向 Lazy 组件**
5. **状态要细粒度**：按模块拆 `StateFlow`，避免巨型 State 引发整页重组
   （参考 `ExamViewModel` 暴露 4 个独立 `StateFlow` 的写法）
6. 每个功能拆独立 `@Composable`；含 `List` 字段的 data class 标 `@Immutable`
7. **不要改动已有模块的 UI 视觉呈现**，除非明确要求

---

## 8. 交付要求

1. **编译验证**：`./gradlew :app:compileDevDebugKotlin` 必须通过；
   提交前再跑一次 `./gradlew assembleProdRelease`。
   **贴出真实命令与真实输出**，不要说"应该能跑"。
2. 给出：
   - **改动文件清单**（新增 / 修改分开列）
   - **关键实现说明**（尤其：Keystore 方案、生物识别降级逻辑、两套登录如何合并）
   - **是否符合性能红线**（逐条对照 §7）
   - **遗留问题与未验证项**（诚实列出，例如"未在真机验证指纹流程"）
3. 需要新页面时：在 `Navigation.kt` 加 `Destination`，并在
   `MainActivity.kt` 的 `ScreenContent` 注册。
4. 需要新文案时：同步 **4 份** `strings.xml`。

---

## 9. 工作方式

- 用**中文**回复
- **动手前先 read / grep / glob 核实**，不要凭猜测改代码
- 遇到不确定的点（密钥参数、降级策略、UI 文案、是否新建页面等）**先问，不要擅自假设**
- 本次涉及**安全**，任何"为了简单而降低安全性"的取舍都要**先提出、等我确认**
