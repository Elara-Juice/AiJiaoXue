# 于卓君 Sprint 2 接口与分工

实际开发日期：2026-10-24。开发分支：`feature/yzj-sprint2-improve`。基线：团队 `main` 的 `bdbf1e8caae9fc74055c276f0f9f874c91423a47`。原任务表的 10/7—10/11 是历史计划日期，本文件不将本次实现回填为当时完成。

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

数据库统一为 v4，提供 `AppDatabase.MIGRATIONS` 的 v1/v2/v3→v4 路径，取消破坏性迁移兜底。v3 用于兼容李奕萱分支的数据库形状，**不表示其整个分支已经合入本分支**。历史数据没有发布快照时保持人工计划，不回填历史审核身份或发布版本。

## 团队交接

| 对接人／模块 | 本分支交接内容 | 尚需团队确认的事项 |
|---|---|---|
| 李奕萱／公共数据与整合 | v4 schema、三条旧库迁移、nullable 审核人、发布及建议实体 | 合并其未整合分支时核对重复公共修改、版本号、DAO 与权限差异；不能直接覆盖新 v4 |
| 孙浩然／分析与审核 | 有身份的审核写入、有效修正消费、独立 `ImprovementSuggestionGenerator` 接口 | 真实转写、真实分析与建议生成服务的接入和课堂效果验证 |
| 王博田／教师反馈入口 | 复用 taskId 的反馈→重点→计划→待办路径 | 同课程同任务演示与教师发布范围确认 |
| 于卓君／评价与改进 | 只读审核参考、候选处置、来源快照、人工回退与测试 | 人工代码审阅、交付 SHA、团队评审和整合结论 |

新增生成器只能消费传入的发布快照，返回稳定的生成版本及来源键。真实服务接入后仍需保留未审核不发布、事务外生成后复核、教师最终确认等边界；不要仅把 `RULE_BASED` 改为模型标签而保持规则输出。

本地接口验证已完成：19 条 JVM 通过，114 条不同设备用例按全量与针对复测的最新结果全部通过；其中 v1/v2/v3→v4 迁移均实际验证。具体各轮结果及限制见 `于卓君_Sprint2测试记录.md`。用户已明确授权提交并推送个人分支，实现提交为 `0627420da6c63d61c353c248374a18bbc62cabfa`，版本同时通过 `docs/test-evidence/source-manifest.json` 绑定；团队人工验收另行记录。
