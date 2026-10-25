# 于卓君 Sprint 2 测试记录

## 2026-10-25：团队main整合版本（本次交付）

被测实现提交：`736c9c0a0c96dcefc156d70d5a201cbea9d0a9dc`。父提交分别为个人 `4885ec6882cad1d6c4ae3cda495f26e2589e0d11` 与团队 main `302531a061389fbb71a509a102cf95d7638b371e`；普通合并发生在 `feature/yzj-sprint2-improve`，不向 main 合并。

**最终构建通过；19/19 条 JVM 用例通过；同一次无筛选全量设备运行 130/130 通过，0 failures、0 errors、0 skipped。**本次不是用多次定向复测拼出全量通过数量。

| 验证项 | 结果与范围 | 原始证据 |
|---|---|---|
| 主工程及测试APK编译 | `assembleDebug`、`assembleDebugAndroidTest` 通过；Room生成v5 schema，历史v3/v4保持 | [完整构建日志](test-evidence/integration-20261025/full-build.log) |
| JVM | 19/19：原规则／验证16条与建议生成3条 | [本次证据目录](test-evidence/integration-20261025/) 中4份TEST XML |
| 最终设备全量 | 130/130，同一次运行，无类筛选；含原86条回归及整合后44条S2用例 | [全量XML](test-evidence/integration-20261025/instrumentation-full-130-pass.xml) |
| 团队分析测试 | 6/6，四输入、证据、替换、审核、越权、任务状态与材料删除行为 | 同上 `data.AiAnalysisTest` |
| 上游安全与并发 | 11/11，actor权限、审核原子性、慢分析遇新审核／参与变化、跨任务证据、回滚、空结果／异常保留旧数据 | 同上 `AiUpstreamSecurityTest` |
| 教师改进数据 | 17/17，正式发布冻结、空发布、教师隔离、逐项处置、幂等、确认锁定、人工回退、迟到生成、同时间最新审核、教师编辑／放弃保留 | 同上 `Sprint2ImprovementTest` |
| 真实旧库迁移 | 5/5，v1/2/3、正常v4、异常v4；保留计划／来源，恢复FK／索引，归档原字段，三表已删除高ID不复用 | 同上 `Sprint2MigrationTest` |
| S2组件交互 | 4/4，评分不自动写入、建议处置、日期、确认、待办、失败人工回退及快速离页保存 | 同上 `Sprint2UiTest` |
| 正式App导航 | 1/1，真实登录及AppNavHost；同一草稿往返审核、提交、教师反馈／重点／建议／确认／待办与返回 | 同上 `Sprint2NavigationTest`；[定向XML](test-evidence/integration-20261025/navigation-1-pass.xml) |

### 环境、执行与隔离

沿用专用 `YzjS2Test_20261024` AVD，serial `emulator-5560`，API37、Android17、x86_64。没有清空个人演示设备；迁移用随机独立数据库，Repository用内存库，正式导航创建专用测试账号／课程后仅清理自己的行。原Sprint1工程 HEAD 保持 `7df61fc` 且工作区干净。验证结束后已停止本次启动的专用模拟器释放资源，AVD保留供复跑。

Windows + Android Studio JBR25、SDK `D:\Android\Sdk`、Gradle Wrapper9.6.0、缓存 `D:\Android\.gradle`。使用根目录 `Build-Sprint2.ps1` 的英文 `S:` 映射。最终实际命令：

```powershell
.\Build-Sprint2.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:connectedDebugAndroidTest') -DeviceSerial emulator-5560
```

本次执行顺序为初次构建／JVM→迁移专项→完整导航专项→最终全量。首轮传入多个class的筛选命令，XML实际仅列迁移5条（5/5通过），没有登记成所有专项已执行；随后导航1/1和最终无筛选全量提供完整覆盖。初轮结果保存在 [迁移XML](test-evidence/integration-20261025/migration-initial-5-pass.xml)。

代码二审后加入空分析结果保全，并强化三AI表历史自增ID测试；上述最终全量已包含这些修改。新迁移代码的数组泛型警告已修正；构建工具仍有protobuf/JDK的弃用提示，不影响本轮构建和测试。没有修改原始XML来消除失败，也没有新增测试后仍引用旧统计。

### 交付版本与限制

新APK：`实验/3/于卓君_Sprint2_集成测试版_20261025.apk`，SHA256 `7ab625e62807f4524b336786715e3648bfa8a5fea74e0a79637340b19b1310df`。原个人测试APK保留。

[源码／APK／测试哈希清单](test-evidence/integration-20261025/source-manifest.json) 绑定本次实现提交、源码Git blob、工作文件SHA256和报告；[结果汇总](test-evidence/integration-20261025/verification-summary.json) 列出所有测试类。后续文档整理提交不改变被测源码。

