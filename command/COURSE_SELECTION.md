# 自主选课模块 · 设计决策与领域模型（v1.5.0）

> 本文档记录 v1.5.0「选课」模块的设计决策、领域术语与后续开发注意事项。
> 面向**下一轮开发的助手或人类**：读完可快速上手，且知道哪些地方**不能乱改**。
>
> 分支：`feature/v1.5.0`（从 `main` = v1.4.1 切出）
> 需求来源：用户已验证的 Python 脚本 `scnu_course_selector.py`

---

## 0. 分支与发版规约（先读这条）

**功能分支绝对不要改版本号和 `update.json`，只写功能代码。**
详见 `command/RELEASE_PROMPT.md`。版本号与 `update.json` 只在发版时由
`command/release.ps1` 改一次。

本分支已严格遵守：`app/build.gradle.kts` 未被改动（仍为 `versionCode 10` /
`versionName 1.4.1`）。发版时执行：

```powershell
.\command\release.ps1 -Version 1.5.0 -Changelog "..." -GiteePat "<PAT>"
```

---

## 1. 领域术语（Ubiquitous Language）

与教务系统（正方）保持一致的叫法，**代码与文档统一使用左侧术语**：

| 术语 | 教务字段 | 含义 |
|---|---|---|
| 课程 | `kch_id` / `kch` / `kcmc` | 一门课。`kch_id` 是内部 ID，`kch` 是人类可读课程号 |
| 教学班 | `jxb_id` / `jxbmc` | 一门课的具体开班。同一课程可有多个教学班（不同教师/时间） |
| **提交 ID** | `do_jxb_id` | **选课真正需要的 ID**。与列表返回的 `jxb_id` **不是同一个值**，缺它无法选课 |
| 子课程 | `xsmc` / `jxbzls` | 教学班的组成部分（理论/实验/上机…）。`jxbzls > 1` 表示含子课程 |
| 类别 | `kklxdm` | 课程类别代号：`01`=主修、`10`=通识选修、`41`=第二类 |
| 轮次 | `xklc` / `xkkz_id` | 选课轮次。页面只显示"第5轮"文案，接口要纯数字，故需抽数字 |
| 选课窗口 | `iskxk` / `xnm` / `xqm` | 是否在选课时间内、学年、学期 |
| 已选课程 | `ChoosedDisplay` | **权威**已选清单，退选 ID 的唯一可靠来源 |
| 批次 | `kspage`/`jspage`/`kcrow` | 客户端窗口分页：一次要 10 条，用 `kcrow` 切出属于本窗口的行 |

### 易混淆点（务必记住）

- **`jxb_id` ≠ `do_jxb_id`**：列表接口给 `jxb_id`，选课提交要用 `do_jxb_id`。
- **课表 ≠ 已选清单**：课表只返回**有上课时间**的课程。课程设计、实训、劳动
  教育等无排课课程不会出现。**判断"是否已选"永远用 `ChoosedDisplay`。**
- **`xkxnm` vs `xnm`**：选课接口用 `xkxnm`/`xkxqm`，课表接口用 `xnm`/`xqm`，
  语义相同但参数名不同。

---

## 2. 架构与文件职责

```
data/network/selection/
├── CourseSelectionModels.kt   # DTO + 领域派生属性 + parseTeacherNames()
├── ScnuSsoLogin.kt            # SSO 双路径登录（auth.html 优先 + fastlogin 兜底）
└── ScnuCourseSelector.kt      # 7 个选课接口 + 上下文抓取 + 窗口分页

ui/campus/
├── CourseSelectionViewModel.kt  # 状态编排、并发控制、超时裁决
├── CourseSelectionScreen.kt     # 登录卡 / 轮次条 / 类别 Tab / 两个列表
└── CourseSelectionDialogs.kt    # 教学班面板 / 子课程勾选 / 退选确认
```

**接入点**：`CampusScreen.kt` 的 `TertiaryServiceGrid`（2×2 第三行）
→ `Destination.CourseSelection` → `MainActivity.ScreenContent`

### 为什么 `ScnuCourseSelector` 是有状态的、且非单例

选课接口的**全部精准度依赖从选课首页抓来的 hidden 上下文**（见下节）。
若做成单例，多个使用方会互相覆盖上下文。故本类通过 `@Inject` 构造函数 +
Hilt **隐式绑定**注入 → 默认 unscoped → 每次注入都是新实例。

> ⚠️ **不要给它加 `@Singleton`**，会立即引入跨页面上下文污染。

底层 `@Named("scnu")` 的 `OkHttpClient` / `CookieJar` 仍是单例，
所以登录会话在「教务同步」和「选课」之间共享 —— 这正是"退出选课要清 CookieJar"
的原因。

---

## 3. 关键机制（改代码前必读）

