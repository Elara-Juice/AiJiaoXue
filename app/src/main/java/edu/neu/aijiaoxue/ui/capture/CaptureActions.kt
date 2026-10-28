package edu.neu.aijiaoxue.ui.capture

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import edu.neu.aijiaoxue.data.model.TaskStatus
import edu.neu.aijiaoxue.ui.theme.AppGlyph
import edu.neu.aijiaoxue.ui.theme.AppIcon
import kotlinx.coroutines.delay

/** The recording controls stay visible while browsing classroom evidence. */
@Composable
internal fun CaptureActionBar(
    state: CaptureUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onEndClass: () -> Unit,
    onAnalysis: () -> Unit,
) {
    if (!state.isRecordingHere && state.status != TaskStatus.IN_PROGRESS && state.status != TaskStatus.PENDING_EVALUATION) return
    var elapsedNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isRecordingHere) {
        while (state.isRecordingHere) { elapsedNow = SystemClock.elapsedRealtime(); delay(1_000) }
    }
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.isRecordingHere) {
                val paused = state.recorder.status == AudioRecorder.Status.PAUSED
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${if (paused) "已暂停" else "录音中"} ${formatDuration(state.recorder.durationAt(elapsedNow))}",
                        modifier = Modifier.weight(1f).semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = if (paused) "录音已暂停" else "录音中"
                        },
                        color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                    )
                    TextButton(onClick = if (paused) onResume else onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                        AppIcon(if (paused) AppGlyph.PLAY else AppGlyph.EDIT, tint = MaterialTheme.colorScheme.primary)
                        Text(if (paused) "继续" else "暂停")
                    }
                    OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) {
                        AppIcon(AppGlyph.SEND, tint = MaterialTheme.colorScheme.primary)
                        Text("结束录音")
                    }
                }
            }
            Button(
                onClick = if (state.status == TaskStatus.IN_PROGRESS) onEndClass else onAnalysis,
                enabled = !state.isImportingAudio,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("capture-finish"),
                shape = MaterialTheme.shapes.small,
            ) {
                AppIcon(if (state.status == TaskStatus.IN_PROGRESS) AppGlyph.CHECKLIST else AppGlyph.ANALYTICS, tint = MaterialTheme.colorScheme.onPrimary)
                Text(if (state.status == TaskStatus.IN_PROGRESS) "结束采集并检查材料" else "AI 课堂分析")
            }
        }
    }
}

@Composable
internal fun AudioImportCard(state: CaptureUiState, onImport: () -> Unit, onCancel: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("已有课堂录音", style = MaterialTheme.typography.titleSmall)
            Text("支持 WAV、M4A、MP3。导入音频仅标注文件时间，课堂位置保持未知。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isImportingAudio) {
                val progress = state.audioImportProgress
                if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (progress == null) "正在检查并导入音频…" else "正在复制 ${(progress * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onCancel) { Text("取消导入") }
                }
            } else {
                OutlinedButton(onClick = onImport, enabled = state.canImportAudio, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("capture-import-audio"), shape = MaterialTheme.shapes.small) {
                    AppIcon(AppGlyph.PHOTO, tint = MaterialTheme.colorScheme.primary)
                    Text("导入课堂录音")
                }
            }
        }
    }
}
