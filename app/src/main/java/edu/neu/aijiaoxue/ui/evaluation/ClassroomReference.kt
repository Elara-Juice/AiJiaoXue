package edu.neu.aijiaoxue.ui.evaluation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 路径也限定在当前任务目录；损坏数据不能使只读查看器打开其他任务文件。 */
fun classroomFile(context: Context, taskId: Long, path: String): File? = runCatching {
    val file = FileStore.resolve(context, path).canonicalFile
    val directory = FileStore.taskDir(context, taskId).canonicalFile
    file.takeIf { it.isFile && it.toPath().startsWith(directory.toPath()) }
}.getOrNull()

@Composable
fun ClassroomReference(record: ClassroomRecord, initiallyExpanded: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var expanded by remember(record.task.id) { mutableStateOf(initiallyExpanded) }
    var photo by remember { mutableStateOf<Material?>(null) }
    var audio by remember { mutableStateOf<Material?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(audio?.id) {
        val selected = audio
        val player = selected?.let { MediaPlayer() }
        if (selected != null && player != null) {
            try {
                val file = requireNotNull(classroomFile(context, record.task.id, selected.filePath)) { "文件缺失或不可访问" }
                player.setDataSource(file.absolutePath)
                player.setOnPreparedListener { it.start(); playing = true }
                player.setOnCompletionListener { audio = null; playing = false }
                player.setOnErrorListener { _, _, _ -> error = "音频无法播放，文件可能损坏"; audio = null; true }
                player.prepareAsync()
            } catch (e: Exception) { error = e.message ?: "音频无法播放"; audio = null }
        }
        onDispose { player?.release(); playing = false }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { audio = null; photo = null }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    SectionCard("课堂参考材料（只读）") {
        Text("材料 ${record.materials.size} 份 · 互动 ${record.events.size} 次 · 参与记录 ${record.engagements.size} 条")
        TextButton(onClick = { expanded = !expanded; if (!expanded) audio = null }) {
            Text(if (expanded) "收起课堂记录" else "展开课堂记录")
        }
        if (expanded) {
            if (record.materials.isEmpty()) Text("暂无课堂材料")
            record.materials.forEachIndexed { index, material ->
                HorizontalDivider()
                Text("材料 ${index + 1} · ${material.type.label}", style = MaterialTheme.typography.titleSmall)
                Text("采集：${timeText(material.capturedAt)} · 课堂偏移 ${material.offsetMs / 1000} 秒")
                if (material.type == MaterialType.AUDIO) {
                    Text("结束：${timeText(material.endedAt)} · 有效时长 ${material.durationMs?.let { "${it / 1000}秒" } ?: "未记录"}")
                } else if (material.isImported) Text("来自相册导入")
                material.note?.let { ReadField("备注", it) }
                val file = classroomFile(context, record.task.id, material.filePath)
                if (file == null) Text("文件缺失或不可访问", color = MaterialTheme.colorScheme.error)
                else TextButton(onClick = {
                    error = null
                    if (material.type == MaterialType.AUDIO) audio = if (audio?.id == material.id) null else material
                    else photo = material
                }) {
                    Text(if (material.type != MaterialType.AUDIO) "查看图片 ${index + 1}"
                        else if (audio?.id == material.id) { if (playing) "停止播放" else "正在准备音频…" } else "播放音频 ${index + 1}")
                }
            }
            error?.let { MessageCard(it, true) }
            HorizontalDivider()
            Text("互动记录", style = MaterialTheme.typography.titleSmall)
            if (record.events.isEmpty()) Text("暂无互动记录")
            record.events.groupingBy { it.type }.eachCount().forEach { (type, count) -> Text("${type.label}：$count 次") }
            record.events.forEach { event ->
                Text("${timeText(event.occurredAt)} · +${event.offsetMs / 1000}秒 · ${event.type.label}")
                event.note?.let { ReadField("备注", it) }
            }
            HorizontalDivider()
            Text("学生参与记录", style = MaterialTheme.typography.titleSmall)
            if (record.engagements.isEmpty()) Text("暂无参与状态记录")
            record.engagements.forEach { row ->
                Text("${timeText(row.recordedAt)} · +${row.offsetMs / 1000}秒 · ${row.type.label}")
                Text("实到 ${row.presentCount ?: "未记录"} 人 · 参与 ${row.engagedCount?.let { "$it 人" } ?: row.engagedPercent?.let { "$it%" } ?: "未记录"}")
                ReadField("描述", row.description)
            }
        }
    }
    photo?.let { material ->
        val imageState by produceState<Pair<Boolean, Bitmap?>>(initialValue = true to null, material.id) {
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    val file = requireNotNull(classroomFile(context, record.task.id, material.filePath))
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeFile(file.path, options)
                }.getOrNull()
            }
            value = false to decoded
        }
        Dialog(onDismissRequest = { photo = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text("${material.type.label} · ${timeText(material.capturedAt)}")
                    imageState.second?.let { Image(it.asImageBitmap(), contentDescription = "课堂材料图片",
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) }
                        ?: Text(if (imageState.first) "正在加载图片…" else "图片无法显示：文件缺失或损坏")
                    TextButton(onClick = { photo = null }) { Text("关闭图片") }
                }
            }
        }
    }
}
