# Sprint 2 / Sprint 3 整改验证记录

验证日期：2026-10-27（Asia/Shanghai）。代码基线 `6e13b95`，代理提交 `8d61d9b`，Android提交 `16e4070`。测试完成后仅整理文件末尾空行、文档、测试证据与CI配置，没有再改变业务逻辑。

| 检查 | 结果 | 原始证据 |
|---|---|---|
| Debug APK | 构建成功 | [汇总与APK哈希](verification-summary.json) |
| JVM | 84通过，0失败 | [13份JUnit XML](jvm/) |
| Android API 37 | 218通过，0失败、0忽略 | [设备JUnit XML](android.xml) |
| FastAPI代理 | 42通过，0失败 | [pytest JUnit XML](server.xml) |

Android最终命令：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
$env:ANDROID_HOME='D:\Android\Sdk'
$env:GRADLE_USER_HOME='D:\Android\.gradle'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
```

仓库含中文路径，本次通过 `subst T:` 在 `T:\` 运行。设备为 `YzjS2Test_20261024` / Android 17 / API 37，模拟器启动并解锁。最终Gradle输出为 `BUILD SUCCESSFUL in 6m 38s`，76项任务中15项执行、61项已是最新。此处为执行摘要，XML为测试工具生成的原始副本。

代理在 `server/` 的独立虚拟环境中运行 `python -m pytest -q --junitxml=../docs/test-evidence/codex-remediation-20261027/server.xml`，并完成 `python -m pip check` 和实际Uvicorn进程健康接口检查。代理测试使用本地协议夹具、临时数据库与真实媒体解码；Android恢复测试经过实际HttpURLConnection和本机测试HTTP服务。

早期失败处理：清理模拟器System UI无响应对焦点的影响；修复历史报告隐藏原始记录、审核正文顺序不一致和并发创建证据目录的问题；更新过时标题、schema可空字段和顺序夹具。保留原业务断言后完整重跑218项通过。

本记录不包含收费供应商调用、公网HTTPS部署或真实课堂内容质量验收。云故事仍需按[工作说明](../../Sprint2-Sprint3整改工作说明.md)补充真实材料验收。APK与虚拟环境不提交Git，汇总保存APK哈希供本机核对。