19处合并冲突及自动合并的Capture重复参数已处理；main的公共AI能力和个人评价／改进能力均纳入本次回归。当前上游仍是Mock，建议仍是明示规则辅助；本报告验证软件行为与数据保护，不证明真实模型分析准确性、教学改善成效，也不代替团队人工验收。

---

## 2026-10-24：原个人版本历史记录

以下内容及旧 `test-evidence/` 文件仅对应原实现 `0627420`，保留原始经过与局限，不表示本次版本：


记录日期：2026-10-24。分支：`feature/yzj-sprint2-improve`。基线提交：`bdbf1e8caae9fc74055c276f0f9f874c91423a47`。测试完成后，用户明确授权提交并推送个人分支。对应实现提交为 `0627420da6c63d61c353c248374a18bbc62cabfa`。被测源码与 APK 由 [source-manifest.json](test-evidence/source-manifest.json) 的 SHA256 清单绑定；基线 SHA 不是本次修改的提交 SHA。

最终结果：**19 条 JVM 用例通过；114 条不同设备用例在全量运行及针对复测后的最新结果均通过，无仍失败用例。**设备结果来自“全量 113/114 通过＋修复一处测试同步问题后 S2 UI 4/4 复测通过”，不是单次全量 114/114 全绿。

## 环境与隔离

Windows，本机 Android Studio JBR 25，Android SDK 37.0，Build Tools 36.0.0，Gradle Wrapper 9.6.0。SDK：`D:\Android\Sdk`，Gradle 缓存：`D:\Android\.gradle`。

本次单独建立 `YzjS2Test_20261024` AVD：API 37、Google APIs x86_64、2 核／2 GB、720×1280；ADB serial 为 `emulator-5560`。设备目录 `D:\Android\avd-yzj-s2-20261024`，使用全新 userdata，没有复制或清空旧演示设备。创建及启动已实际完成，ADB 检查 `sys.boot_completed=1`。最终测试由该 serial 定向执行。

测试结束后已停止本次专用模拟器释放内存，AVD 保留以便复跑。再次执行设备测试前需启动该 AVD，并核对实际 serial。

S2 Repository 测试用 Room 内存库；迁移与重开测试用随机独立数据库名并在结束时清理。现有部分 S1 导航／采集测试使用应用数据库，故全套测试也只在本次专用设备运行。

## 新增测试与需求依据

| 测试类 | 用例数 | 关键行为 |
|---|---:|---|
| `ImprovementSuggestionGeneratorTest` | 3 | 只选相关有效审核依据、明确规则来源、无相关依据／不明自选问题不虚构候选、前测建议使用有效修正文本 |
| `Sprint2ImprovementTest` | 15 | 有身份修正、false 仍有效、历史匿名不发布、发布冻结、教师／任务隔离、按事项采用、幂等、修改来源、放弃恢复、确认锁定及待办、原分析删除保快照、人工回退、慢请求复核、生成失败、磁盘重开 |
| `Sprint2MigrationTest` | 3 | 从真实历史 v1/v2/v3 schema 建旧库后升级至 v4，保留评价草稿、正式评价、重点、草稿／已确认计划、待办和旧 AI 数据 |
| `AiUpstreamSecurityTest` | 6 | 分析／审核／证据的角色与任务归属、只读证据、重新分析清理、有效审核和事务状态 |
| `Sprint2UiTest` | 4 | 审核参考不自动评分、人工草稿保留；放弃恢复、修改采用、确认锁定、待办来源；建议失败时人工新增及确认；快速编辑离页后重开采用不会丢失 |

历史 schema 测试夹具位于 `app/src/androidTest/assets/migration-schemas/`。v1/v2 来源于 main 基线的导出 schema；v3 来源于 `origin/feature/lyx-sprint2` 的 `7f48eb1b4c414408fc2a3190e9cbf5ccbc1011fb`。测试按 schema SQL 创建真实 SQLite，再用生产迁移打开，由 Room 校验结构，另检查业务记录与外键完整性。此测试不代表运行过队友分支的全部应用功能。

S1 回归依据为 `docs/Sprint1需求说明.docx`：

| 需求 | 继续验证的约束 |
|---|---|
| US29 | 人工四维评分、草稿保存、缺分阻止提交，AI 参考不改分 |
| US30 | 问题条目与总体意见必填，提交发布给教师，提交后锁定 |
| US36 | 至少一个改进重点、优先级、自选事项、确认前可调整 |
| US41 | 逐条采纳／修改／放弃、自填措施、每事项至少一条、有效日期、教师确认及来源区分 |
| US42 | 确认生成自己的待办、状态更新、原计划／反馈／来源关联 |

