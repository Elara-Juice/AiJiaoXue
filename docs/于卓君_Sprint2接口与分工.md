# 于卓君 Sprint 2 接口与分工

原个人开发：2026-10-24；整合适配：2026-10-25。开发分支：`feature/yzj-sprint2-improve`。原基线 `bdbf1e8`，本次吸收团队 `main` 的 `302531a061389fbb71a509a102cf95d7638b371e`，保留个人 `4885ec6` 之前的提交历史。原任务表的 10/7—10/11 是历史计划日期，本文件不将本次实现回填为当时完成。

## 个人交付边界

本次增量将“有身份的督导审核→人工评价提交→教师按问题处理建议→确认计划→待办与来源”接入已有流程。保留 S1 人工评价、问题选择、自填措施及待办。对应个人任务 YZJ-S2-01 至 08 的消费、持久化、页面与验证部分；US31 完整报告、真实转写和真实建议生成不因此变更为于卓君独立负责。

现有 `MockAIAnalyzer` 仍是模拟／规则分析。教师建议使用明确标注的 `RuleBasedImprovementSuggestionGenerator`；它是可执行的本地规则辅助，不是接入真实模型后生成的教学建议，也不证明课堂判断准确或措施有效。

## 发布与权限契约

| 入口 | 输入 | 行为及限制 |
|---|---|---|
| `ReviewedAnalysisProvider.forSupervisor` | `actorId, taskId` | 本任务督导读取当前有效审核；需要真实 `reviewerId`、与原文一致的最新修正及 `verified`；`accepted=false` 表示有效修改，不能当拒绝过滤 |
| `EvaluationRepository.save(..., submit=true)` | 现有人工评价草稿 | 提交事务内形成唯一发布记录和审核快照；评分、意见仍由督导输入；空审核集合也冻结 |
| `EvaluationRepository.load` | `actorId, taskId` | 未提交时显示当前已审核参考，提交后显示发布版本；不让后续审核替换已发布依据 |
| `ImprovementRepository.load` | `actorId, taskId` | 只允许本课程教师读取已完成任务、已提交评价；返回计划、措施、建议、发布快照 |
| `refreshSuggestions` | `actorId, taskId` | 只用已发布快照生成按事项的规则候选；生成在事务外，写回重新校验权限、计划与事项状态 |
| `adoptSuggestion` | `actorId, taskId, suggestionId, content?` | 同一建议只形成一条措施；可带教师修改正文；重复采用不覆盖已编辑措施 |
| `dismissSuggestion` | `actorId, taskId, suggestionId, dismissed` | 保存放弃／恢复状态；已采用建议需先移除关联措施才能放弃 |
| `saveMeasures` | 原有参数 | 校验事项与建议归属、来源一致性、日期及每事项至少一条措施；确认后锁定正文和日期 |
| `todos/updateStatus` | 原有参数 | 保留教师自己的待办与基础状态更新；不引入 S3 实施证据与复评 |

调用端传入的 ID 不构成访问授权。Repository 重新读取归属与发布状态；教师端仅显示可公开的结论／依据摘要，不因来源追溯开放原始音频、照片。

## 持久化与来源

| 数据 | 作用 |
|---|---|
| `AiCorrection.reviewerId` | 记录本次实际审核人。旧版无身份记录保留 null，不能用任务负责人倒填 |
| `ReviewPublication` | 每个正式评价的一次发布，空集合也有记录，禁止以后默默补入新结果 |
| `ReviewedAnalysisSnapshot` | 保存原分析标识、修正标识、实际审核人、有效正文、依据摘要、版本键与审核时间 |
| `ImprovementSuggestion` | 保存事项、候选正文、问题快照、教学环节、理由、规则／模型类型与版本、发布依据及放弃状态 |
| `ImprovementMeasure.suggestionId` | 可空建议关联；null 保持 S1 人工建议或教师新增逻辑 |

