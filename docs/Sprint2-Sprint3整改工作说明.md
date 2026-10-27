# Sprint 2 与 Sprint 3 代码整改工作说明

本轮以《第8组_Sprint2与Sprint3代码编写设计文档》为实施基线，在现有 Android 产品上补齐课堂输入、真实云分析、人工审核、正式发布、复评与治理之间的连接。业务数据仍保存在本机；云端代理只承担材料处理和分析任务。

代码分支：[feature/codex-sprint-remediation](https://github.com/Elara-Juice/AiJiaoXue/tree/feature/codex-sprint-remediation)。基础版本：`6e13b95`。本说明随代码交付，不把自动测试通过等同于真实课堂材料已经验收。

## 1. 本轮实现

| 故事 | 代码中的实现与操作入口 |
|---|---|
| US03 课程大纲与目标 | 管理员从课程详情录入版本；督导在“课堂分析资料”显式绑定本课堂的知识点和目标范围。历史绑定不会自动升级。 |
| US14 人工教学环节 | 保存草稿、校验整数时间及单层不重叠约束，确认后形成不可变版本；下游只使用已确认版本。 |
| US15 真实转写 | 音频逐文件提交百炼任务，保存供应商句级及词级时间；缺少词级时间时明确标记部分成功并保留句级定位。支持校订、人工说话角色、原音频定位、50句分页及暂停跨度映射。 |
| US16 内容分析 | DeepSeek 从实际转写提取主题和知识点，每条候选必须引用本次输入。 |
| US17 大纲对齐 | 对本次选中知识点逐项说明覆盖情况；录音覆盖不足时不能断言“未讲授”。人工可调整对应、理由和引用。 |
| US19 BOPPPS | 六类覆盖状态与实际片段分开保存；人工可修分类、来源及真实证据范围内的时间边界。 |
| US20 目标与活动 | 目标、活动、支撑关系及缺少依据的目标分别建模，校验ID归属和互斥关系。 |
| US26 证据追溯 | 点击分析或正式报告来源打开原图片、音频片段、事件、课程要求或人工事实；数据层再次校验任务和角色。 |
| US28 事实补录 | 发生时间与补录时间分开保存，更正追加新版本，重复请求幂等；发布时冻结事实及其来源。 |
| US31 完整报告 | 历史记录显示课程上下文、审核有效分析、人工评价、问题声明和事实；正式快照不随候选重算改变。教师收到已发布摘要。 |
| US34 异常课程 | 管理员首页新增“异常课程”，筛查正式评价低于60分的课堂；60分不算异常，无效分数及无正式评价分别列示，可下钻报告。 |
| US18 表达统计 | 对真实转写进行确定性匹配，保留实际次数和字符位置；支持教师角色范围、专业术语与忽略表达。候选不自动认定为语言问题。 |
| US21 环节衔接 | 依据有效环节及真实前后文本生成衔接候选；未知时间不能伪造阶段，空档与语义问题分开表达。 |
| US22 材料支撑 | 逐图片识别内容，再关联实际讲授主题或知识点；图片块、讲授证据与结论交叉校验，无法辨读单独标识。 |
| US24 多源参与 | 普通课堂入口即可访问，无需先创建复评链接；保留正常对照、冲突来源及输入不足状态。 |
| US25 环节定位 | 读取已审核BOPPPS或已确认人工环节，按所有真实重叠范围定位；正式课堂读取发布时冻结版本。 |
| US45 指标比较 | 增加教学设计指标，统一课堂先后规则，倒序、重叠或顺序不明不能作为有效比较。 |
| US46 改善核验 | 按具体原问题填写存在状态、改善程度及相关主证据；支持部分改善及冲突说明，不能只因总分上涨就判问题解决。 |

问题声明新增“尚未判断 / 存在主要问题 / 确认本次无主要问题”。旧核验记录保留，但不自动当作新口径有效核验；治理页面单列待重新核验，并明确改善率分母。

## 2. 关键实现位置

- [分析代理及部署说明](../server/README.md)：FastAPI、SQLite持久任务、DeepSeek/百炼适配、真实文件校验、签名下载、取消、租约恢复、有限重试和过期清理。
- [Android分析输入](../app/src/main/java/edu/neu/aijiaoxue/data/ai/AnalysisInputs.kt) 与 [任务流程](../app/src/main/java/edu/neu/aijiaoxue/data/ai/CloudAnalysisPipeline.kt)：来源白名单、输入指纹、幂等键、取消和迟到结果保护。
- [结构化审核校验](../app/src/main/java/edu/neu/aijiaoxue/data/repository/StructuredPayloadValidation.kt) 与 [正式发布](../app/src/main/java/edu/neu/aijiaoxue/data/repository/ReviewedAnalysisProvider.kt)：有效payload、人工校正、来源冻结和历史稳定性。
- [有效教学环节](../app/src/main/java/edu/neu/aijiaoxue/data/repository/EffectivePhaseProvider.kt) 与 [逐问题核验](../app/src/main/java/edu/neu/aijiaoxue/data/repository/ReviewVerificationService.kt)：复评与参与分析使用同一可信来源。
- 数据库升级到 v10，新增8→9及9→10迁移并继续注册历史升级链；[Room导出结构](../app/schemas/edu.neu.aijiaoxue.data.AppDatabase/10.json)随代码提交。

生产导航已从 Mock 分析切换到真实代理和明确标识的本地统计。旧 Mock 代码仅用于兼容回归，历史记录保留来源标签。重算只替代当前操作的激活批次，不删除旧审核链和正式报告；页面可查看历史批次。

云模型配置采用 `deepseek-flash` 做文字和图片分析，官方列示版本为 V4.1-Flash-1017，能力依据见 [DeepSeek 官方模型说明](https://api-docs.deepseek.com/quick_start/pricing/) 和 [官方模型列表接口](https://api-docs.deepseek.com/api/list-models/)。音频使用百炼北京地域 `qwen3-asr-flash-filetrans`，两家密钥分别配置。DeepSeek 模型名称可能随供应商升级，实际响应模型记录在结果 producer 中；本轮未执行收费 API 冒烟。

## 3. 如何运行与演示

1. 用 Android Studio 打开仓库，安装 Android API 37，构建并安装 Debug APK。
2. 按 `server/README.md` 创建 Python 环境并安装 `requirements.lock.txt`；安装可执行的 FFmpeg/FFprobe。
3. 复制 `server/.env.example` 为本机 `.env`，自行配置 DeepSeek密钥、百炼密钥、设备令牌摘要、签名密钥和公网HTTPS地址。两家密钥不能混用，不进入APK或Git。
4. 在 `server/` 启动代理，在应用“我的督导任务 → AI分析 → 配置云端分析服务”填写代理地址和设备令牌。README 的 `127.0.0.1:8080` 监听适用于同机 Debug 模拟器，App 中填 `http://10.0.2.2:8080`。USB 真机可按 README 使用 `adb reverse`；其他网络地址使用 HTTPS。百炼下载音频所用的 `PUBLIC_BASE_URL` 必须能从公网访问，与模拟器访问地址分别配置。
5. 管理员录入课程要求；督导采集真实课堂并结束采集，绑定本次范围、确认教学环节，逐音频转写、逐图片识别，校订转写并运行相关分析。
6. 督导打开证据、保存结构修正或确认原结构，再提交人工评价；从“历史记录”查看冻结的完整报告。教师接收反馈、制定和落实改进，后续课堂按具体原问题完成复评核验。

本机路径含中文时，Android构建工具会拒绝直接构建。本轮临时映射到 `T:` 后运行；如自己复现，先确认该盘符空闲，再在仓库根目录执行：

```powershell
subst T: (Get-Location).Path
Set-Location T:\
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:GRADLE_USER_HOME='D:\Android\.gradle'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
```

上面的JDK与SDK位置是本次机器实际配置，其他机器应换成本机路径。设备测试需先启动并解锁模拟器。

未配置代理或供应商时，可以完成人工业务链和本地统计；真实云能力会给出配置或处理失败信息，不生成固定文字冒充转写。单个材料一个任务，成功结果独立保存。语义请求只发送句级文本与时间，完整词级时间仍保存在本机；代理限制每请求1000项输入和2MiB，不静默截断。

## 4. 验证记录

2026-10-27 最终验证通过，原始XML与构建摘要归档到 [本轮测试证据](test-evidence/codex-remediation-20261027/README.md)。代理实现提交为 `8d61d9b`，Android实现提交为 `16e4070`；后续提交补充本说明、测试证据与CI配置。

| 检查 | 最终结果 |
|---|---|
| Debug APK构建 | `assembleDebug` 成功 |
| JVM规则测试 | 84项通过，0失败 |
| API 37模拟器测试 | 218项通过，0失败、0忽略 |
| FastAPI代理测试 | 42项通过，0失败 |
| 依赖与进程检查 | `pip check` 通过，真实Uvicorn进程 `/healthz` 返回成功 |
| Git差异检查 | `git diff --check` 通过 |

共344项自动测试通过。覆盖Room升级与历史数据保留、来源版本、权限、审核发布、全业务链导航、真实本机HTTP下的幂等恢复和迟到结果隔离，以及代理供应商协议夹具。APK位于 `app/build/outputs/apk/debug/app-debug.apk`，文件SHA256见测试摘要；本地构建结果不等同于GitHub Actions已完成。

本轮早期设备测试遇到模拟器System UI无响应弹窗导致窗口焦点失败；已定位并处理环境问题。测试期间同时修正旧测试类型枚举、历史schema可空字段和顺序测试唯一键夹具，保留原业务断言。

回归还修复了三处实际问题：历史报告保留冻结摘要的同时恢复原始材料列表与缺文件提示；审核正文从保存的规范化结构统一生成，保证保存、重开与发布一致；并发导入改进证据时，目录被另一请求创建也能正确继续。导航、角色权限、草稿保留及证据幂等断言均继续执行。

## 5. 真实云验收仍需完成

代码及自动测试覆盖与真实云质量验收分别记录。本轮没有调用聊天中提供的密钥或收费接口，没有在公网部署服务。

- 配置可用的两家云服务及余额，部署公网HTTPS，确认百炼可下载签名音频。
- 使用团队允许演示的两段不同主题录音、静音/损坏文件和一次暂停继续样本，抽查转写文本与实际播放位置。
- 使用真实PPT及板书，检查识别、材料对应关系、大纲同义表述、部分录音和非典型BOPPPS结构。
- 对US15、16、17、19、20、21、22等云端故事保存真实输出、文件哈希及人工核验记录，再按编码设计文档的关闭矩阵确认验收。当前应写“已实现，待真实材料验收”，不能仅凭协议夹具关闭故事。

Debug APK用于课程开发演示；应用商店签名发布、跨设备业务同步、服务端业务账户系统不属于本轮范围。
