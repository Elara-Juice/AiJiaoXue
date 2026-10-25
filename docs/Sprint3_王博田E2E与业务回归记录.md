# Sprint 3 王博田 E2E 与业务回归记录

## 范围

仅覆盖 US18/US21 与已有分析、Evidence、审核快照、正式评价及教师反馈入口；不实现 US22、US45/46，不修改其他成员业务模块。自动结果来自当前 JVM 单元测试及 Gradle 验证；源代码检查与自动测试、人工操作分别标注，不将代码阅读等同于运行时验收。

## 回归项

| 用例 | 检查方式/实际结果 | 状态 |
|---|---|---|
| US18 正常重复表达统计 | `AdvancedAnalysisTest.us18FindsRepeatedExpressionInRecordedNotes` 使用测试输入确认规则命中和证据非空 | 已通过 |
| US18 输入不足不伪造结论 | `us18ReportsInsufficientInputWithoutInventingConclusion` 确认不足提示和空证据 | 已通过 |
| US18 来源与 offset 可追溯 | `us18EvidenceTracesRepeatedTextToSourceAndClassPosition` 检查材料/事件 ID、时间偏移及结果范围 | 已通过 |
| US21 相邻区间诊断 | `us21BuildsTransitionIntervalsFromMaterialsAndEvents` 检查方法、区间、来源判断依据及端点证据 | 已通过 |
| US21 无记录/单时间点 | `us21DoesNotCreateAnIntervalWithoutTimedSources`、`us21ReportsInsufficientInputWhenOnlyOneTimePointExists` 检查不足提示及不生成虚假完整结构 | 已通过 |
| 已审核 US18/US21 可形成快照 | `reviewedUs18AndUs21ResultsRetainTypeContentAndEvidenceSummary` 检查类型、原文、有效内容、证据摘要和版本 | 已通过 |
| 未审核/审核人不匹配/原文不匹配/空修正不得形成快照 | `unreviewedOrMismatchedCorrectionCannotBecomeFormalSnapshot` 覆盖四类拒绝条件 | 已通过 |
| 跨教师访问拒绝 | DAO 查询按 actor ID、课程归属、TEACHER 角色及已提交状态过滤；当前 JVM 测试没有 Room 数据库运行环境，未做跨账号持久化调用 | 待人工验证 |
| 未发布评价不可由教师读取 | 教师反馈查询仅选 `SUBMITTED`；Repository 发布与评价提交置于事务中。尚未对 Room 持久化/教师列表做自动集成验证 | 待人工验证 |
| 分析结果与持久化 Evidence 外键关联 | 分析仓库以成对 `AiAnalysisResult + evidences` 交给 DAO 事务写入，DAO 使用实际插入的 analysis ID 写证据；当前新增测试覆盖分析输出/快照摘要，不是 Room 外键集成测试 | 待人工验证 |
| 无可比课堂/缺测/复评趋势 | 当前无 US45/46 的可比性、缺测、复评关系接口和页面 | 因依赖接口未具备而无法验证 |
| 督导→教师的真机连续旅程 | 本轮未运行真机、未切换账号现场操作 | 待人工验证 |

## 自动验证

本轮执行时使用环境已有 Android SDK `/home/codespace/android-sdk`：

| 命令 | 实际结果 |
|---|---|
| `bash gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL；报告 27 tests，0 failures，0 errors，0 skipped |
| `bash gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `bash gradlew :app:assembleDebug` | BUILD SUCCESSFUL |

除现有 AdvancedAnalysisTest 外，本轮未新增测试代码：已存在的自动用例覆盖本阶段可由纯 JVM 可靠验证的 US18/US21、正式快照门槛和证据摘要。跨账号 SQL/Room 事务行为需数据库集成或设备运行，不以模拟 helper 代替。

## 展演准备检查

- **正常主旅程**：展演脚本含管理员安排、督导创建任务/采集/分析/审核/评价提交、教师查看本人已提交反馈、后续督导；明确高级分析在教师端无专门展示位。
- **跨教师越权**：列出非所属教师直接尝试反馈 taskId 的负向步骤；DAO 过滤已检查，运行时拒绝待人工。
- **未发布结果**：列出草稿/未审核负向路径；审核快照门槛有自动测试，教师列表持久化过滤待人工。
- **输入不足**：US18 无有效 note、US21 无时间记录/单时间点均有自动测试。
- **无可比数据**：无接口时不演示、不模拟通过，标为因依赖接口未具备而无法验证。
- **故障备用**：脚本要求保留不足提示和错误状态，不伪造输入或成功；没有截图、录屏或现场结果被记录为已完成。
