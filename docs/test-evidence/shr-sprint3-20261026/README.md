# 孙浩然 Sprint3 测试证据

日期：2026-10-26（Asia/Shanghai）。分支：`feature/shr-sprint3-governance`。开发起点：`b79aefb44921240c9bfc1aab177e3dee6986a5b1`。

## 最终结果

`final/build-and-test.log` 是最后一次构建、JVM及全量设备测试的原始日志。`final/jvm/`、`final/device/` 保留原始 XML，未重新排版或改写测试结果。

| 项目 | 结果 |
|---|---|
| Debug APK | 构建成功 |
| JVM | 54通过，0失败/错误/跳过 |
| 全量设备测试 | 183通过，0失败/错误/跳过；未使用类过滤 |
| 环境 | Windows、Android Studio JBR、Android 17 / API 37模拟器 emulator-5556 |

执行：`Build-Sprint2.ps1 -Drive T: -DeviceSerial emulator-5556 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:connectedDebugAndroidTest')`。

## 失败及修复

`failure-photo-offset/` 保留此前一次全量182项的原始报告，其中181通过、1失败。失败断言为照片相对课堂偏移应当非负：相机文件时间可能早于开课时间。修复保留 `capturedAt`，只将 `offsetMs` 下限限制为零；增加固定旧文件时间的用例后完成上述183项全量复测。没有删掉失败用例、放宽断言或将失败标成跳过。

## 版本与完整性

测试执行时尚未Git提交，不能以起点SHA冒充新增代码的测试版本。`source-manifest.json` 记录实际被测源码、测试、schema和构建配置的逐文件SHA256；`summary.json` 记录源码清单及APK校验值。`evidence-sha256.json` 用于校验归档文件原始字节。用户随后已授权提交、推送和合入main；发布前再次核对源码清单，原始测试XML和日志不变。原有S1/S2目录未参与本轮修改。

本结果证明列出的自动化路径通过；不代表真实模型效果、物理真机特殊场景、人工代码审查或团队发布决定已完成。
