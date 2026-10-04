package edu.neu.aijiaoxue.ui.capture

import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** US08 主流程 4：在材料列表回放音频。同一时间只播放一段。 */
class AudioPlayer {
    var playingId by mutableStateOf<Long?>(null)
        private set

    private var player: MediaPlayer? = null

    /** 点同一段停止，点另一段切换。文件无法播放时返回 false。 */
    fun toggle(id: Long, file: File): Boolean {
        if (playingId == id) {
            stop()
            return true
        }
        stop()
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                prepare()
                start()
            }
            playingId = id
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        player?.release()
        player = null
        playingId = null
    }
}