S1 原有 16 条 JVM、86 条设备用例；本轮新增 3 条 JVM、28 条设备用例。最终实际覆盖共 19 条 JVM 和 114 条不同设备用例，详见以下原始报告。

## 执行记录

最终可复现入口为项目根目录 [Build-Sprint2.ps1](../Build-Sprint2.ps1)。脚本安全核对并将当前新工程映射为 `S:`，配置 JBR／SDK／缓存，运行后恢复环境变量；不占用旧 S1 映射，不需要 `-Pandroid.overridePathCheck=true`。此前英文 junction 会被 Gradle 解析回中文真实路径，因此不作为最终方案。

在新工程根目录运行：

```powershell
.\Build-Sprint2.ps1
.\Build-Sprint2.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:connectedDebugAndroidTest') -DeviceSerial emulator-5560
```

第一条执行构建及 JVM 测试；第二条增加专用设备测试。脚本已经实际执行验证。设备需已启动；脚本本身不启动、清空或关闭设备。

| 检查／任务 | 实际结果 |
|---|---|
| `:app:assembleDebug` | 通过；测试 APK 交付副本为 `实验/3/于卓君_Sprint2_测试版.apk`，哈希见 manifest |
| `:app:testDebugUnitTest` | 19/19 通过，0 failures/errors；包括 S1 16 条与规则建议 3 条 |
| `:app:assembleDebugAndroidTest` | 通过，设备测试 APK 构建成功 |
| `:app:connectedDebugAndroidTest` 全量 | 114 条，113 通过、1 失败、0 errors/skipped；唯一失败是新 UI 测试先于异步页面初始化读取 `todo`；[原始 XML](test-evidence/instrumentation-full-114.xml) |
| S2 UI 定向复测 | 4/4 通过，0 failures/errors；类筛选 `edu.neu.aijiaoxue.sprint2.Sprint2UiTest`；[复测 XML](test-evidence/instrumentation-ui-retest-4.xml) |
| v1/v2/v3→v4 迁移定向复测 | 3/3 通过；类筛选 `edu.neu.aijiaoxue.sprint2.Sprint2MigrationTest`；[迁移 XML](test-evidence/migrations-3-pass.xml)；后续全量中也全部通过 |
| S1 回归／正式导航 | 原有 86 条设备用例在最终全量中全部通过，包含原 S1 真实 AppNavHost 导航和完整端到端测试 |
| 最终逐用例汇总 | 114 条不同设备用例，取每例最新结果后 114 通过、0 仍失败；不把复测的 4 条重复计入总数 |
| 真课堂材料＋真实模型服务 | 未完成；当前上游仍为 Mock，建议为本地规则辅助 |

归档报告位于 [test-evidence](test-evidence/)，包括 JVM XML、全量 XML、UI 复测 XML、迁移 XML 与源码／APK 哈希清单。Gradle 还生成 `app/build/reports/tests/testDebugUnitTest/`、`app/build/reports/androidTests/connected/` 及对应原始结果目录；后续定向运行可能覆盖构建目录，审阅以已归档证据为准。

最后一次改动仅修正 UI **测试夹具**：等待待办页面节点出现后才读取其 ViewModel。产品源码和生产 APK 没有因此变化，因此复测该类 4 条，未无依据重复整套。工具检查与 AI 静态审阅不替代同学的最终代码审阅。

### 已发生的问题与修复

- 首次 JVM 执行因 Windows 中文 classpath 导致测试类启动失败；处理构建入口后，实际读取到 19 条 JVM 用例的通过 XML，不将此前启动失败算成测试成功。
- 首轮设备报告为 110 条、11 失败，保存在 `D:\Android\workspaces\yzj-sprint2-first-instrumentation.xml`。当轮尚未包含新增 S2 Compose 测试，不能直接将它与最终测试总数混为一套。
- 其中 3 条迁移失败发生在旧库测试夹具解析阶段：历史 Room schema 省略了默认 false 的 `notNull` 和空 `indices`。改为读取缺省值后，三条真实迁移定向复跑均通过。这次失败没有证据表明生产迁移逻辑损坏。
- 其余 8 条为旧 UI 测试的节点定位／键盘或录音页面交互问题，修复后在最终全量中通过。
- 最终全量唯一的新失败为 `Sprint2UiTest.suggestionCanBeDismissedRestoredEditedConfirmedAndTracedInTodo` 的 `lateinit property todo has not been initialized`。等待页面节点后再读取的同步修复仅作用于测试，随后该类 4 条全部通过；保留全量中的失败记录，没有将原始 XML 改成通过。
