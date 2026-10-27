package edu.neu.aijiaoxue.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课堂材料文件统一放在 filesDir/tasks/{taskId}/{audio|photo}/ 下。
 * Material.filePath 只存相对 filesDir 的路径，不存绝对路径。
 * 文件名：AUD_yyyyMMdd_HHmmss.m4a、IMG_yyyyMMdd_HHmmss.jpg，同秒冲突时追加 _1、_2。
 */
object FileStore {
    const val AUDIO_DIR = "audio"
    const val PHOTO_DIR = "photo"
    const val IMPROVEMENT_DIR = "improvement"

    private fun stamp(at: Long) = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(at))

    fun taskDir(context: Context, taskId: Long): File = File(context.filesDir, "tasks/$taskId")

    /** 新建音频文件，返回 (文件, 相对路径)。 */
    fun newAudioFile(context: Context, taskId: Long, at: Long = System.currentTimeMillis()) =
        newFile(context, taskId, AUDIO_DIR, "AUD_${stamp(at)}", "m4a")

    fun newPhotoFile(context: Context, taskId: Long, at: Long = System.currentTimeMillis()) =
        newFile(context, taskId, PHOTO_DIR, "IMG_${stamp(at)}", "jpg")

    fun resolve(context: Context, relativePath: String): File = File(context.filesDir, relativePath)

    /** US07：文件丢失时界面显示“文件缺失”。 */
    fun exists(context: Context, relativePath: String): Boolean = resolve(context, relativePath).exists()

    /** US11：删除材料时先删文件再删记录。 */
    fun delete(context: Context, relativePath: String): Boolean {
        val f = resolve(context, relativePath)
        return !f.exists() || f.delete()
    }

    /** US43：每次导入使用独立临时文件，重试不会覆盖已保存证据。 */
    suspend fun newImprovementTempFile(context: Context, taskId: Long, executionId: Long): Pair<File, String> =
        withContext(Dispatchers.IO) {
            val dir = improvementDirectory(context, taskId, executionId, create = true)
            val file = File.createTempFile("pending_", ".tmp", dir)
            file to "tasks/$taskId/$IMPROVEMENT_DIR/$executionId/${file.name}"
        }

    /** 返回未占用的目标路径；调用方完成复制后移动，不能直接使用外部文件名。 */
    suspend fun newImprovementEvidenceFile(
        context: Context,
        taskId: Long,
        executionId: Long,
        extension: String,
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        require(extension in setOf("jpg", "png", "pdf")) { "不支持的实施证据文件类型" }
        val dir = improvementDirectory(context, taskId, executionId, create = true)
        var file: File
        do {
            file = File(dir, "EVD_${UUID.randomUUID()}.$extension")
        } while (file.exists())
        file to "tasks/$taskId/$IMPROVEMENT_DIR/$executionId/${file.name}"
    }

    /** US43：即使数据库路径被篡改，也只能解析当前课堂与实施记录的直接子文件。 */
    suspend fun resolveImprovementEvidence(
        context: Context,
        taskId: Long,
        executionId: Long,
        relativePath: String,
    ): File = withContext(Dispatchers.IO) {
        val dir = improvementDirectory(context, taskId, executionId, create = false)
        val prefix = "tasks/$taskId/$IMPROVEMENT_DIR/$executionId/"
        require(!File(relativePath).isAbsolute && relativePath.startsWith(prefix)) { "实施证据路径无效" }
        val name = relativePath.removePrefix(prefix)
        require(name.isNotBlank() && '/' !in name && '\\' !in name) { "实施证据路径无效" }
        val file = File(context.filesDir, relativePath).canonicalFile
        require(file.parentFile == dir) { "实施证据路径超出授权目录" }
        file
    }

    suspend fun deleteImprovementEvidence(
        context: Context,
        taskId: Long,
        executionId: Long,
        relativePath: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val file = resolveImprovementEvidence(context, taskId, executionId, relativePath)
        !file.exists() || file.delete()
    }

    // 仅从上述 IO 调度方法进入；固定数字 ID 的目录不得通过符号链接逃逸。
    private fun improvementDirectory(context: Context, taskId: Long, executionId: Long, create: Boolean): File {
        require(taskId > 0 && executionId > 0) { "实施证据所属记录无效" }
        val root = context.filesDir.canonicalFile
        val expected = File(root, "tasks/$taskId/$IMPROVEMENT_DIR/$executionId")
        val dir = expected.canonicalFile
        require(dir == expected.absoluteFile) { "实施证据目录无效" }
        if (create) {
            // 并发导入可能在首次检查后创建同一目录；mkdirs 返回 false 时需重查。
            check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "无法创建实施证据目录" }
        }
        require(!dir.exists() || dir.isDirectory) { "实施证据目录无效" }
        return dir
    }

    private fun newFile(context: Context, taskId: Long, sub: String, base: String, ext: String): Pair<File, String> {
        val dir = File(taskDir(context, taskId), sub).apply { mkdirs() }
        var name = "$base.$ext"
        var i = 1
        while (File(dir, name).exists()) name = "${base}_${i++}.$ext"
        return File(dir, name) to "tasks/$taskId/$sub/$name"
    }
}
