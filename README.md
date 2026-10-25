# AiJiaoXue

## 于卓君 Sprint 2 个人分支

分支：`feature/yzj-sprint2-improve`。原个人实现基于 `bdbf1e8`，2026-10-25 在本分支整合团队 main `302531a`。保留实验2的 Sprint1 工程，不向 main 合并或推送。

本轮完成审核参考、评价发布快照、教师按事项选择规则辅助建议、措施来源及待办追溯，保留人工流程与数据库 v1/v2/v3/v4 → v5 的数据。当前分析器仍为上游 Mock，建议明确标注为规则辅助；真实模型能力不在本个人分支验收范围。

- [个人交付说明与演示流程](docs/于卓君_Sprint2交付说明.md)
- [接口、权限和团队整合注意事项](docs/于卓君_Sprint2接口与分工.md)
- [实际测试记录](docs/于卓君_Sprint2测试记录.md)
- [AI互动记录](docs/于卓君_AI互动记录.md)
- [Sprint3接口规划](docs/Sprint3接口规划.md)

Windows 构建（脚本指向本工程，不操作原 Sprint1）：

```powershell
.\Build-Sprint2.ps1
```

脚本使用本机 `D:\Android` 工具链，将本工程映射为英文盘符 `S:`，解决 Gradle 对中文路径及 JVM 测试类路径的限制。盘符已被其他目录占用时会停止，可用 `-Drive T:` 选择空闲盘符；也可在 Android Studio 打开该盘符目录。

在专用测试模拟器执行数据库、界面和导航测试：

```powershell
.\Build-Sprint2.ps1 -Tasks ':app:connectedDebugAndroidTest' -DeviceSerial emulator-5560
```

`emulator-5560` 是本次专用设备，其他环境请填写自己的测试设备序列号。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，测试报告位于 `app/build/reports/tests/testDebugUnitTest/` 与 `app/build/reports/androidTests/connected/debug/`。

本轮已获用户明确授权提交并推送到个人分支 [`feature/yzj-sprint2-improve`](https://github.com/Elara-Juice/AiJiaoXue/tree/feature/yzj-sprint2-improve)，不得推送或合并到 main。原个人实现提交为 `0627420da6c63d61c353c248374a18bbc62cabfa`；本次整合版本及测试证据见最新测试记录。团队验收和 main 合并另行审阅。

2026-10-25 整合实现：`736c9c0a0c96dcefc156d70d5a201cbea9d0a9dc`。构建成功，19/19 JVM、单次全量130/130设备测试通过；数据库v5，测试证据见上述记录。
