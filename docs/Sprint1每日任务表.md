# Sprint 1 每日任务表（10/4 重排，10/4—10/7）

- 10/4 按实际进度重排：任务总量和分工不变，Sprint 1 结束时间从 10/6 晚上延到 10/7 晚上前。
- 每晚 21:00 开 15 分钟站会（孙浩然线上或群内文字汇报），汇报：今天完成了什么、明天做什么、遇到什么阻碍。
- “当天完成标志”未达成时，当晚站会由 DRI 决定是否启动降级（见 `Sprint1分工与计划.md` 第 5 节）。降级检查点改为 **10/5 21:00**。
- 计划变更：Sprint 1 延至 10/7，需在 Retrospective 记录。

## 文件约定

- 代码路径相对 `app/src/main/java/edu/neu/aijiaoxue/`，如 `ui/course/` 即 `.../edu/neu/aijiaoxue/ui/course/`。
- 一个类一个文件，文件名 = 类名 + `.kt`。每个页面两个文件：`XxxScreen.kt` 和 `XxxViewModel.kt`（`XxxUiState` 写在 ViewModel 文件里）。
- `Panel`、`Section`、`Editor`、`List` 结尾的是嵌入页面的组件，没有单独路由。
- 当前登录用户统一从 `data/Session.kt` 取：`Session.requireUser().id`，不要自己保存。
- 页面完成后，在 `navigation/AppNavHost.kt` 里把对应路由的 `pending(...)` 换成自己的 Screen；`taskId`、`courseId` 用 `entry.taskId()`、`entry.courseId()` 取。路由已在 `navigation/Routes.kt` 定义好。
- 文档放在 `docs/` 下。

## 已完成（10/2—10/4 上午）

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 安装环境、创建项目 ✅ | 工程 `Aijiaoxue/` |
| | 全部实体与 DAO ✅ | `data/entity/Entities.kt`、`data/dao/Daos.kt`、`data/dao/Rows.kt`、`data/model/Rules.kt`、`data/AppDatabase.kt` |
| | 写入演示数据 ✅ | `data/DemoData.kt` |
| | 包结构、导航、登录页、三角色首页 ✅ | `data/Session.kt`；`ui/login/LoginScreen.kt`、`LoginViewModel.kt`、`ProfileScreen.kt`；`navigation/AppNavHost.kt`、`AdminHomeScreen.kt`、`SupervisorHomeScreen.kt`、`TeacherHomeScreen.kt`、`HomeScaffold.kt`、`PendingScreen.kt` |
| 王博田 | 整理演示数据 ✅ | `docs/演示数据.md` |

## 10/4（周日）：推送骨架，核心功能开发 1

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 建 Git 仓库并邀请组员；提交并推送骨架到 `main`（**最先做，其他人从这里拉代码**） | 全部已完成文件 |
| | 采集页：录音开始 / 暂停 / 继续 / 结束（US08） | `ui/capture/CaptureScreen.kt`、`CaptureViewModel.kt`、`AudioRecorder.kt` |
| 王博田 | 安装开发环境；画页面草图（已完成的打 ✅） | 草图按本表 Screen 名命名 |
| | 课程检索（US01） | `ui/course/CourseSearchScreen.kt`、`CourseSearchViewModel.kt` |
| | 课程详情（US02） | `ui/course/CourseDetailScreen.kt`、`CourseDetailViewModel.kt` |
| | 创建督导任务（US04） | `ui/course/TaskCreateScreen.kt`、`TaskCreateViewModel.kt` |
| 于卓君 | 安装开发环境；读需求 US29/30/32/36/41/42；画页面草图，理清“问题条目 → 改进事项 → 改进措施”（已完成的打 ✅） | 草图按本表 Screen 名命名 |
| | 结构化评价表单与总分计算（US29） | `ui/evaluation/EvaluationScreen.kt`、`EvaluationViewModel.kt`、`ScoreSection.kt` |
| | 综合意见与问题条目（US30） | `ui/evaluation/IssueEditor.kt` |
| | 提交评价、状态改为“已完成” | `ui/evaluation/EvaluationViewModel.kt`（`submit()`） |
| 孙浩然 | 确认设备情况 | — |
| | 参与状态记录（US13） | `ui/engagement/EngagementPanel.kt`、`EngagementViewModel.kt` |
| | 开始写测试用例 | `docs/Sprint1测试用例.md` |

当天完成标志：仓库可拉取；督导能检索课程、创建任务、进入采集页并录音。

