package edu.neu.aijiaoxue.data.remote

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ProxyConfiguration(val baseUrl: String, val token: String)

/** 只保存本项目代理令牌；云供应商密钥从不进入Android。 */
class AnalysisSettings(context: Context) {
    private val prefs = context.getSharedPreferences("analysis_proxy", Context.MODE_PRIVATE)
    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    fun get() = ProxyConfiguration(prefs.getString("url", "").orEmpty(), prefs.getString("token", "").orEmpty())
    fun save(url: String, token: String) {
        val uri = URI(url.trim().trimEnd('/'))
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && !uri.host.isNullOrBlank()) { "代理地址无效" }
        require(uri.scheme == "https" || (debug && uri.scheme == "http" && uri.host in setOf("10.0.2.2", "127.0.0.1", "localhost"))) { "代理须使用HTTPS；HTTP仅限调试本机" }
        require(token.isNotBlank()) { "请填写本项目代理令牌（不是DeepSeek密钥）" }
        prefs.edit().putString("url", uri.toString()).putString("token", token.trim()).apply()
    }
}

class AnalysisApiClient(private val configuration: ProxyConfiguration) {
    suspend fun request(method: String, path: String, body: JSONObject? = null, key: String? = null): JSONObject =
        exchange(method, path, "application/json", key) { connection ->
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }

    suspend fun upload(file: File, metadata: JSONObject): JSONObject {
        val boundary = "aijiaoxue-${UUID.randomUUID()}"
        return exchange("POST", "/v1/assets", "multipart/form-data; boundary=$boundary", null) { connection ->
            connection.setChunkedStreamingMode(64 * 1024)
            connection.outputStream.use { output ->
                fun write(text: String) = output.write(text.toByteArray(Charsets.UTF_8))
                write("--$boundary\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n$metadata\r\n")
                write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"material.${file.extension}\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(output, 64 * 1024) }
                write("\r\n--$boundary--\r\n")
            }
        }
    }

    private suspend fun exchange(method: String, path: String, contentType: String, key: String?, write: (HttpURLConnection) -> Unit): JSONObject = withContext(Dispatchers.IO) {
        require(configuration.baseUrl.isNotBlank() && configuration.token.isNotBlank()) { "请先配置分析代理地址和设备令牌" }
        require(path.startsWith("/v1/") && !path.contains(".."))
        val connection = URL(configuration.baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${configuration.token}")
            connection.setRequestProperty("Content-Type", contentType)
            key?.let { connection.setRequestProperty("Idempotency-Key", it) }
            connection.doOutput = method == "POST"
            coroutineContext.ensureActive()
            write(connection)
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val out = StringBuilder(); val buffer = CharArray(8192)
                while (true) { val n = reader.read(buffer); if (n < 0) break; out.append(buffer, 0, n); require(out.length <= 16_000_000) { "响应超过大小限制" } }
                out.toString()
            }.orEmpty()
            coroutineContext.ensureActive()
            val json = if (text.isBlank()) JSONObject() else try { JSONObject(text) } catch (_: org.json.JSONException) {
                if (status in 200..299 || status >= 500) throw java.io.IOException("代理响应不完整（HTTP $status），保留原请求以便恢复")
                else throw IllegalArgumentException("代理拒绝请求（HTTP $status）")
            }
            if (status !in 200..299) {
                val message = "分析服务请求失败（HTTP $status）：${json.optJSONObject("error")?.optString("code") ?: json.optString("detail", "请检查服务配置") }"
                if (status in setOf(408, 429) || status >= 500) throw java.io.IOException(message)
                else throw IllegalArgumentException(message)
            }
            json
        } finally { connection.disconnect() }
    }
}
