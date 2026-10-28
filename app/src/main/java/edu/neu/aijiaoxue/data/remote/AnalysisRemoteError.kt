package edu.neu.aijiaoxue.data.remote

import org.json.JSONObject
import java.io.IOException

class AnalysisRemoteError(val code: String, override val message: String, val retryable: Boolean = false,
    val retryAfterSeconds: Double? = null, val stage: String? = null, val providerRequestId: String? = null,
    val httpStatus: Int? = null) : IOException(message) {
    fun json() = JSONObject().put("code", code).put("message", message).put("retryable", retryable)
        .put("retryAfterSeconds", retryAfterSeconds ?: JSONObject.NULL).put("stage", stage ?: JSONObject.NULL)
        .put("providerRequestId", providerRequestId ?: JSONObject.NULL).put("httpStatus", httpStatus ?: JSONObject.NULL)
    companion object {
        fun from(json: JSONObject, status: Int? = null) = AnalysisRemoteError(json.optString("code", "REMOTE_ERROR"),
            json.optString("message", "分析服务请求失败"), json.optBoolean("retryable", status == 429 || status == 408 || (status ?: 0) >= 500),
            json.optDouble("retryAfterSeconds").takeIf { it.isFinite() && it >= 0 },
            json.optString("stage").takeIf { it.isNotBlank() && it != "null" },
            json.optString("providerRequestId").takeIf { it.isNotBlank() && it != "null" }, status)
    }
}
