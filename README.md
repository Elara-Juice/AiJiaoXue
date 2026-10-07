# 爱教学 · AiJiaoXue

软件项目管理课程实验项目，在实验一规划的基础上完成 Sprint 1：以人工方式跑通“督导安排 → 课堂采集 → 督导评价 → 教师反馈 → 基础改进”。

项目采用 Kotlin、Jetpack Compose、Material 3 和 Room，支持 Android 10 及以上。数据保存在设备本地，三个角色在同一设备切换账号演示；本轮不接入后端或AI分析。

## 项目文档

| 文档 | 内容 |
|---|---|
| [项目规划方案](docs/项目规划方案.docx) | 实验一的项目背景、总体规划和阶段安排 |
| [Sprint1需求说明](docs/Sprint1需求说明.docx) | 用户故事、业务规则与验收标准 |
| [Sprint1分工与计划](docs/Sprint1分工与计划.md) | 团队分工、开发安排和联调事项 |
| [开发环境搭建指南](docs/开发环境搭建指南.md) | 必要下载、启动、构建、测试和演示账号 |
| [开发规范](docs/开发规范.md) | 数据、权限、命名、路由与Git约定 |
| [Sprint1验收说明](docs/Sprint1验收说明.md) | 当前分支完成情况、测试结果和剩余事项 |

## 快速开始

配置 Android Studio、SDK 和JBR后，在项目根目录执行：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK输出位置为 `app/build/outputs/apk/debug/app-debug.apk`。本机已有环境可直接使用[环境指南](docs/开发环境搭建指南.md)中的脚本。

演示账号：`admin`（管理员）、`sup01`（督导）、`tea01`（教师），初始密码均为 `123456`。

## 当前状态

截至2026-10-07，`yzj-sprint1` 分支已实现于卓君负责的 US29、US30、US32、US36、US41、US42。16项JVM测试和44项模拟器测试通过；队友模块尚未合并，完整业务主链及真机验收待完成。用户已授权提交推送，版本以本分支Git历史为准；独立人工审查记录仍待补充。

源码位于 `app/src/main/java/edu/neu/aijiaoxue/`，测试位于 `app/src/test/` 和 `app/src/androidTest/`。
