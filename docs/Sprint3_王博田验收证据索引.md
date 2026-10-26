# Sprint 3 王博田验收证据索引

本索引只列工作区当前存在的代码、测试和文档证据。未执行的真机/多账号操作、截图、录屏、会议及人工验收不作为证据。

| 对应任务/故事 | 证据文件或代码 | 验证方式 | 当前状态 |
|---|---|---|---|
| US18 实现 | [MockAIAnalyzer.kt](../app/src/main/java/edu/neu/aijiaoxue/data/ai/MockAIAnalyzer.kt) 的 `verbalRepetition` | 检查真实 `Material.note` / `InteractionEvent.note` 输入、重复片段统计、来源 ID、offset 与字符位置映射 | 已自动验证 |
| US18 测试 | [AdvancedAnalysisTest.kt](../app/src/test/java/edu/neu/aijiaoxue/data/ai/AdvancedAnalysisTest.kt) | JVM 测试覆盖有效输入、输入不足及来源/课堂位置可追溯 | 已自动验证 |
| US21 实现 | [MockAIAnalyzer.kt](../app/src/main/java/edu/neu/aijiaoxue/data/ai/MockAIAnalyzer.kt) 的 `teachingTransitions` | 检查相邻时间点规则、来源类别判断、区间、依据和不足处理 | 已自动验证 |
| US21 测试 | [AdvancedAnalysisTest.kt](../app/src/test/java/edu/neu/aijiaoxue/data/ai/AdvancedAnalysisTest.kt) | JVM 测试覆盖有效区间、零记录/单时间点不足和端点证据 | 已自动验证 |
| US18/US21 高级分析验收说明 | [Sprint3_王博田高级分析验收说明.md](./Sprint3_王博田高级分析验收说明.md) | 对照当前规则、输出、证据及人工审核链进行文档检查 | 已自动验证 |
| 分析→Evidence→审核/正式快照链 | [AIAnalysisRepository.kt](../app/src/main/java/edu/neu/aijiaoxue/data/repository/AIAnalysisRepository.kt)、[ReviewedAnalysisProvider.kt](../app/src/main/java/edu/neu/aijiaoxue/data/repository/ReviewedAnalysisProvider.kt)、[AiCorrectionRepository.kt](../app/src/main/java/edu/neu/aijiaoxue/data/repository/AiCorrectionRepository.kt)、[EvaluationRepository.kt](../app/src/main/java/edu/neu/aijiaoxue/ui/evaluation/EvaluationRepository.kt) | 源码路径检查；快照构造边界由 `AdvancedAnalysisTest` 验证；完整 Room 持久化和 UI 旅程未执行 | 待人工验证 |
| 未审核/审核人不符/原文过期不得成为正式快照 | [AdvancedAnalysisTest.kt](../app/src/test/java/edu/neu/aijiaoxue/data/ai/AdvancedAnalysisTest.kt)、[ReviewedAnalysisProvider.kt](../app/src/main/java/edu/neu/aijiaoxue/data/repository/ReviewedAnalysisProvider.kt) | 纯函数自动测试拒绝未审核、审核人不匹配、原文不匹配及空修正；跨角色 DB 查询尚待设备验证 | 已自动验证 |
| 已审核 US18/US21 正式快照字段 | [AdvancedAnalysisTest.kt](../app/src/test/java/edu/neu/aijiaoxue/data/ai/AdvancedAnalysisTest.kt)、[ReviewedAnalysisProvider.kt](../app/src/main/java/edu/neu/aijiaoxue/data/repository/ReviewedAnalysisProvider.kt) | 自动检查类型、原文、有效内容、证据摘要和审核版本字段映射 | 已自动验证 |
| 教师正式反馈可见性与跨教师拒绝 | [Daos.kt](../app/src/main/java/edu/neu/aijiaoxue/data/dao/Daos.kt)、[FeedbackDetailViewModel.kt](../app/src/main/java/edu/neu/aijiaoxue/ui/feedback/FeedbackDetailViewModel.kt)、[ImprovementRepository.kt](../app/src/main/java/edu/neu/aijiaoxue/ui/improve/ImprovementRepository.kt) | 源码检查教师 ID/课程归属/已提交过滤；未运行 Room 多账号集成或真机深链 | 待人工验证 |
| 教师端 US18/US21 分析快照展示 | [ImprovePlanScreen.kt](../app/src/main/java/edu/neu/aijiaoxue/ui/improve/ImprovePlanScreen.kt)、[ImprovementSuggestionGenerator.kt](../app/src/main/java/edu/neu/aijiaoxue/ui/improve/ImprovementSuggestionGenerator.kt) | 检查已知页面与类型匹配范围；当前没有 US18/US21 专门展示/建议映射 | 依赖团队接口 |
| D5-D6 联调记录 | [Sprint3_王博田联调验收记录.md](./Sprint3_王博田联调验收记录.md) | 审阅记录中的检查边界、自动测试结果及尚未人工执行的事项 | 待人工验证 |
| E2E 与业务回归 | [Sprint3_王博田E2E与业务回归记录.md](./Sprint3_王博田E2E与业务回归记录.md) | 对照真实自动测试、代码检查、人工待测项和缺失接口分类 | 待人工验证 |
| 端到端展演准备 | [Sprint3_王博田端到端展演脚本.md](./Sprint3_王博田端到端展演脚本.md) | 检查管理员→督导→教师→后续督导脚本、负向旅程和备用说明 | 待人工验证 |
| 单元测试 | 命令 `bash gradlew :app:testDebugUnitTest` | 本轮执行；报告 27 tests、0 failures、0 errors、0 skipped | 已自动验证 |
| Kotlin 编译 | 命令 `bash gradlew :app:compileDebugKotlin` | 本轮执行，Gradle `BUILD SUCCESSFUL` | 已自动验证 |
| Debug APK 构建 | 命令 `bash gradlew :app:assembleDebug` | 本轮执行，Gradle `BUILD SUCCESSFUL` | 已自动验证 |
| 无可比课堂/缺测/复评趋势 | 当前代码库无 US45/46 可比性、缺测和复评趋势接口 | 未模拟输入或通过结果；待相应接口与规则交付 | 依赖团队接口 |

文档状态只表达证据类型，不代表未执行的人工步骤已完成。脚本及当前回归记录中没有截图、录屏或现场操作产物。
