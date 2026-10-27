package edu.neu.aijiaoxue.ui.input

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.repository.ResolvedEvidenceSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EvidenceSourceDialog(source: ResolvedEvidenceSource, onClose: () -> Unit) {
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var playbackError by remember(source) { mutableStateOf<String?>(null) }
    DisposableEffect(source) { onDispose { player?.release(); player = null } }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }, title = { Text("课堂来源") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (source) {
                is ResolvedEvidenceSource.Image -> {
                    val bitmap by produceState<android.graphics.Bitmap?>(null, source.file.path) {
                        value = withContext(Dispatchers.IO) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(source.file.path, bounds)
                            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1600) }
                            BitmapFactory.decodeFile(source.file.path, options)
                        }
                    }
                    bitmap?.let { Image(it.asImageBitmap(), "原始课堂图片", Modifier.fillMaxWidth().heightIn(max = 360.dp)) } ?: Text("图片加载中或文件无法解码")
                    Text(source.description)
                }
                is ResolvedEvidenceSource.Audio -> {
                    Text(source.description)
                    Text("定位：文件内 ${source.mediaStartMs / 1000} 秒")
                    Row {
                        TextButton(onClick = {
                            try {
                                player?.release()
                                player = MediaPlayer().apply {
                                    setDataSource(source.file.absolutePath)
                                    setOnPreparedListener { it.seekTo(source.mediaStartMs, MediaPlayer.SEEK_CLOSEST); it.start() }
                                    setOnErrorListener { _, _, _ -> playbackError = "原音频无法播放"; true }
                                    prepareAsync()
                                }
                            } catch (e: Exception) { playbackError = "无法打开原音频" }
                        }) { Text("从此处播放") }
                        TextButton(onClick = { runCatching { player?.pause() } }) { Text("暂停") }
                    }
                    playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                is ResolvedEvidenceSource.Detail -> { Text(source.title, style = MaterialTheme.typography.titleSmall); Text(source.text) }
                is ResolvedEvidenceSource.SummaryOnly -> { Text(source.summary); Text(source.reason, color = MaterialTheme.colorScheme.secondary) }
            }
        }
    })
}
