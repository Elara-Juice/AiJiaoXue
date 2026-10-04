# Sprint 1 每日任务表（10/4 重排，10/4—10/7）

- 10/4 按实际进度重排：任务总量和分工不变，Sprint 1 结束时间从 10/6 晚上延到 10/7 晚上前，需在 Retrospective 记录计划变更。
- 每晚 21:00 开 15 分钟站会（孙浩然线上或群内文字汇报）：今天完成了什么、明天做什么、遇到什么阻碍。
- **10/5 21:00 检查点**：主链未打通则由 DRI 决定降级（见 `Sprint1分工与计划.md` 第 5 节）。
- 每个成员一行，任务和文件按序号一一对应；完成一项就在任务后面打 ✅。

## 文件约定

- 代码路径相对 `app/src/main/java/edu/neu/aijiaoxue/`。
- 一个类一个文件，文件名 = 类名 + `.kt`。每个页面两个文件：`XxxScreen.kt` 和 `XxxViewModel.kt`（`XxxUiState` 写在 ViewModel 文件里）。
- `Panel`、`Section`、`Editor`、`List` 结尾的是嵌入页面的组件，没有单独路由。
- 当前登录用户从 `data/Session.kt` 取：`Session.requireUser().id`。
- 页面完成后，在 `navigation/AppNavHost.kt` 里把对应路由的 `pending(...)` 换成自己的 Screen；`taskId`、`courseId` 用 `entry.taskId()`、`entry.courseId()` 取。

## 已完成（10/2—10/4 上午）

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 1. 安装环境、创建项目 ✅<br>2. 全部实体与 DAO ✅<br>3. 写入演示数据 ✅<br>4. 包结构、导航、登录页、三角色首页 ✅ | 1. 工程 `Aijiaoxue/`<br>2. `data/entity/Entities.kt`、`data/dao/Daos.kt`、`data/dao/Rows.kt`、`data/model/Rules.kt`、`data/AppDatabase.kt`<br>3. `data/DemoData.kt`<br>4. `data/Session.kt`、`ui/login/`、`navigation/` |
| 王博田 | 1. 整理演示数据 ✅ | 1. `docs/演示数据.md` |
| 于卓君 | — | — |
| 孙浩然 | — | — |

## 10/4（周日）：推送骨架，核心功能开发 1

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 1. 建 Git 仓库，推送骨架到 `main` ✅<br>2. 采集页：录音开始 / 暂停 / 继续 / 结束（US08）<br>3. 采集页：拍照归集（US09） | 1. —<br>2. `ui/capture/CaptureScreen.kt`、`CaptureViewModel.kt`、`AudioRecorder.kt`<br>3. `ui/capture/PhotoCapture.kt`、`AndroidManifest.xml`、`res/xml/file_paths.xml` |
| 王博田 | 1. 安装开发环境；画页面草图<br>2. 课程检索（US01）<br>3. 课程详情（US02）<br>4. 创建督导任务（US04） | 1. —<br>2. `ui/course/CourseSearchScreen.kt`、`CourseSearchViewModel.kt`<br>3. `ui/course/CourseDetailScreen.kt`、`CourseDetailViewModel.kt`<br>4. `ui/course/TaskCreateScreen.kt`、`TaskCreateViewModel.kt` |
| 于卓君 | 1. 安装开发环境；读需求 US29/30/32/36/41/42；画页面草图<br>2. 结构化评价表单与总分计算（US29）<br>3. 综合意见与问题条目（US30）<br>4. 提交评价，状态改为“已完成” | 1. —<br>2. `ui/evaluation/EvaluationScreen.kt`、`EvaluationViewModel.kt`、`ScoreSection.kt`<br>3. `ui/evaluation/IssueEditor.kt`<br>4. `ui/evaluation/EvaluationViewModel.kt` |
| 孙浩然 | 1. 确认设备情况<br>2. 参与状态记录（US13）<br>3. 开始写测试用例 | 1. —<br>2. `ui/engagement/EngagementPanel.kt`、`EngagementViewModel.kt`<br>3. `docs/Sprint1测试用例.md` |

当天完成标志：仓库可拉取；督导能检索课程、创建任务、进入采集页并录音。

