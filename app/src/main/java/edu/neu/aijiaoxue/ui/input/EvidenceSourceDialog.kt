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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import edu.neu.aijiaoxue.data.model.EvidencePlaybackStatus

@Composable
fun EvidenceSourceDialog(source: ResolvedEvidenceSource, onClose: () -> Unit) {
    var player by remember(source) { mutableStateOf<MediaPlayer?>(null) }
    var playbackError by remember(source) { mutableStateOf<String?>(null) }
    var playback by remember(source) { mutableStateOf(EvidencePlaybackStatus.READY) }
    var position by remember(source) { mutableLongStateOf(0L) }
    var duration by remember(source) { mutableLongStateOf(0L) }
    var pendingSeek by remember(source) { mutableStateOf<Float?>(null) }
    LaunchedEffect(source, playback) {
        while (playback == EvidencePlaybackStatus.PLAYING) {
            player?.let { active ->
                position = runCatching { active.currentPosition.toLong() }.getOrDefault(position)
                val end = (source as? ResolvedEvidenceSource.Audio)?.mediaEndMs
                if (end != null && position >= end) {
                    runCatching { active.pause() }
                    playback = EvidencePlaybackStatus.FINISHED
                }
            }
            delay(150)
        }
    }
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
                    Text("文件时间 ${inputTime(if (duration == 0L) source.mediaStartMs else position)}" + (source.mediaEndMs?.let { " / 本句结束 ${inputTime(it)}" } ?: ""))
                    if (playback == EvidencePlaybackStatus.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (duration > 0) Slider(value = pendingSeek ?: position.toFloat(), onValueChange = { pendingSeek = it },
                        onValueChangeFinished = { pendingSeek?.let { player?.seekTo(it.toLong(), MediaPlayer.SEEK_CLOSEST); position = it.toLong() }; pendingSeek = null },
                        valueRange = source.mediaStartMs.toFloat()..(source.mediaEndMs ?: duration).coerceAtMost(duration).coerceAtLeast(source.mediaStartMs + 1).toFloat())
                    Row {
                        TextButton(onClick = {
                            try {
                                playbackError = null
                                if (player != null && playback == EvidencePlaybackStatus.PAUSED) {
                                    player?.start(); playback = EvidencePlaybackStatus.PLAYING
                                    return@TextButton
                                }
                                player?.release()
                                playback = EvidencePlaybackStatus.LOADING
                                player = MediaPlayer().apply {
                                    setDataSource(source.file.absolutePath)
                                    setOnPreparedListener { duration = it.duration.toLong(); it.seekTo(source.mediaStartMs, MediaPlayer.SEEK_CLOSEST) }
                                    setOnSeekCompleteListener { if (playback == EvidencePlaybackStatus.LOADING) { it.start(); playback = EvidencePlaybackStatus.PLAYING } }
                                    setOnCompletionListener { playback = EvidencePlaybackStatus.FINISHED }
                                    setOnErrorListener { _, _, _ -> playbackError = "原音频无法播放，请重试或检查文件"; playback = EvidencePlaybackStatus.FAILED; true }
                                    prepareAsync()
                                }
                            } catch (e: Exception) { playbackError = "无法打开原音频"; playback = EvidencePlaybackStatus.FAILED }
                        }, enabled = playback !in setOf(EvidencePlaybackStatus.LOADING, EvidencePlaybackStatus.PLAYING)) { Text(when (playback) { EvidencePlaybackStatus.PAUSED -> "继续播放"; EvidencePlaybackStatus.FINISHED -> "重播本句"; EvidencePlaybackStatus.FAILED -> "重试播放"; else -> "从此处播放" }) }
                        TextButton(onClick = { runCatching { player?.pause() }; playback = EvidencePlaybackStatus.PAUSED }, enabled = playback == EvidencePlaybackStatus.PLAYING) { Text("暂停") }
                    }
                    playbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                is ResolvedEvidenceSource.Detail -> { Text(source.title, style = MaterialTheme.typography.titleSmall); Text(source.text) }
                is ResolvedEvidenceSource.SummaryOnly -> { Text(source.summary); Text(source.reason, color = MaterialTheme.colorScheme.secondary) }
            }
        }
    })
}
