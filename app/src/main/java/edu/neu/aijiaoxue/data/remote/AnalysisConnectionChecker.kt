package edu.neu.aijiaoxue.data.remote

import android.content.Context
import edu.neu.aijiaoxue.data.model.ConnectionCheckState
import org.json.JSONObject

data class ConnectionCheckItem(val id: String, val label: String, val state: ConnectionCheckState, val detail: String)
data class AnalysisConnectionReport(val checks: List<ConnectionCheckItem>, val asrConfigured: Boolean,
    val visionConfigured: Boolean, val semanticConfigured: Boolean, val checkedAt: Long,
    val maxAudioBytes: Long = 262144000, val maxAudioDurationMs: Long = 7200000, val maxImageBytes: Long = 10485760)

class AnalysisConnectionChecker(private val context: Context) {
    suspend fun check(): AnalysisConnectionReport {
        val config = AnalysisSettings(context).get()
        val json = AnalysisApiClient(config).request("GET", "/v1/capabilities")
        require(json.optInt("schemaVersion") == 1) { "代理协议版本不兼容" }
        val diagnostics = json.optJSONObject("diagnostics")
        fun boolCheck(id: String, label: String, parent: JSONObject?, key: String) = ConnectionCheckItem(id, label,
            if (parent == null || !parent.has(key)) ConnectionCheckState.NOT_CHECKED else if (parent.optBoolean(key)) ConnectionCheckState.PASSED else ConnectionCheckState.FAILED,
            if (parent == null || !parent.has(key)) "旧服务未提供此检查" else if (parent.optBoolean(key)) "配置检查通过，未调用云端推理" else "请检查服务端配置")
        val checks = listOf(ConnectionCheckItem("proxy", "代理连接与设备鉴权", ConnectionCheckState.PASSED, "鉴权请求成功"),
            boolCheck("asr", "转写配置", json, "asr"), boolCheck("vision", "图片分析配置", json, "vision"),
            boolCheck("semantic", "语义分析配置", json, "semantic"), boolCheck("ffmpeg", "FFmpeg", diagnostics, "ffmpegAvailable"),
            boolCheck("ffprobe", "FFprobe", diagnostics, "ffprobeAvailable"), boolCheck("https", "公网HTTPS配置", diagnostics, "publicAssetHttpsConfigured"),
            ConnectionCheckItem("cloud", "供应商权限与材料公网可达性", ConnectionCheckState.NOT_CHECKED, "未调用收费接口，需要真实样例验证"))
        val report = AnalysisConnectionReport(checks, json.optBoolean("asr"), json.optBoolean("vision"), json.optBoolean("semantic"), System.currentTimeMillis(),
            json.optLong("maxAudioBytes", 262144000).coerceAtLeast(1), json.optLong("maxAudioDurationMs", 7200000).coerceAtLeast(1), json.optLong("maxImageBytes", 10485760).coerceAtLeast(1))
        context.getSharedPreferences("analysis_preflight", Context.MODE_PRIVATE).edit().putString("baseUrl", config.baseUrl)
            .putLong("maxAudioBytes", report.maxAudioBytes).putLong("maxAudioDurationMs", report.maxAudioDurationMs).apply()
        return report
    }
    fun cachedAudioLimits(): Pair<Long, Long> {
        val prefs = context.getSharedPreferences("analysis_preflight", Context.MODE_PRIVATE)
        if (prefs.getString("baseUrl", "") != AnalysisSettings(context).get().baseUrl) return 262144000L to 7200000L
        return minOf(262144000L, prefs.getLong("maxAudioBytes", 262144000)) to minOf(7200000L, prefs.getLong("maxAudioDurationMs", 7200000))
    }
}
