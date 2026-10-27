# 爱教学云分析代理

Android 保存业务数据和审核版本；本服务负责带设备鉴权的临时材料、持久任务队列、DeepSeek 文字/图片分析及百炼异步转写。云响应通过字段和引用校验后才提供给客户端。没有 API Key 时返回未配置状态，不返回演示分析。

本轮核对的 DeepSeek 模型为 `deepseek-flash`，官方列示版本为 V4.1-Flash-1017，支持文字和图片输入；音频由百炼处理。依据：[官方模型与能力说明](https://api-docs.deepseek.com/quick_start/pricing/)、[官方模型列表接口](https://api-docs.deepseek.com/api/list-models/)。代码使用模型名称而非锁定版本快照，实际响应的 model 会写入 producer；更换模型前须重新确认图片与 JSON 输出能力，不能只替换为旧的文字模型名称。

## 运行

需要 Python 3.11 或 3.12。Android 的 M4A/AAC 录音还需要 FFmpeg/ffprobe；PCM WAV 可直接用 Python 标准库校验。将 FFmpeg 官方发行渠道提供的可执行文件加入 PATH，或设置 `FFMPEG_PATH`、`FFPROBE_PATH`。转写前执行完整解码检查，不只依赖后缀。

在 `server/` 中执行：

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
Copy-Item .env.example .env
```

在本机编辑 `.env`：填写自己新配置的 `DEEPSEEK_API_KEY`、北京地域 `DASHSCOPE_API_KEY`；两把密钥不能混用。`.env`、本地数据库、材料和虚拟环境都已忽略。不要把密钥写入 Android、Git 或测试 fixture。

另生成一个随机设备访问令牌，将其 SHA256 十六进制摘要填入 `PROXY_CLIENT_TOKEN_HASH`，原令牌仅通过 Android 运行时配置录入；`ASSET_URL_SIGNING_KEY` 使用另外一个随机秘密。可在自己的终端用 Python `secrets.token_urlsafe(32)` 生成。代理目前只有一个设备 owner，符合本地同机切换角色的演示范围；它不是跨设备业务账户系统。

可在自己的终端一次生成下列三个值：第一行原令牌填入 Android，其余两行填入本机 `.env`。终端输出包含秘密，不要复制到仓库或测试记录。

```powershell
.\.venv\Scripts\python.exe -c "import hashlib,secrets; device_token=secrets.token_urlsafe(32); print('ANDROID_DEVICE_TOKEN='+device_token); print('PROXY_CLIENT_TOKEN_HASH='+hashlib.sha256(device_token.encode('utf-8')).hexdigest()); print('ASSET_URL_SIGNING_KEY='+secrets.token_urlsafe(32))"
```

`.env.example` 已列出代码读取的全部环境变量。必须自行填写两家 API Key、设备令牌摘要、签名密钥并替换 `PUBLIC_BASE_URL`；其余默认采用 DeepSeek 官方地址、百炼北京地域及相对于 `server/` 的存储目录。`ASR_RESULT_HOSTS` 是逗号分隔的精确主机名白名单，不填写 URL、端口或空格。已生成的配置不要再次用 `Copy-Item` 覆盖。

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --env-file .env --host 127.0.0.1 --port 8080 --no-access-log
```

以上命令在 `server/` 运行，适用于同机 Android Studio 模拟器：Debug APK 的代理地址填 `http://10.0.2.2:8080`，模拟器中的 `localhost` 指向模拟器自身。USB 真机调试可先执行 `adb reverse tcp:8080 tcp:8080`，再填 `http://127.0.0.1:8080`。当前 App 只为 Debug 的回环/模拟器宿主地址开放 HTTP；其他地址及 Release APK 使用 HTTPS。无需为同机模拟器把监听地址改为 `0.0.0.0`。

在 HTTPS 反向代理后部署并设置 `PUBLIC_BASE_URL`。百炼必须能从公网访问签名下载地址；localhost、模拟器 `10.0.2.2`、局域网 IP 只能用于 Android 到代理的调试，不能充当百炼下载地址。反向代理也应关闭 `/provider-assets/` 查询串日志。生产环境使用有效 TLS 证书，不在 APK 中禁用证书校验。

`GET /healthz` 仅检查进程存活。`GET /v1/capabilities` 需 `Authorization: Bearer <设备访问令牌>`，返回 asr/vision/semantic 的配置状态。是否能实际访问供应商、账户是否有权限及签名 URL 是否可下载，需要部署者自行进行真实材料冒烟；本仓库自动测试不会调用收费接口。

## Android 契约

所有 `/v1/*` 请求都需要设备令牌。结果使用 `jobId/state/stage/inputFingerprint/operation/result/error/resultHash` 外壳，时间单位全部为毫秒。错误包含 `code/message/retryable/retryAfterSeconds/providerRequestId/stage`，不透出云密钥或完整请求。

1. `POST /v1/assets`：multipart，`file` 为原始二进制，`metadata` 是 JSON 字符串 `{caseId,inputId,kind,sha256}`。kind 为 `AUDIO/IMAGE`，sha256 必须为实际字节的 64 位小写哈希。每个上传保存独立 case/input 引用，响应为 `assetId/sha256/mediaType/durationMs/width/height`。
2. `POST /v1/analysis-jobs`：请求头 `Idempotency-Key` 为 UUID。相同 key 与相同 body 返回原任务；同 key 不同 body 返回 409。
3. `GET /v1/analysis-jobs/{jobId}`：轮询，客户端可从 2 秒逐渐延长到 10 秒。离开页面后服务端仍处理，App 重开可按保存的 jobId 恢复。
4. `POST /v1/analysis-jobs/{jobId}/cancel`：停止后续步骤并丢弃晚到结果；已完成任务不被删除。已发往供应商的请求不承诺取消计费。
5. `POST /v1/analysis-jobs/{jobId}/ack`：body `{resultHash}`，在 Room 原子落库后调用；重复 ack 幂等。

任务输入：最多 1000 个 input（Android 的每条互动事件保留独立来源）；JSON 请求体总上限仍为 2 MiB，超限会明确拒绝，不截断材料。语义请求可省略转写的 words，句段时间、课堂映射及角色来源仍须保留；本机原始转写保留词时间戳。

```json
{
  "schemaVersion": 1,
  "caseId": "case-1",
  "operation": "CONTENT",
  "inputFingerprint": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "methodVersion": "content_v1",
  "inputs": [{
    "inputId": "tr-1",
    "kind": "TRANSCRIPT",
    "revision": 1,
    "assetId": null,
    "payload": {"segments": [{
      "segmentId": "seg-1", "text": "本节课讨论二叉树遍历。",
      "mediaStartMs": 0, "mediaEndMs": 2000,
      "speakerRole": "UNKNOWN", "roleSource": "UNKNOWN",
      "words": [], "classRanges": [], "mappingQuality": "UNKNOWN"
    }]}
  }]
}
```

上例仅解释契约，实际 fingerprint 由 Android 对真实输入和方法版本计算。服务端另存整个请求的规范化 bodyHash，防止幂等键复用到不同内容。业务来源版本是否仍为当前版本，由 Android 在激活和发布前再次验证；代理不读取 Room。

支持的输入种类：`AUDIO/IMAGE/TRANSCRIPT/COURSE_REFERENCE/PHASES/EVENTS/FACTS/IMAGE_TEXT/CONTENT/BOPPPS/CONTEXT`；也兼容 `TRANSCRIPT_SEGMENT/MANUAL_PHASE/INTERACTION_EVENT/ENGAGEMENT_RECORD/MANUAL_FACT`。来源集合分别放 `segments/knowledgePoints/objectives/events/facts/blocks/topics/activities/records`。每项须有唯一 `id` 或 `segmentId`。输出引用始终为 `{inputId,itemKey,quote}`，服务端按这两个 ID 定位，再验证引文（仅忽略空白差异）。`CONTEXT` 仅用于覆盖计算，不属于证据白名单，引用它会被拒绝。

`TRANSCRIBE` 和 `IMAGE_TEXT` 每个资产一个任务，便于独立保存成功项和重试失败项；客户端汇总多个任务展示部分成功。`TRANSCRIBE` 输入为一个 `AUDIO`，`IMAGE_TEXT` 为一个 `IMAGE`，其 assetId、caseId、inputId 和上传记录必须一致。其他 operation 不需要重新上传已经有结构化内容的来源。

| operation | payload |
|---|---|
| TRANSCRIBE | `schemaVersion/timingSource/segments`，片段含真实媒体时间、供应商返回的 words；speakerRole 初始 UNKNOWN，classRanges 初始为空，由 Android 映射 |
| IMAGE_TEXT | `readability/blocks/limitations` |
| CONTENT | `topics` |
| SYLLABUS_ALIGNMENT | `alignments/deviations/coverageScope` |
| BOPPPS | `coverage`（六类各一次）、`segments` |
| GOAL_ACTIVITY | `activities/links/unsupportedGoals` |
| TRANSITION | `transitions` |
| MATERIAL_SUPPORT | `supports/concerns` |

完整输出 JSON Schema 可通过鉴权接口 `GET /v1/schemas/{operation}` 获取，定义位于 `app/schemas.py`。`resultState=INSUFFICIENT_INPUT/UNREADABLE` 是明确的输入限制结果，不能当作已发现教学缺陷。模型返回空文本、截断、空 SUPPORTED 结果或无效引用都会失败；不会套入固定结论。

课程输入可提供 `selectedKnowledgePointIds/selectedObjectiveIds`；不提供时视传入列表自身为本次已选范围。缺失性结论需要 `CONTEXT.payload.context` 中的 `classDurationMs/allAudioTranscribed/missingAudioIds/recordingSpans`，每个 span 带 `classStartMs/classEndMs/mappingQuality`。服务端重算非 UNKNOWN 区间并集是否覆盖 90%，单独 `sufficient=true` 不会通过。条件不足时将“可能缺失”改为输入不足。

## 恢复、清理及边界

SQLite 使用 WAL、短事务租约和 leaseVersion 写入隔离。worker 续租，网络请求不占写事务。百炼 task_id 先持久化再轮询，重启优先继续原任务。供应商成功后立即下载并存储原始结果；查询链接过期后也可从本地结果恢复。POST 已发送但未拿到 task_id/结果时标记 `SUBMISSION_UNKNOWN`，需人工决定是否重新提交，避免静默重复计费。

临时限流和可安全重试的查询/下载最多重试三次；认证、余额、无权限、无效证据不自动重试。结构异常最多修复一次。每个任务有六小时截止时间，签名下载 grant 有效 24 小时。自动清理超过保留期且无活动任务引用的资产；结果默认保留七天，过期返回 EXPIRED。只删除配置资产目录中的文件。

百炼当前使用 `qwen3-asr-flash-filetrans` 的单数 `file_url/result`；时间戳本来就是毫秒。结果下载只接受配置白名单内的官方 OSS 域名，HTTP 地址会保留 path/query 转为 HTTPS；不跟随重定向，不向对象存储发送 API Key。若地域返回不同官方域名，由部署者核实后加入 `ASR_RESULT_HOSTS`。

具体调用为 `POST /services/audio/asr/transcription`，请求头 `X-DashScope-Async: enable`，body 使用 `input.file_url` 与 `parameters.channel_id=[0] / enable_itn=false / enable_words=true`；随后 `GET /tasks/{task_id}`，从 `output.result.transcription_url` 下载句段和字词结果。缺少字词时间但句段有效时保留句级定位并标记 PARTIAL，不宣称具有完整词级定位。

DeepSeek 调用 `/chat/completions`，设置 `thinking.type=disabled`、`response_format.type=json_object` 和 `stream=false`；图片以实际二进制的 base64 `image_url` 发送，`detail=original`。JSON 模式之外仍执行本服务的严格字段、枚举、时间和引文检查，模型自行输出的合法 JSON 不等于业务结果已经有效。

本服务不进行说话人身份识别；教师/学生角色来源于 Android 人工标注。课堂暂停时间由 Android 的 recording spans 映射，服务端不伪造课堂时间。模型图片区域仅为待核查候选，无法确定时为 null。

## 测试

```powershell
.\.venv\Scripts\python.exe -m pytest -q
```

测试通过 HTTPX MockTransport 和临时 SQLite 验证真实协议形状、上传解码、引文、取消、租约、恢复及错误分支；不读取聊天里的密钥、不调用收费 API。测试通过仅证明本地代码路径和协议解析，不能替代真实录音/PPT 的云端质量与部署冒烟验收。