## 10/5（周一）：核心功能开发 2

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 1. 互动事件一键标注、撤销、计数（US12）<br>2. 结束课堂采集，状态改为“待评价”<br>3. 任务恢复（US07，原王博田）：我的督导任务列表，“继续”回到采集或评价，恢复已采集材料 | 1. `ui/capture/InteractionPanel.kt`<br>2. `ui/capture/CaptureViewModel.kt`<br>3. `ui/course/MyTasksScreen.kt`、`MyTasksViewModel.kt`；材料恢复写在 `ui/capture/CaptureViewModel.kt` |
| 王博田 | 1. 督导安排（US05）<br>2. 任务进度跟踪（US06） | 1. `ui/arrange/ArrangementScreen.kt`、`ArrangementViewModel.kt`<br>2. `ui/arrange/TaskProgressScreen.kt`、`TaskProgressViewModel.kt` |
| 于卓君 | 1. 历史归档与检索（US32）<br>2. 改进重点选择（US36）<br>3. 改进计划确认（US41） | 1. `ui/evaluation/HistoryScreen.kt`、`HistoryViewModel.kt`、`HistoryDetailScreen.kt`、`HistoryDetailViewModel.kt`<br>2. `ui/improve/ImproveFocusScreen.kt`、`ImproveFocusViewModel.kt`<br>3. `ui/improve/ImprovePlanScreen.kt`、`ImprovePlanViewModel.kt` |
| 孙浩然 | 1. 材料删除重采（US11）<br>2. 测试用例定稿，交给于卓君 | 1. `ui/engagement/MaterialList.kt`<br>2. `docs/Sprint1测试用例.md` |

当天完成标志：各模块自测通过并合并到 `main`。**21:00 检查点**：主链未打通则启动降级。

## 10/6（周二）：收尾与主链串联

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 1. 合并各分支，联调采集 → 评价<br>2. 补完采集页遗留问题；检查各页面数据归属校验<br>3. 主持全链路串联，修缺陷<br>4. 编写端到端自动化测试（主链 11 步），回归时直接跑 | 1. `navigation/AppNavHost.kt`<br>2. `ui/capture/`、各模块 ViewModel<br>3. `docs/Sprint1缺陷清单.md`<br>4. `app/src/androidTest/java/edu/neu/aijiaoxue/EndToEndTest.kt` |
| 王博田 | 1. 教师查看反馈（US33）<br>2. 参与全链路串联，修缺陷 | 1. `ui/feedback/FeedbackListScreen.kt`、`FeedbackListViewModel.kt`、`FeedbackDetailScreen.kt`、`FeedbackDetailViewModel.kt`<br>2. 本人模块 |
| 于卓君 | 1. 改进待办（US42）<br>2. 参与全链路串联，修缺陷 | 1. `ui/improve/ImproveTodoScreen.kt`、`ImproveTodoViewModel.kt`<br>2. 本人模块 |
| 孙浩然 | 1. 线上跟进串联问题，修 US11 / US13 缺陷 | 1. `ui/engagement/` |

全链路串联：安排 → 检索 → 创建任务 → 采集 → 评价 → 归档 → 教师反馈 → 改进重点 → 改进计划 → 改进待办。

当天完成标志：需求文档表 6-1 的 11 步端到端场景能在模拟器上走通；12 条 Must 故事全部完成。

## 10/7（周三）：回归与 Review

| 成员 | 任务 | 文件 |
|---|---|---|
| 李奕萱 | 1. 真机验证，打包 APK（Increment v0.1）<br>2. 编写 Review 演示脚本（账号、操作顺序、每步预期结果）<br>3. 14:00 前冻结代码，16:00 主持 Sprint Review<br>4. 主持 Retrospective，记录计划变更 | 1. —<br>2. `docs/Sprint1演示脚本.md`<br>3. —<br>4. `docs/Sprint1回顾.md` |
| 王博田 | 1. 按测试用例回归 F1、US33，修缺陷<br>2. 准备演示数据重置（公共文件，改前群里说一声）<br>3. 参加 Retrospective | 1. `docs/Sprint1缺陷清单.md`<br>2. `data/DemoData.kt`<br>3. — |
| 于卓君 | 1. 带队回归，记录缺陷，修缺陷<br>2. 整理测试记录<br>3. 参加 Retrospective；更新 AI 互动记录 | 1. `docs/Sprint1缺陷清单.md`<br>2. `docs/Sprint1测试记录.md`<br>3. — |
| 孙浩然 | 1. 线上参加 Review，按测试用例验收<br>2. 线上参加 Retrospective | 1. `docs/Sprint1测试用例.md`<br>2. — |

当天完成标志：回归通过；真机可完整演示端到端场景；Increment v0.1 确认，Sprint 1 结束。

## 进度汇总

| 日期 | 完成标志 | Must 故事累计 |
|---|---|---|
| 10/4 | 骨架推送；检索 → 创建任务 → 录音 | F0、US01、02、04、08、29、30 |
| 10/5 | 各模块自测通过并合并 | + US12、32、36、41 |
| 10/6 | 端到端主链走通 | + US33、42（12 条全部完成） |
| 10/7 | 回归通过、APK 交付、Review | 验收 |
