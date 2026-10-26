package edu.neu.aijiaoxue.ui.improve

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.entity.ImprovementEvidence
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class OpenedEvidence(val file: File, val mimeType: String, val displayName: String)

/** US43：导入选中的资料到应用私有目录，不保存可能失效的临时 URI。 */
class ImprovementEvidenceStore(
    context: Context,
    private val repository: ImprovementExecutionRepository,
) {
    private val appContext = context.applicationContext

    suspend fun importEvidence(
        actorId: Long,
        executionId: Long,
        uri: Uri,
        description: String,
        requestKey: String,
    ): Long = withContext(Dispatchers.IO) {
        // 在查询外部提供者或创建文件之前授权；Repository 落库时还会重新核对。
        val access = repository.requireExecution(actorId, executionId)
        require(requestKey.isNotBlank()) { "缺少证据保存请求标识，请重新选择文件" }
        require(uri.scheme == "content" || uri.scheme == "file") { "请选择本机图片或 PDF 资料" }
        var temporaryPath: String? = null
        var completedPath: String? = null
        var referencedByDatabase = false
        try {
            val displayName = readDisplayName(uri)
            val (temporaryFile, relativeTemporaryPath) =
                FileStore.newImprovementTempFile(appContext, access.taskId, executionId)
            temporaryPath = relativeTemporaryPath
            val byteSize = copySource(uri, temporaryFile)
            val fileType = detectFileType(temporaryFile)
            val (completedFile, relativeCompletedPath) =
                FileStore.newImprovementEvidenceFile(appContext, access.taskId, executionId, fileType.extension)
            currentCoroutineContext().ensureActive()
            // 同目录移动不使用 REPLACE_EXISTING，绝不覆盖另一次请求的文件。
            Files.move(temporaryFile.toPath(), completedFile.toPath())
            completedPath = relativeCompletedPath
            temporaryPath = null
            currentCoroutineContext().ensureActive()

            // Room 与文件不是一个事务。提交后即使调用者取消，也先确定文件归属再清理。
            withContext(NonCancellable) {
                val evidenceId = repository.addEvidence(
                    actorId,
                    executionId,
                    ImprovementEvidence(
                        executionId = executionId,
                        relativePath = relativeCompletedPath,
                        displayName = displayName,
                        mimeType = fileType.mimeType,
                        byteSize = byteSize,
                        description = description.trim(),
                        createdAt = System.currentTimeMillis(),
                        requestKey = requestKey.trim(),
                    ),
                )
                // addEvidence 可能已经插入；后续读取失败时也不能删除其引用的文件。
                referencedByDatabase = true
                val stored = repository.requireEvidence(actorId, evidenceId)
                if (stored.evidence.relativePath != relativeCompletedPath) {
                    // 同 requestKey 复用原记录：只清理本次新文件，保留首次成功的文件。
                    referencedByDatabase = false
                    val originalFile = FileStore.resolveImprovementEvidence(
                        appContext,
                        stored.taskId,
                        stored.evidence.executionId,
                        stored.evidence.relativePath,
                    )
                    check(originalFile.isFile && originalFile.canRead()) { "原证据文件缺失，不能将重试当作保存成功" }
                    require(
                        stored.evidence.executionId == executionId &&
                            stored.evidence.mimeType == fileType.mimeType &&
                            stored.evidence.byteSize == byteSize &&
                            originalFile.length() == byteSize &&
                            stored.evidence.description == description.trim() &&
                            MessageDigest.isEqual(sha256(originalFile), sha256(completedFile)),
                    ) { "同一请求不能保存不同的证据，请重新选择文件" }
                }
                evidenceId
            }
        } finally {
            // 取消与失败都清理由本次请求独占的文件；已被数据库引用的文件必须保留。
            withContext(NonCancellable) {
                temporaryPath?.let { FileStore.deleteImprovementEvidence(appContext, access.taskId, executionId, it) }
                if (!referencedByDatabase) {
                    completedPath?.let { FileStore.deleteImprovementEvidence(appContext, access.taskId, executionId, it) }
                }
            }
        }
    }

    suspend fun openEvidence(actorId: Long, evidenceId: Long): OpenedEvidence = withContext(Dispatchers.IO) {
        val access = repository.requireEvidence(actorId, evidenceId)
        val evidence = access.evidence
        val file = FileStore.resolveImprovementEvidence(appContext, access.taskId, evidence.executionId, evidence.relativePath)
        if (!file.isFile || !file.canRead()) throw IOException("实施证据文件缺失或不可读取，请重新补充证据")
        if (file.length() != evidence.byteSize || file.length() > MAX_EVIDENCE_BYTES) {
            throw IOException("实施证据文件大小异常，无法打开")
        }
        if (detectFileType(file).mimeType != evidence.mimeType) throw IOException("实施证据文件类型与记录不一致")
        OpenedEvidence(file, evidence.mimeType, evidence.displayName)
    }

    private fun readDisplayName(uri: Uri): String {
        val providerName = try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 部分文件提供者不实现名称查询；文件内容仍由后续实际读取和签名校验决定。
            null
        }
        return (providerName ?: uri.lastPathSegment ?: "实施证据")
            .substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() }.trim().take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "实施证据" }
    }

    private suspend fun copySource(uri: Uri, destination: File): Long {
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (error: SecurityException) {
            throw IOException("无法读取所选文件，请重新选择并授权", error)
        } ?: throw IOException("无法读取所选文件，请重新选择")
        var copied = 0L
        input.use { source ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    if (copied > MAX_EVIDENCE_BYTES) throw IOException("实施证据不能超过 10 MiB")
                    output.write(buffer, 0, read)
                }
            }
        }
        if (copied == 0L) throw IOException("所选文件为空，无法作为实施证据")
        return copied
    }

    private fun detectFileType(file: File): EvidenceFileType {
        val signature = ByteArray(8)
        val count = file.inputStream().use { input ->
            var total = 0
            while (total < signature.size) {
                val read = input.read(signature, total, signature.size - total)
                if (read < 0) break
                total += read
            }
            total
        }
        return when {
            count >= 3 && signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() &&
                signature[2] == 0xFF.toByte() -> EvidenceFileType("image/jpeg", "jpg")
            count == 8 && signature.contentEquals(PNG_SIGNATURE) -> EvidenceFileType("image/png", "png")
            count == 8 && signature.copyOfRange(0, 5).contentEquals(PDF_SIGNATURE) &&
                signature[5].toInt().toChar() in '1'..'2' && signature[6] == '.'.code.toByte() &&
                signature[7].toInt().toChar() in '0'..'9' -> EvidenceFileType("application/pdf", "pdf")
            else -> throw IOException("文件内容不受支持，仅可导入 JPEG、PNG 图片或 PDF 教学资料")
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private data class EvidenceFileType(val mimeType: String, val extension: String)

    companion object {
        const val MAX_EVIDENCE_BYTES = ImprovementExecutionRepository.MAX_EVIDENCE_BYTES
        private const val MAX_DISPLAY_NAME_LENGTH = 160
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
