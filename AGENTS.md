# AGENTS.md

给 AI 编码助手（Claude Code、Cursor、Copilot 等）的项目说明。人类成员请先读 `docs/`。

## 项目

“爱教学”教学质量全链路数字化管理平台，Sprint 1（10/2—10/6）目标：人工跑通“督导安排 → 课堂督导 → 督导评价 → 教师反馈 → 基础改进”主链，不含任何 AI 分析功能。

- 原生 Android，Kotlin + Jetpack Compose + Material 3，单 Activity。
- 数据只存本地 Room，无后端，无网络请求。三个角色在同一台手机上切换账号。
- 包名 `edu.neu.aijiaoxue`，minSdk 29，targetSdk / compileSdk 37。

## 必读文档

| 文档 | 用途 |
|---|---|
| `docs/开发规范.md` | 命名、枚举、时间格式、文件路径、路由、Git 规范。写代码前必读 |
| `docs/Sprint1需求说明.docx` | 每条用户故事的主流程、业务规则、异常处理和验收标准 |
| `docs/Sprint1分工与计划.md` | 模块分工与包结构 |

实现某条用户故事前，先在需求说明里找到对应的 US 编号，按其中的“业务规则”和“异常处理”实现，不要自行发挥。

## 构建与测试

```bash
./gradlew :app:assembleDebug        # 编译
./gradlew :app:testDebugUnitTest    # 单元测试
```

Windows 用户名含中文时，需设置 `GRADLE_USER_HOME=D:\Android\.gradle`，否则单元测试进程会报 `ClassNotFoundException: GradleWorkerMain`。JDK 使用 Android Studio 自带的 JBR。

改完代码必须能通过以上两条命令。

## 代码结构

```
app/src/main/java/edu/neu/aijiaoxue/
├── data/
│   ├── model/Enums.kt      全部业务枚举（唯一来源）
│   ├── model/Rules.kt      总分、等级、任务编号、逾期、日期格式
│   ├── entity/Entities.kt  Room 实体
│   ├── dao/Daos.kt         DAO
│   ├── AppDatabase.kt      AppDatabase.get(context)
│   ├── FileStore.kt        音频/图片文件路径
│   └── PasswordHasher.kt
├── navigation/Routes.kt    全部路由
└── ui/<模块>/              各成员的页面，见分工文档
```

## 硬性规则

1. 状态、类型、角色只用 `data/model/Enums.kt` 里的枚举，不要写字符串或数字常量，也不要新建同义枚举。
2. 总分、等级、任务编号、逾期判断只调用 `Rules.kt`，不要在页面里重算。
3. “已逾期”是计算结果，不是状态值，不要写进数据库。
4. 任务状态只在规范第 4 节列出的操作里改变，不提供手动修改。
5. 模块之间只传 `taskId` / `courseId`，路由用 `Routes` 里的函数生成。
6. 权限校验放在数据查询层：按当前用户 ID 查询，不要查全部再在界面过滤，也不能只靠隐藏入口。教师只能看本人课程的已提交评价。
7. 采集数据（材料、事件、参与状态、评价草稿）产生时立即写库，不依赖“保存”按钮（US07、NF-03）。
8. 文件路径用 `FileStore` 生成，`Material.filePath` 存相对路径；删除材料先删文件再删记录。
9. 密码只存 `PasswordHasher.hash()` 结果，不得明文存储或打印到日志。
10. 不要引入网络库、后端调用、AI SDK 或新的第三方依赖。确需新依赖时，加到 `gradle/libs.versions.toml` 并固定版本，同时说明理由。
11. 修改实体后把 `AppDatabase.version` 加 1，并一并提交 `app/schemas/` 下生成的 JSON。

## 代码风格

- 遵循 Kotlin 官方风格（`kotlin.code.style=official`），4 空格缩进。
- 页面 `XxxScreen`，ViewModel `XxxViewModel`，状态 `XxxUiState`；一个页面一个文件。
- ViewModel 通过 `StateFlow` 暴露状态，页面用 `collectAsState()` 读取；数据库操作放在 `viewModelScope` 协程里。
- 界面文字用中文；代码标识符用英文，不用拼音。
- 注释写“为什么”，并在相关代码处标注 US 编号，如 `// US12：5 秒内可撤销`。
- 公共文件（`data/`、`navigation/`）只做追加，不改已有方法签名；需要改时先告知用户。

## 提交

- 分支 `feature/<缩写>-<模块>`，提交信息 `<类型>(<模块>): <中文说明>`，详见规范第 8 节。
- 不要直接推送 `main`，不要 force push。
- 不要提交 `local.properties`、`build/`、`.gradle/`、`.idea/` 下的个人配置。
- AI 产出的代码须经人工审查（DoD 要求），提交前请让成员确认。

## Sprint 1 不做

AI 转写与分析、时间轴关联（US10）、教学环节切分（US14）、自动报告、复评、注册与找回密码、教务系统对接、学生端。遇到相关需求，只预留字段，不实现。
