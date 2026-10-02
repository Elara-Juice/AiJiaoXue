package edu.neu.aijiaoxue.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课堂材料文件统一放在 filesDir/tasks/{taskId}/{audio|photo}/ 下。
 * Material.filePath 只存相对 filesDir 的路径，不存绝对路径。
 * 文件名：AUD_yyyyMMdd_HHmmss.m4a、IMG_yyyyMMdd_HHmmss.jpg，同秒冲突时追加 _1、_2。
 */
object FileStore {
    const val AUDIO_DIR = "audio"
    const val PHOTO_DIR = "photo"

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

    private fun newFile(context: Context, taskId: Long, sub: String, base: String, ext: String): Pair<File, String> {
        val dir = File(taskDir(context, taskId), sub).apply { mkdirs() }
        var name = "$base.$ext"
        var i = 1
        while (File(dir, name).exists()) name = "${base}_${i++}.$ext"
        return File(dir, name) to "tasks/$taskId/$sub/$name"
    }
}