## 10/5（周一）：核心功能开发 2

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 采集页：拍照归集（US09） | `ui/capture/PhotoCapture.kt`；`AndroidManifest.xml`（FileProvider）、`res/xml/file_paths.xml` |
| | 互动事件一键标注、撤销、计数（US12） | `ui/capture/InteractionPanel.kt` |
| | 结束课堂采集、状态改为“待评价”；采集数据即时持久化 | `ui/capture/CaptureViewModel.kt`（`endClass()`） |
| 王博田 | 督导安排（US05） | `ui/arrange/ArrangementScreen.kt`、`ArrangementViewModel.kt` |
| | 任务进度跟踪（US06） | `ui/arrange/TaskProgressScreen.kt`、`TaskProgressViewModel.kt` |
| | 任务恢复（US07） | `ui/course/MyTasksScreen.kt`、`MyTasksViewModel.kt` |
| 于卓君 | 历史归档与检索（US32） | `ui/evaluation/HistoryScreen.kt`、`HistoryViewModel.kt`、`HistoryDetailScreen.kt`、`HistoryDetailViewModel.kt` |
| | 改进重点选择（US36） | `ui/improve/ImproveFocusScreen.kt`、`ImproveFocusViewModel.kt` |
| | 改进计划确认（US41） | `ui/improve/ImprovePlanScreen.kt`、`ImprovePlanViewModel.kt` |
| 孙浩然 | 材料删除重采（US11） | `ui/engagement/MaterialList.kt` |
| | 测试用例定稿，交给于卓君 | `docs/Sprint1测试用例.md` |

当天完成标志：各模块自测通过，已合并到 `main`。**21:00 检查点**：主链未打通则启动降级。

## 10/6（周二）：收尾与主链串联

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 合并各分支，联调采集 → 评价 | `navigation/AppNavHost.kt` |
| | 补完采集页遗留问题；检查各页面数据归属校验 | `ui/capture/` 下文件；各模块 ViewModel |
| | 主持全链路串联；修缺陷 | `docs/Sprint1缺陷清单.md` |
| 王博田 | 教师查看反馈（US33） | `ui/feedback/FeedbackListScreen.kt`、`FeedbackListViewModel.kt`、`FeedbackDetailScreen.kt`、`FeedbackDetailViewModel.kt` |
| | 参与全链路串联；修缺陷 | 本人模块下文件 |
| 于卓君 | 改进待办（US42） | `ui/improve/ImproveTodoScreen.kt`、`ImproveTodoViewModel.kt` |
| | 参与全链路串联；修缺陷 | 本人模块下文件 |
| 孙浩然 | 线上跟进串联发现的问题；修 US11 / US13 缺陷 | `ui/engagement/` 下文件 |

全链路串联：安排 → 检索 → 创建任务 → 采集 → 评价 → 归档 → 教师反馈 → 改进重点 → 改进计划 → 改进待办。

当天完成标志：需求文档表 6-1 的 11 步端到端场景能在模拟器上走通；12 条 Must 故事全部完成。

## 10/7（周三）：回归、Review 与交接

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 真机验证；打包 APK（Increment v0.1） | `app/build/outputs/apk/` |
| | 修缺陷；14:00 前冻结代码；16:00 主持 Sprint Review | — |
| | 主持 Retrospective；记录计划变更 | `docs/Sprint1回顾.md` |
| 王博田 | 按测试用例回归 F1、US33；修缺陷 | `docs/Sprint1缺陷清单.md` |
| | 准备演示数据重置（公共文件，改前群里说一声） | `data/DemoData.kt`（追加 `reset(db)`） |
| | 参加 Retrospective | — |
| 于卓君 | 带队回归，记录缺陷清单；修缺陷 | `docs/Sprint1缺陷清单.md` |
| | 整理测试记录 | `docs/Sprint1测试记录.md` |
| | 参加 Retrospective；更新 AI 互动记录 | — |
| 孙浩然 | 线上参加 Review，按测试用例验收 | `docs/Sprint1测试用例.md` |
| | 线上参加 Retrospective | — |

当天完成标志：回归通过；真机可完整演示端到端场景；Increment v0.1 确认，Sprint 1 结束。

## 进度汇总

| 日期 | 完成标志 | Must 故事累计 |
|---|---|---|
| 10/4 | 骨架推送、检索 → 创建任务 → 录音 | F0、US01、02、04、08、29、30 |
| 10/5 | 各模块自测通过并合并 | + US12、32、36、41 |
| 10/6 | 端到端主链走通 | + US33、42（12 条全部完成） |
| 10/7 | 回归通过、APK 交付、Review | 验收 |
