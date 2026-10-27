package edu.neu.aijiaoxue.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.neu.aijiaoxue.Sprint1Fixture
import edu.neu.aijiaoxue.data.ai.CloudAnalysisPipeline
import edu.neu.aijiaoxue.data.model.AnalysisOperation
import edu.neu.aijiaoxue.data.model.JobState
import edu.neu.aijiaoxue.data.model.ResultState
import edu.neu.aijiaoxue.data.remote.AnalysisSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the production HTTP client and Room state transitions using only device loopback. */
@RunWith(AndroidJUnit4::class)
class CloudJobRecoveryTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var fixture: Sprint1Fixture
    private lateinit var server: LoopbackProxy
    private lateinit var pipeline: CloudAnalysisPipeline
    private var savedUrl: String? = null
    private var savedToken: String? = null
    private var preferencesSaved = false
    private var task = 0L

    @Before fun setUp() = runBlocking {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("analysis_proxy", Context.MODE_PRIVATE)
        savedUrl = prefs.getString("url", null)
        savedToken = prefs.getString("token", null)
        preferencesSaved = true
        server = LoopbackProxy()
        AnalysisSettings(context).save(server.url, "test-only-${UUID.randomUUID()}")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fixture = Sprint1Fixture(db).also { it.seed() }
        task = fixture.task()
        db.taskDao().markClassStart(task, 1_000)
        db.taskDao().markClassEnd(task, 301_000)
        pipeline = CloudAnalysisPipeline(db, context)
    }

    @After fun tearDown() {
        if (::server.isInitialized) server.close()
        if (::db.isInitialized) db.close()
        if (preferencesSaved) {
            val edit = context.getSharedPreferences("analysis_proxy", Context.MODE_PRIVATE).edit()
            savedUrl?.let { edit.putString("url", it) } ?: edit.remove("url")
            savedToken?.let { edit.putString("token", it) } ?: edit.remove("token")
            assertTrue("restore proxy settings", edit.commit())
        }
        if (::server.isInitialized) server.assertHealthy()
    }

    @Test fun missingJobIdAndHttp500RetryTheSameLocalJobAndIdempotencyKey() = runBlocking {
        server.enqueue(200, JSONObject())
        val id = pipeline.start(fixture.supervisor, task, AnalysisOperation.CONTENT)
        val firstRequest = server.takeRequest()
        val initial = requireNotNull(db.analysisSourceDao().getJob(id))
        assertEquals(JobState.QUEUED, initial.state)
        assertNull(initial.serverJobId)
        assertNotNull(initial.errorCode)

        server.enqueue(500, JSONObject().put("error", JSONObject().put("code", "TEMPORARY_FAILURE")))
        assertEquals(JobState.QUEUED, pipeline.refresh(fixture.supervisor, id))
        val secondRequest = server.takeRequest()
        val afterFailure = requireNotNull(db.analysisSourceDao().getJob(id))
        assertEquals(initial.clientRequestId, afterFailure.clientRequestId)
        assertNull(afterFailure.serverJobId)

        server.enqueue(200, JSONObject().put("jobId", "recovered-job"))
        assertEquals(JobState.QUEUED, pipeline.refresh(fixture.supervisor, id))
        val thirdRequest = server.takeRequest()
        val recovered = requireNotNull(db.analysisSourceDao().getJob(id))
        assertEquals("recovered-job", recovered.serverJobId)
        assertNull(recovered.errorCode)
        assertEquals(listOf(id), db.analysisSourceDao().jobsForTask(task).map { it.id })
        val requests = listOf(firstRequest, secondRequest, thirdRequest)
        requests.forEach { request ->
            assertEquals("POST", request.method)
            assertEquals("/v1/analysis-jobs", request.path)
            assertEquals(initial.clientRequestId, request.headers["idempotency-key"])
            assertEquals(initial.inputFingerprint, JSONObject(request.body).getString("inputFingerprint"))
            assertEquals("CONTENT", JSONObject(request.body).getString("operation"))
            assertEquals("CONTEXT", JSONObject(request.body).getJSONArray("inputs").getJSONObject(0).getString("kind"))
        }
        assertEquals(1, requests.map { JSONObject(it.body).getString("caseId") }.distinct().size)
    }

    @Test fun cancellationWinsOverAnAlreadyInFlightSuccessfulResponse() = runBlocking {
        val id = submittedJob("cancel-race")
        val releaseSuccess = CountDownLatch(1)
        server.enqueue(200, success(id), releaseSuccess)
        val lateRefresh = async(Dispatchers.Default) { pipeline.refresh(fixture.supervisor, id) }
        assertEquals("GET", server.takeRequest().method)
        try {
            server.enqueue(200, JSONObject().put("state", "CANCELLED"))
            pipeline.cancel(fixture.supervisor, id)
            val cancel = server.takeRequest()
            assertEquals("POST", cancel.method)
            assertEquals("/v1/analysis-jobs/cancel-race/cancel", cancel.path)
            assertEquals(JobState.CANCELLED, db.analysisSourceDao().getJob(id)!!.state)
        } finally {
            releaseSuccess.countDown()
        }
        assertEquals(JobState.CANCELLED, withTimeout(15_000) { lateRefresh.await() })
        val saved = db.analysisSourceDao().getJob(id)!!
        assertEquals(JobState.CANCELLED, saved.state)
        assertNull(saved.resultJson)
        assertTrue(db.aiAnalysisDao().getByTask(task).isEmpty())
    }

    @Test fun delayedRunningResponseCannotRegressAnAlreadySavedSuccess() = runBlocking {
        val id = submittedJob("running-race")
        val releaseRunning = CountDownLatch(1)
        server.enqueue(200, JSONObject().put("state", "RUNNING").put("stage", "late running"), releaseRunning)
        val olderRefresh = async(Dispatchers.Default) { pipeline.refresh(fixture.supervisor, id) }
        assertEquals("GET", server.takeRequest().method)
        try {
            server.enqueue(200, success(id))
            assertEquals(JobState.SUCCEEDED, pipeline.refresh(fixture.supervisor, id))
            assertEquals("GET", server.takeRequest().method)
        } finally {
            releaseRunning.countDown()
        }
        assertEquals(JobState.SUCCEEDED, withTimeout(15_000) { olderRefresh.await() })
        val saved = db.analysisSourceDao().getJob(id)!!
        assertEquals(JobState.SUCCEEDED, saved.state)
        assertEquals("已保存，待人工复核", saved.stage)
        assertNotNull(saved.resultJson)
        val analysis = db.aiAnalysisDao().getByTask(task).single()
        assertEquals(id.toString(), analysis.runId)
        assertEquals(ResultState.INSUFFICIENT_INPUT, analysis.resultState)
        assertTrue(analysis.isCurrent)
        assertEquals(0, JSONObject(analysis.payloadJson!!).getJSONArray("topics").length())
        val requestCount = server.requestCount
        assertEquals(JobState.SUCCEEDED, pipeline.refresh(fixture.supervisor, id))
        assertEquals(requestCount, server.requestCount)
        assertEquals(analysis.id, db.aiAnalysisDao().getByTask(task).single().id)
    }

    private suspend fun submittedJob(serverId: String): Long {
        server.enqueue(200, JSONObject().put("jobId", serverId))
        val id = pipeline.start(fixture.supervisor, task, AnalysisOperation.CONTENT)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(serverId, db.analysisSourceDao().getJob(id)!!.serverJobId)
        return id
    }

    private suspend fun success(id: Long): JSONObject = JSONObject().put("state", "SUCCEEDED")
        .put("inputFingerprint", db.analysisSourceDao().getJob(id)!!.inputFingerprint)
        .put("result", JSONObject().put("resultState", "INSUFFICIENT_INPUT")
            .put("payload", JSONObject().put("topics", JSONArray()).put("note", "No classroom transcript supplied")))

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    private data class Response(val status: Int, val body: String, val release: CountDownLatch?)

    /** Each socket has a worker so an intentionally delayed GET cannot block a cancellation/second GET. */
    private class LoopbackProxy : Closeable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "analysis-test-http").apply { isDaemon = true } }
        private val responses = LinkedBlockingQueue<Response>()
        private val requests = LinkedBlockingQueue<Request>()
        private val allRequests = Collections.synchronizedList(mutableListOf<Request>())
        private val openSockets = Collections.synchronizedList(mutableListOf<Socket>())
        private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        @Volatile private var closed = false
        val url = "http://127.0.0.1:${socket.localPort}"
        val requestCount: Int get() = allRequests.size
        private val acceptThread = Thread({
            while (!closed) {
                try {
                    val client = socket.accept()
                    openSockets.add(client)
                    workers.execute { serve(client) }
                } catch (error: Exception) {
                    if (!closed) failures.add(error)
                }
            }
        }, "analysis-test-accept").apply { isDaemon = true; start() }

        fun enqueue(status: Int, body: JSONObject, release: CountDownLatch? = null) {
            responses.put(Response(status, body.toString(), release))
        }

        suspend fun takeRequest(): Request = withContext(Dispatchers.IO) {
            requireNotNull(requests.poll(10, TimeUnit.SECONDS)) { "Expected a loopback HTTP request" }
        }

        private fun serve(client: Socket) {
            try {
                client.use { connection ->
                    connection.soTimeout = 10_000
                    val input = connection.getInputStream()
                    val requestLine = line(input).split(' ')
                    require(requestLine.size >= 2)
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line(input)
                        if (header.isEmpty()) break
                        val separator = header.indexOf(':')
                        require(separator > 0)
                        headers[header.substring(0, separator).lowercase(Locale.ROOT)] = header.substring(separator + 1).trim()
                    }
                    require(headers["transfer-encoding"] == null) { "This fixture expects fixed JSON request bodies" }
                    val length = headers["content-length"]?.toInt() ?: 0
                    require(length in 0..1_000_000)
                    val body = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val count = input.read(body, read, length - read)
                        require(count > 0)
                        read += count
                    }
                    val request = Request(requestLine[0], requestLine[1], headers, body.toString(Charsets.UTF_8))
                    val response = requireNotNull(responses.poll(10, TimeUnit.SECONDS)) { "Unexpected loopback request ${request.method} ${request.path}" }
                    allRequests.add(request)
                    requests.put(request)
                    require(response.release?.await(10, TimeUnit.SECONDS) != false) { "Delayed fixture response was not released" }
                    val bytes = response.body.toByteArray(Charsets.UTF_8)
                    connection.getOutputStream().use { output ->
                        output.write(("HTTP/1.1 ${response.status} ${if (response.status == 200) "OK" else "Server Error"}\r\n" +
                            "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (error: Throwable) {
                if (!closed) failures.add(error)
            } finally {
                openSockets.remove(client)
            }
        }

        private fun line(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val next = input.read()
                require(next >= 0) { "Incomplete HTTP headers" }
                if (next == 10) break
                if (next != 13) bytes.write(next)
                require(bytes.size() < 16_384)
            }
            return bytes.toString(Charsets.US_ASCII.name())
        }

        override fun close() {
            closed = true
            socket.close()
            synchronized(openSockets) { openSockets.toList() }.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptThread.join(1_000)
            workers.awaitTermination(1, TimeUnit.SECONDS)
        }

        fun assertHealthy() {
            assertTrue("Loopback fixture failed: ${failures.firstOrNull()?.javaClass?.simpleName}", failures.isEmpty())
        }
    }
}
