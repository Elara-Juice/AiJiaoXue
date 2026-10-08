package edu.neu.aijiaoxue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import edu.neu.aijiaoxue.data.FileStore
import edu.neu.aijiaoxue.data.entity.Material
import edu.neu.aijiaoxue.data.model.MaterialType
import java.io.File
import kotlin.math.sin

/** 自制课堂图片与AAC测试音；不下载真实课堂数据、不申请麦克风权限。 */
object Sprint1MediaFixture {
    suspend fun create(context: Context, fixture: Sprint1Fixture, taskId: Long): List<File> {
        val (photo, photoPath) = FileStore.newPhotoFile(context, taskId)
        val bitmap = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(245, 247, 250))
            val paint = Paint().apply { color = Color.rgb(30, 50, 80); textSize = 36f; isAntiAlias = true }
            drawText("Data Structures - Classroom Test", 40f, 100f, paint)
            drawText("Binary tree: root / left / right", 40f, 210f, paint)
            drawText("Synthetic fixture - no real student data", 40f, 330f, paint)
        }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val (audio, audioPath) = FileStore.newAudioFile(context, taskId)
        encodeTone(audio)
        val now = System.currentTimeMillis()
        fixture.db.captureDao().insertMaterial(Material(taskId = taskId, type = MaterialType.PPT, filePath = photoPath,
            capturedAt = now, offsetMs = 0, note = "自制图片测试素材"))
        fixture.db.captureDao().insertMaterial(Material(taskId = taskId, type = MaterialType.AUDIO, filePath = audioPath,
            capturedAt = now, endedAt = now + 30_000, durationMs = 30_000, offsetMs = 0, note = "30秒合成测试音"))
        val (board, boardPath) = FileStore.newPhotoFile(context, taskId)
        val boardBitmap = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888)
        Canvas(boardBitmap).apply {
            drawColor(Color.rgb(25, 62, 47))
            val chalk = Paint().apply { color = Color.WHITE; textSize = 40f; isAntiAlias = true }
            drawText("Classroom Discussion", 45f, 100f, chalk)
            drawText("Observe -> Ask -> Discuss", 45f, 225f, chalk)
            drawText("Synthetic blackboard fixture", 45f, 350f, chalk)
        }
        board.outputStream().use { boardBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; boardBitmap.recycle()
        fixture.db.captureDao().insertMaterial(Material(taskId = taskId, type = MaterialType.BLACKBOARD, filePath = boardPath,
            capturedAt = now + 5_000, offsetMs = 5_000, note = "自制板书测试素材"))
        return listOf(photo, audio, board)
    }
    private fun encodeTone(file: File) {
        val sampleRate = 44100
        val totalSamples = sampleRate * 30
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            var inputDone = false; var outputDone = false; var sample = 0; var track = -1
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!outputDone) {
                check(System.nanoTime() < deadline) { "AAC fixture encoding timed out" }
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(input)).apply { clear(); order(java.nio.ByteOrder.LITTLE_ENDIAN) }
                        val count = minOf(buffer.remaining() / 2, totalSamples - sample)
                        repeat(count) { offset -> buffer.putShort((sin(2.0 * Math.PI * 440 * (sample + offset) / sampleRate) * 1200).toInt().toShort()) }
                        inputDone = sample + count >= totalSamples
                        codec.queueInputBuffer(input, 0, count * 2, sample * 1_000_000L / sampleRate,
                            if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        sample += count
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true }
                else if (output >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        check(muxerStarted); muxer.writeSampleData(track, requireNotNull(codec.getOutputBuffer(output)), info)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(output, false)
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            if (muxerStarted) muxer.stop()
            muxer.release()
        }
    }
}
