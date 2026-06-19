<<<<<<< HEAD
# 拾光课程表
![GitHub Downloads (all assets, all releases)](https://img.shields.io/github/downloads/XingHeYuZhuan/SCNU-Schedule/total)  ![GitHub License](https://img.shields.io/github/license/XingHeYuZhuan/SCNU-Schedule)  ![GitHub Repo stars](https://img.shields.io/github/stars/XingHeYuZhuan/SCNU-Schedule)
#### 与我们交流和讨论 ![](https://img.shields.io/badge/QQ_频道-pd68794181-blue)

本仓库为拾光课程表（SCNU-Schedule）项目的主仓库，包含软件的全部源代码及相关资源。项目采用开源模式，欢迎社区开发者参与贡献和适配。  

## 预览图

![预览图1](/picture/Preview_1.png "预览图")

## 项目定位

拾光课程表是一款面向中国高校师生的课程表管理工具，支持通过适配脚本导入各类教务系统课程数据，方便用户高效管理个人课表。项目注重开放性和可扩展性，鼓励社区开发者参与适配和功能完善。

## 功能介绍

### 主页面
- 今日课表
  > 顾名思义就是看当天的课程
- 课表
  > 显示一周的课程，左右滑动可以切换周次，点击顶部周次标题会弹出底部选择器用于快速跳转周次(会标记当前日期所在周)
- 我的
  > 也就是设置页面，这里放置所有的配置项

#### 深色适配
- 目前软件所有可以实现深色适配的位置都已全部实现深色适配
- 小组件也拥有深色适配

#### 课表配置
- 支持时间列表与课表绑定,切换不同的课表自动改变时间
- 课表支持独立定义，一周的起始日是周一还是周日
  
#### 小组件  
- 多种功能小组件任其选择  
  > [全部小组件预览图](/picture/all_widget.png)。
- 小组件支持明日预告
  > 超小小组件不支持明日预告,因为只有2x1的尺寸,设计上是用来展示最近一节课的消息  

#### 全局课程管理  
- 独立的课程管理页面将所有课程以最直接的方式全局显示出来，支持快速添加以及修改  

#### 课表页面个性化配置  
- 支持自定义背景图片  
- 支持调整课表格子高度、圆角、间距及透明度  
- 支持自定义课程块颜色  
- 支持调整课程块内容样式  

#### 课程导入与导出  
- json文件导入与导出，方便课表备份  
- 通用ics文件导出，支持多种设备以及平台的日程导入 
- 教务导入，开源适配仓库开发者深度适配学校作息时间，一键导入  
  > 识别课程与对应作息时间是适配的基础功能，更高级的自动配置开学日期等 与开发者是否适配学校可能拥有的接口有关  

#### 课程提醒相关  
- 课程提醒与上课自动化开启勿扰或者静音  
- 获取整年节假日数据防止节假日课程打扰  
  
#### 语言支持  
- 简体中文 
- 繁体中文 
- 英语  

## 关于项目版本

目前软件支持Android 8.0 +的Android版本  

项目分为 **开发版（`dev`）** 和 **正式版（`prod`）** 两个版本。

主要特性和区别如下：

1.  **安全性：正式版**开启了**基准灯塔标签验证**，确保用户导入的适配脚本是安全可靠的。
2.  **仓库可见性：正式版**默认**隐藏了自定义/私有仓库**，防止普通用户误用未经官方验证的脚本，提供了更高的安全性。
3.  **版本标识：开发版**使用 `.dev` 后缀，允许其与正式版共存，便于开发者进行测试。
4.  **调试工具：** **正式版**会禁用 **DevTools** 选项，**防止普通用户误触启用调试功能，从而避免潜在的信息泄露或配置被意外修改的风险**。

**重要提示：**

**正式版 (`prod`)** 默认**开启了安全验证**并**隐藏了自定义仓库**，为普通用户提供了更严格的安全保障。**强烈推荐普通用户使用正式版。**  

正式版图标是**蓝色**背景 开发者版图标是**红色**背景 注意不要搞混了

-----

## 如何参与

1. Fork 本仓库，提交你的改进或教务适配使用的可调用组件。
2. 提交 Pull Request，等待审核合并(main分支已经开启分支保护,提交需要提交到dev分支)。
3. 如有问题或建议，欢迎在 GitHub 提交 Issue 或加入社区讨论。

## 相关链接

- 项目主页：[https://github.com/TangeTiv/SCNU-Schedule](https://github.com/TangeTiv/SCNU-Schedule)
- 适配脚本仓库：[https://github.com/XingHeYuZhuan/shiguang_warehouse](https://github.com/XingHeYuZhuan/shiguang_warehouse)
- 查看如何适配,Wiki：[https://github.com/TangeTiv/SCNU-Schedule/wiki](https://github.com/TangeTiv/SCNU-Schedule/wiki)
- 浏览器测试插件:[https://github.com/XingHeYuZhuan/shiguang_Tester](https://github.com/XingHeYuZhuan/shiguang_Tester)  

- ###### 由[@Jursin](https://github.com/Jursin)主导并维护的网站:[https://sgschedule.jursin.top/](https://sgschedule.jursin.top/)  
---

如有问题或建议，欢迎提交 Issue 或 PR。

## 贡献  
欢迎任何人提交你的贡献  
### 教务适配贡献  
<a href="https://github.com/XingHeYuZhuan/shiguang_warehouse/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=XingHeYuZhuan/shiguang_warehouse" />
</a>

### 软件开发贡献  
<a href="https://github.com/XingHeYuZhuan/SCNU-Schedule/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=XingHeYuZhuan/SCNU-Schedule" />
</a>
=======
# shiguang_warehouse

本仓库用于 shiguangschedule 适配脚本的管理和测试。所有适配脚本将集中存放于此，方便软件拉取和测试。

**注意为避免代码出现问题，main分支启用分支保护，需要先合并到pending分支等待分支同步**

## 仓库结构说明


## 资源目录结构

每个学校或工具都有一个独立的目录，包含以下文件：
## root_index.yaml 填写与适配流程

所有适配学校/工具必须先在 `index/root_index.yaml` 文件中登记，CI/CD 构建脚本会根据此文件决定处理哪些资源。

### 字段说明

每个学校/工具条目需包含如下字段：

| 字段名           | 类型    | 说明                       |
| --------------- | ------- | -------------------------- |
| id              | String  | 唯一标识（拼音或缩写,如果可以更建议使用域名）<br>一般来说我们建议教务使用全大写 工具使用小写 |
| name            | String  | 中文名称                   |
| initial         | String  | 名称首字母（用于排序）     |
| resource_folder | String  | 资源文件夹名称 （建议和id保持一致）|

示例：
```yaml
schools: #固定字段
  - id: "GLOBAL_TOOLS"
    name: "通用工具与服务"
    initial: "G"
    resource_folder: "GLOBAL_TOOLS"

  - id: "CUST"
    name: "长春理工大学"
    initial: "C"
    resource_folder: "CUST"
```

### 适配注意

1. 若要适配新学校/工具，**必须先在 `root_index.yaml` 添加条目**，填写上述字段。
2. 在 `resources/` 下创建与 `resource_folder` 字段一致的文件夹。
3. 在该文件夹内添加 `adapters.yaml` 和适配脚本。
4. 只有在 `root_index.yaml` 已登记的学校/工具，才能提交适配文件。
5. 新增学校/工具时，请确保在适配 PR 中已将相关条目添加到 `root_index.yaml` 的学校列表，无需单独提交更新 PR。

```
资源目录名/
  ├── adapters.yaml  # 配置信息
  └── xxx.js         # 适配脚本
```

## adapters.yaml 配置说明


每个适配器配置应包含以下字段（YAML格式，字段全部必填）：

| 字段名           | 类型    | 说明                                   |
| --------------- | ------- | -------------------------------------- |
| adapter_id      | String  | 唯一标识（建议用拼音或英文缩写）,个人建议使用学校id加序号的形式        |
| adapter_name    | String  | 中文名称                               |
| category        | String  | 分类：`BACHELOR_AND_ASSOCIATE`(本科/专科)、`POSTGRADUATE`(研究生)、`GENERAL_TOOL`(通用工具) |
| asset_js_path   | String  | 适配脚本的**相对路径**（如 `school.js`）         |
| import_url      | String  | 系统登录URL（教务系统适配器必填，工具可为空） |
| maintainer      | String  | 维护者信息（如姓名或 GitHub 用户名）    |
| description     | String  | 简要说明（如适配用途、备注等）          |


示例：
```yaml
adapters: #固定字段
  - adapter_id: "GENERAL_TOOL_01" # id加上序号
    adapter_name: "组件测试"
    category: "GENERAL_TOOL"
    asset_js_path: "school.js" #相对路径
    import_url: ""
    maintainer: "星河欲转"
    description: "这是一个空网站，用于组件测试与演示模式"
```

**注意：**  
- 请严格按照上述字段填写，不要添加或减少字段。
- `importUrl` 一定要是登录页面。
- `asset_js_path` 填写对应学校的适配脚本**相对路径**。
- `maintainer` 填写维护者信息，便于后续沟通和维护。

## 开发流程

1.**Fork 仓库**
- 所有开发者需先 fork 本仓库（本仓库带有 `lighthouse` 标签，已经在开发者软件版本关闭检查逻辑，希望各位开发者对使用的git仓库链接负责）。

2.**添加适配代码**
>在**不更新索引**的情况下，修改任何yaml文件是没有作用的（任何对于yaml文件的修改都需要编译索引才能应用），所以我们才提供了asset_js_path: "test.js" 占位用于在不更新索引的情况下测试适配代码

fork仓库之后 建议测试代码不要在自己的主分支测试哦,可以在仓库在开一个测试分支,测试完成可以一次将正确的代码提交到主分支,这样你的提交历史就不会充斥错误的提交历史  
**注意** 仓库更改数据结构,我们的索引需要编译,软件只接收编译过的索引文件,如果你要测试适配代码,我建议你在`resources\GLOBAL_TOOLS\test.js`文件放置适配代码,我们定义这个位置是一个适配占位符,开发者版本添加了网址链接输入,希望了解适配流程,**注意提交pr请不要把测试的test.js也发上去哦！！**  
```yaml
- adapter_id: "GENERAL_TOOL_02"
  adapter_name: "适配代码测试"
  category: "GENERAL_TOOL"
  asset_js_path: "test.js"
  import_url: ""
  maintainer: "星河欲转"
  description: "空网站以及不存在适配代码,用于在不更新索引的情况下给开发者进行适配的软件测试"    
```
#### 如果要更新索引也可以自行了解仓库的ci配置 (我不建议测试适配,还要更新索引)

3.**软件测试**

- 开发者需要安装 dev(开发者版，图标红色)版本app,在软件的“我的-更多-更新仓库”中选择**自定义仓库或私有仓库**，来拉取并更新自己的仓库代码进行实际测试，完成 Beta 阶段适配验证。

4.**提交 PR**

- 测试通过后，提交 Pull Request，等待审核合并。

## 社区公约

本项目基于 MIT 协议开源，致力于教务系统的适配与维护。为保障项目的良性流转，请在参与或使用本项目时遵守以下约定：

### 1. 贡献与署名
我们感谢所有贡献者的无私分享，每一份适配代码都值得被尊重。在引用或二次开发时，请保留原始开发者的贡献记录（如 Git 提交历史）。这既是对他人劳动的认可，也是社区协作的基础。任何抹除或篡改记录的行为，均不符合社区规范。

### 2. 分支与免责声明
*   **适用范围**：本项目主要维护工作集中于官方仓库及官方分支。
*   **非官方项目**：任何脱离官方管理的第三方分支或衍生项目，其所有行为均与本项目官方无关。
*   **责任界定**：若第三方项目存在代码滥用、违规适配或分发不当，由此产生的一切后果均由该项目维护者自行承担。**本项目及无辜的原始贡献者不承担任何连带责任**。

## 注意事项

- 请确保 `adapters.yaml` 信息准确完整，符合规范要求。
- 每次提交适配代码或索引信息后，建议自测通过再提交 PR。
- 仓库需保留 `lighthouse` 标签，否则软件无法识别为适配仓库。

## 更多链接  
**[如何适配](https://github.com/XingHeYuZhuan/shiguangschedule/wiki/%E5%A6%82%E4%BD%95%E9%80%82%E9%85%8D%E6%95%99%E5%8A%A1)**  

**[浏览器测试插件](https://github.com/XingHeYuZhuan/shiguang_Tester)**

---  

如有问题或建议，欢迎提交 Issue 或 PR。
>>>>>>> tange/main