`MeasureSource.ADOPTED/MODIFIED/ADDED` 继续表示教师采用方式，不用来表示建议是不是 AI 来源。原分析可能因重新分析删除，已发布快照和已采用措施不依赖该原始记录级联存活。

数据库统一为 **v5**，`AppDatabase.MIGRATIONS` 提供 v1/v2/v3/v4 直达 v5 的保留数据迁移。保留团队 AI 表外键、索引及材料／事件删除后的 SET_NULL 行为；审核人字段可空以兼容旧版本，新审核必须使用真实授权 actor。原 v4 的发布、建议、教师编辑及待办保留；历史无发布快照的评价保持人工流程，不补造历史身份或发布版本。

旧 AI 数据若存在孤立父记录、未知分析类型或失效引用，原始行与原因保存在 `LegacyAiMigrationArchive`，不进入有效审核依据；可空失效引用置空。该归档仅用于迁移诊断，无业务级联外键，不开放给教师页面。历史 schema 1～4 保留，新结构导出为 5.json。

## 团队交接

| 对接人／模块 | 本分支交接内容 | 尚需团队确认的事项 |
|---|---|---|
| 李奕萱／公共数据与整合 | v5 schema、四来源直达迁移、nullable 审核人、团队 AI 约束及个人建议域 | 基于 main 302531a 的整合；后续迁移继续保留历史 schema 与来源数据 |
| 孙浩然／分析与审核 | 有身份的审核写入、有效修正消费、独立 `ImprovementSuggestionGenerator` 接口 | 真实转写、真实分析与建议生成服务的接入和课堂效果验证 |
| 王博田／教师反馈入口 | 复用 taskId 的反馈→重点→计划→待办路径 | 同课程同任务演示与教师发布范围确认 |
| 于卓君／评价与改进 | 只读审核参考、候选处置、来源快照、人工回退与测试 | 人工代码审阅、交付 SHA、团队评审和整合结论 |

新增生成器只能消费传入的发布快照，返回稳定的生成版本及来源键。真实服务接入后仍需保留未审核不发布、事务外生成后复核、教师最终确认等边界；不要仅把 `RULE_BASED` 改为模型标签而保持规则输出。

本次整合的实际构建、迁移、数据、UI 与正式导航验证以 [测试记录](于卓君_Sprint2测试记录.md) 为准。2026-10-24 的 19/114 结果只对应原个人版本；自动测试和 AI 审阅不登记为团队人工验收。

## 本次统一的公共接口

| 接口 | 统一契约 |
|---|---|
| `AIAnalyzer.analyze` | `taskId, materials, events, engagements` 四参数，保留 main 的参与记录输入 |
| `AIResult` / `AiAnalysisResult.type` | 使用 `AiAnalysisType`；发布快照在消费边界保存 `.name`，不保存中文 label |
| `AIAnalysisRepository` | `loadTask/analyzeAndSave/observeResults/clearResults(taskId, actorId)`；分析写回前在事务内重查权限、任务、材料、事件、参与与最新审核版本 |
| `AiCorrectionRepository` | `loadAnalysis/observeCorrections(analysisId, actorId)`；`saveCorrection(analysisId, actorId, correctedContent)` 返回修正 ID，事务内重查并核验 verified 更新行数 |
| `AiEvidenceRepository` | `observeEvidence(analysisId, actorId)`、`observeByTask(taskId, actorId)`，按数据层授权读取 |
| DAO / 审核映射 | 最新审核按 `createdAt DESC, id DESC`；证据材料和事件必须属于同一任务；DAO 为内部持久化接口，页面通过有 actor 的 Repository |
| 导航 | 保留 main 的 `moduleViewModel` 和分析 ID 参数；评价返回复用原路由与 VM，仅刷新审核参考；Capture 保留单一分析回调 |

耗时分析不会覆盖执行期间新增的审核或参与数据；耗时建议不会改写教师编辑／放弃决定，计划确认和事项变化后拒绝旧写回。分析可以重新运行，正式评价的发布快照保持冻结。
