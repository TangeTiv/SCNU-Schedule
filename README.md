<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://img.shields.io/badge/SCNU_Schedule-1.0.0-73CAF8?style=for-the-badge&logo=appveyor">
  <img alt="SCNU Schedule" src="https://img.shields.io/badge/SCNU_Schedule-1.0.0-73CAF8?style=for-the-badge&logo=appveyor">
</picture>

# 🏫 华师课表 (SCNU-Schedule)

> 专为华南师范大学学子打造的现代化校园日程与教务管理工具 —— 课表同步、考试提醒、成绩分析，一站式搞定。

[![GitHub License](https://img.shields.io/github/license/TangeTiv/SCNU-Schedule)](LICENSE)
[![GitHub Releases](https://img.shields.io/github/v/release/TangeTiv/SCNU-Schedule)](https://github.com/TangeTiv/SCNU-Schedule/releases)
![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android)

---

## ✨ 核心定制功能

### 🚀 教务全量无缝同步

一键抓取学期课程与考试安排，基于正方教务系统深度适配。**预览确认机制**确保数据准确无误后再写入本地数据库，绝不污染本地数据。

### ⏱️ 考试倒计时管家

智能计算每门考试的剩余天数，已考科目自动置灰沉底，状态一目了然。告别考前突击，从容应对每一场考试。

### 📊 成绩分析系统

直观展示各学期绩点与学情变化趋势，支持按学年筛选和查看详细成绩构成，学业进度尽在掌握。

### 🗺️ 校园地图接入

原生唤起微信小程序地图，快速定位教学楼与校园设施，新生报到、跨校区上课不再迷路。

---

## 📖 预览

| 课表视图 | 今日视图 | 校园服务 |
|:---:|:---:|:---:|
| ![课表](picture/Preview_1.png) | ![今日](picture/Preview_1.png) | ![校园](picture/Preview_1.png) |

> 更多截图请查看 `picture/` 目录。

---

## ⚙️ 功能介绍

### 📅 课表管理

- **周课表 / 今日课表** — 左右滑动切换周次，顶部快速跳转
- **课程管理** — 全局课程列表，支持快速添加、编辑与删除
- **个性化样式** — 自定义背景图片、格子高度、圆角、间距、透明度
- **多课表绑定** — 支持多套时间表与课表独立绑定

### 🔄 数据导入与导出

- **教务导入** — 从适配仓库加载学校脚本，一键导入课表数据
- **JSON 备份** — 课表数据的完整导入与导出，方便迁移
- **ICS 日历导出** — 导出为通用日历格式，支持各平台日程导入

### 🔔 课程提醒

- 上课前智能提醒，支持自动化开启勿扰 / 静音
- 获取全年节假日数据，避免节假日课程打扰

### 🎨 个性化设置

- 深色模式（自动 / 浅色 / 深色）
- 多语言支持（简体中文 / 繁体中文 / English）
- 小组件（4 种尺寸样式，支持明日预告）
- 动态取色（Material You）

---

## 🧩 小组件

项目提供多种桌面小组件：

| 类型 | 尺寸 | 说明 |
| :--- | :---: | :--- |
| 超小 | 2×1 | 展示最近一节课信息 |
| 紧凑 | 4×1 | 紧凑排列当日课程 |
| 双日 | 4×2 | 展示今明两天课程 |
| 列表 | 4×3 | 完整列表视图 |

---

## ⚖️ 开源协议与致谢

### 致谢

本项目基于优秀的开源应用 **[拾光课表 (shiguangschedule)](https://github.com/XingHeYuZhuan/shiguangschedule)** 进行二次开发。衷心感谢原作者 **XingHeYuZhuan (星河欲转)** 为高校开发者提供的坚实开源底座与卓越架构。

### 版权

- 核心业务逻辑与华师专属功能代码归 **TangeTiv** 所有。
- 整体项目遵循 **Apache License 2.0** 开源协议。
- 原项目版权归 **XingHeYuZhuan (星河欲转)** 所有。

详情请参阅 [LICENSE](LICENSE) 文件。

---

## 🤝 参与贡献

欢迎通过以下方式参与项目：

- [提交 Issue](https://github.com/TangeTiv/SCNU-Schedule/issues) 反馈问题或建议
- [发起 Pull Request](https://github.com/TangeTiv/SCNU-Schedule/pulls) 提交代码改进
- 加入社区交流（QQ 频道：`pd68794181`）

---

<p align="center">
  <sub>Made with ❤️ for SCNU students</sub>
</p>
