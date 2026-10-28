package edu.neu.aijiaoxue.ui.capture

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

data class ImportedAudioInfo(val extension: String, val durationMs: Long)

/** Local format preflight; the proxy still performs full decode validation before ASR. */
object AudioImportInspector {
    fun inspect(file: File): ImportedAudioInfo {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        val extension = detectExtension(head.copyOf(maxOf(0, read)))
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            require(tracks.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) { "文件不包含有效音轨" }
            require(tracks.none { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) { "仅支持音频文件，不支持视频" }
        } finally { extractor.release() }
        val metadata = MediaMetadataRetriever()
        val duration = try {
            metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally { metadata.release() }
        require(duration != null && duration in 1..7_200_000) { "无法取得有效时长，或音频超过2小时" }
        return ImportedAudioInfo(extension, duration)
    }

    fun detectExtension(header: ByteArray): String {
        fun text(start: Int, end: Int) = if (header.size >= end) String(header, start, end - start, Charsets.US_ASCII) else ""
        return when {
            text(0, 4) == "RIFF" && text(8, 12) == "WAVE" -> "wav"
            text(4, 8) == "ftyp" -> "m4a"
            text(0, 3) == "ID3" || (header.size >= 2 && header[0].toInt() and 255 == 255 && header[1].toInt() and 224 == 224) -> "mp3"
            else -> error("仅支持真实WAV、M4A或MP3音频，请勿只修改扩展名")
        }
    }
}