### 3.1 动态上下文抓取（不要改成硬编码）

`ScnuCourseSelector.refreshContext()` 抓选课首页 HTML，用正则收集**全部**
`<input type="hidden">`，再按 `CTX_KEYS`/`LIST_KEYS`/`CLASS_KEYS` 三张白名单
组装请求参数。

**为什么不能硬编码**：缺失这些规则参数时，教务**不报错**，而是**静默忽略
专业/年级筛选**，把全校课程返回 —— 这是"查到别的专业的课"的根因。
`DEFAULT_RULE_FLAGS` 里那 24 个 `'0'` 同样是必需的。

### 3.2 客户端窗口分页（`kcrow` 切分）

教务不是标准服务端分页：

```
窗口 ks..js  = (batch-1)*10+1 .. batch*10
请求后服务器可能推送【超出该窗口】的行
→ 必须用 kcrow（rankInBatch）过滤出 ks..js 的行
→ 本批行数 < 10 即视为到底
```

去重键为 `jxb_id or kch_id`（滑动窗口**确实会重复推送**同一教学班）。

### 3.3 子课程选课（不可省略）

`jxbzls > 1` 的教学班由多个子课程组成。此时：

1. 先调 `subCourses()` 拿子课程列表
2. 用户勾选
3. 把多个 `do_jxb_id` **用逗号拼成 `jxb_ids`** 一起提交

**只提交单个 `do_jxb_id` 会导致选课失败，或更糟：只选上理论课、实验课没选上**，
用户以为选完了，实际课时不完整。UI 上这类教学班按钮文案为「选子课程」，
并在列表标注「含子课程」。

### 3.4 登录：双路径 + 真实会话校验

`ScnuSsoLogin.login()` 实现两条授权路径（对应脚本）：

```
主路径：  GET /openapi/auth.html?client_id=9347e8e3...&response_type=code
          └ 响应含 gotoApp 时，再从 JS 里抠 var url 跳转
兜底路径：GET /openapi/fastlogin.html?app_id=96
```

`client_id` 与 `app_id=96` 是**两套不同的授权标识**，不可互换。

登录后**必须**调用 `refreshContext()`：抓不到 hidden 字段即判定会话无效
（对应脚本 `_refresh_context()`）。这是本模块唯一的真实会话校验 ——
**现有 `ScnuScraper` 缺少这一步**，导致 Cookie 静默失效时会把登录页 HTML
丢给 JSON 解析，报出误导性的"JSON 解析失败"。

---

## 4. 八条设计决策（与需求方逐条确认过）

| # | 决策 | 理由 |
|---|---|---|
| 1 | 模块独立，进入显示登录卡；**账号预填、密码必须重输** | 复用 `campus_account`；密码绝不落盘 |
| 2 | **退出时清空课程数据 + 清 CookieJar** | 不清会话会导致"跳过登录"，与决策 1 矛盾 |
| 3 | 按类别**懒加载 + 分页续拉**，滑到底自动续 | 全量拉可能 30s+；主修规模小，首批 10~20 条即时可见 |
| 4 | **只读查询 + 选课 + 子课程勾选 + 退选**（全做） | 脚本已验证；缺退选则无法取代脚本 |
| 5 | 退选必须**二次确认**，展示完整教学班标识 | 同名课程可能有多个教学班，只显示课程名等于盲选 |
| 6 | 卡片放**新增 2×2 第三行**，选课占左格 | 塞进三卡行会把每张压到约 72dp，5 字标题被截断 |
| 7 | 配色**跟随 MaterialTheme + 深色模式** | 与同模块 `SyncSelectionScreen` 一致；`values-night` 是支持目标 |
| 8 | 默认落在**主修 `01`**；会话中途失效**保留已加载列表** | 用户主要抢主修；已翻 20 批数据不应因过期被清空 |

### 明确「不做」的事

- ❌ 选课结果写回 Room（模块是**临时沙盒**，退出即清）
- ❌ 退选后自动触发 `syncCourses`（会把风险外溢到已发布功能）
- ❌ 本地乐观删除已选课程（后端可能没退成功，界面会骗用户）
- ❌ 改动 `ScnuScraper` / `CampusSyncViewModel` / 成绩 / 考试 / 校园页其他卡片
- ❌ 「记住上次 Tab」、自动重试

---

## 5. 高并发规则（抢课期间的生命线）

### 5.1 超时 ≠ 失败

网络超时/结果不明时，**绝不盲目重试**，而是查权威接口 `my_enrolled()`：

| 权威查询结果 | 提示 |
|---|---|
| 已包含该课 | "已选上（上次请求实际已成功）" |
| 未包含 | "未收到教务结果，尚未选上。可重新尝试" |
| 查询本身失败 | "结果未知，请稍后手动确认" |

### 5.2 业务码语义（两个字典不可混用）

