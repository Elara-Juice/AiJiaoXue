# Sprint 3 王博田 D5-D6 联调验收记录

## 检查范围与方式

仅检查 US18、US21 及其现有分析、证据、审核、评价发布和教师改进计划读取边界；通过源代码检查与 JVM 单元测试，不代表完成真机或人工操作。

## 主路径检查

1. `AIAnalysisRepository.analyzeAndSave` 在同一事务替换分析结果和证据，校验材料/事件 ID 属于本任务；US18、US21 的类型、时间和 `AIEvidenceRef` 字段可写入既有表。
2. `AiCorrectionRepository.saveCorrection` 按实际督导身份保存原文/修正文并标记已审核；`ReviewedAnalysisProvider` 仅接受已审核、审核人匹配、原文未过期且修正文非空的版本。
3. `EvaluationRepository.save(submit = true)` 与正式评价提交同一事务发布审核快照。快照保留类型、原文、审核后有效内容、证据摘要和版本标识；失败时事务回滚。
4. 教师侧 `ImprovementRepository.read` 只在本人课程的已提交评价下读取正式快照，具备 Repository 数据可读路径。

## 自动验收结果与边界

- **正常分析 / 输入不足 / 证据关联**：`AdvancedAnalysisTest` 覆盖 US18、US21 的正常结果、不足提示和真实材料/事件时间证据。
- **未审核 / 已审核**：新增纯映射测试覆盖 US18/US21 快照保留分析类型、原文、修正后内容、证据摘要和审核版本；另覆盖未审核、审核人不匹配、原文过期及空修正均不能发布。
- **教师界面交付限制**：数据层会返回正式快照，但当前 `ImprovePlanScreen` 不直接展示快照列表；规则建议生成器也未将 `VERBAL_REPETITION`、`TEACHING_TRANSITION` 纳入建议类型匹配。因此 US18/US21 正式快照的教师端实际可见性尚未验收，需与负责反馈/US45-46 的成员联调；本轮不改其实现。
- **无可比课堂 / 缺测**：当前无 US45/46 可比性、缺测判定接口或数据口径；不构造虚假场景，本轮只列为待后续联调条件。
- **真机/人工操作**：督导审核、评价提交、教师账号实际查看完整流程未执行。

## 验证命令

以下结果为本轮实际执行；Android SDK 使用环境中已有的 `/home/codespace/android-sdk`。

| 命令 | 结果 |
|---|---|
| `bash gradlew :app:testDebugUnitTest` | BUILD SUCCESSFUL |
| `bash gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `bash gradlew :app:assembleDebug` | BUILD SUCCESSFUL |