**选课 `_FLAG_MSG`**：

| flag | 含义 | 处理 |
|---|---|---|
| `1` / `3` | 成功 | 成功 |
| `6` | 重复选课 | **不算失败**，提示"已选中" |
| `-1` | 名额已满 | 单独文案"名额已满"（高并发最常见失败） |
| `0` | 非法访问/会话失效 | 引导重新登录 |

**退选 `_DROP_MSG`**：`2`/`3`/`4`/`5` → **原文透传**，不要泛化成"失败"。

### 5.3 提交加锁（有意偏离脚本的一处）

脚本是同步阻塞 CLI，不存在连点问题。App 里必须加锁，否则：

> 连点两次 → 第二次可能返回 `flag='5'` 校验不通过 → 用户以为选课失败，
> **实际第一次已经成功**。

实现：`inFlightSubmissions` / `inFlightDrops` 集合 + UI 按钮 `enabled = !isSubmitting`。

### 5.4 退选成功后**不做**本地乐观删除

重新拉取 `my_enrolled()` 权威清单。否则一旦后端实际未退成功，界面已骗了用户。

---

## 6. 性能红线落实位置

| 红线 | 落实点 |
|---|---|
| 只用 `collectAsStateWithLifecycle()` | 全部状态收集 |
| 主线程零重活 | 所有请求在 `Dispatchers.IO`；过滤/排序显式 `.map { withContext(Dispatchers.Default) }` |
| 不在 Composable 里 new 格式化器 | `WelcomeCard` 已用 `remember`；`TextStyle`/`Locale` 提为顶层常量 |
| 列表用 Lazy；不嵌套同方向 | 外层 `LazyColumn`；类别 Tab 用 `LazyRow`（横向，方向不同） |
| 状态细粒度 | 按类别拆 `StateFlow`；`LoadedCourses` 按类别缓存，切 Tab 不触发全量重组 |
| 每个功能独立 Composable | `LoginPane` / `RoundInfoBar` / `CategoryTabs` / `CourseRow` / `EnrolledCourseRow` 等 |
| 不改现有模块视觉 | `SecondaryServiceGrid` 三张卡零改动；`SmallServiceCard` 仅新增可选 `onClick` |

---

## 7. 遗留事项与技术债

1. **登录实现重复**：`ScnuSsoLogin`（双路径）与 `ScnuScraper.login()`（仅 fastlogin）
   暂时并存。**v1.6.0 目标**：评估统一到 `ScnuSsoLogin`。
   在迁移前**不要删除任何一侧** —— `ScnuScraper` 仍被 `CampusSyncViewModel` 使用，
   删除会导致教务同步整体失效。
2. **`auth.html` 路径未在真机验证**：移植自脚本，但本次开发环境无法连通校园网
   实测。若登录异常，优先怀疑此处（可临时只走 fastlogin 兜底路径排障）。
3. **选课/退选写操作未端到端实测**：逻辑严格对齐已验证脚本，但 App 侧的网络栈
   （OkHttp + trust-all）与 Python `urllib` 存在差异。**建议在选课窗口内实测一次**。
4. **分页限速**：脚本用 `time.sleep(0.2)` 批次间限速。当前实现**未加**此节流
   （依赖用户滚动触发，天然稀疏）。若发现教务限流，在 `listCoursesBatch` 加
   `delay(200)`。
5. **`CourseSelectionUiState.infoMessage` 字段当前未使用**，预留给"退出清空"类提示。
6. **`CampusViewModel.onSyncStart()` 是死代码**（`CampusScreen` 已直接进
   `Destination.SyncSelection`），且 `SyncSelectionScreen` 的 KDoc 仍引用它。
   建议后续清理，本次未动以控制回归面。

---

## 8. 排障速查

| 现象 | 优先检查 |
|---|---|
| 查到别的专业的课 | 上下文未抓到 / `DEFAULT_RULE_FLAGS` 被改 |
| 选课返回 `flag='0'` | 会话失效（`refreshContext` 能否抓到 hidden） |
| 选课返回 `flag='-1'` | 名额已满，正常业务结果 |
| 报"JSON 解析失败" | 会话已失效但未识别（应报"会话可能无效"） |
| 只选上部分子课程 | 未走子课程勾选流程（`jxbzls > 1` 判断） |
| 退选报 `'4'` | 参数校验敏感，检查 `kch_id`/`jxb_id` 是否传错 |
| 编译报 "Unclosed comment" | KDoc 里写了 `/*` 序列（Kotlin 块注释**支持嵌套**） |

### 接口契约属于逆向所得

URL、`app_id=96`、`client_id`、`gnmkdm=N253512` 编号等常量均来自抓包/逆向，
**教务升级即可能失效**。这些常量已集中定义在各文件的 `companion object` 中，
不要散落到调用处。
