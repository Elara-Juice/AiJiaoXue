package edu.neu.aijiaoxue.ui.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import edu.neu.aijiaoxue.R

/**
 * 录音期间的前台服务，只负责让进程保持前台、显示“正在录音”通知；录音本身在 [AudioRecorder] 里。
 * Android 11 起，后台应用的麦克风会被静音，没有它锁屏或切去相机后录到的都是空白（NF-04）。
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "课堂录音", NotificationManager.IMPORTANCE_LOW)
        )
        // 点通知回到应用当前页面，不新建页面
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在录制课堂音频")
            .setContentText("回到爱教学可暂停或结束录音")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        // 进程被杀后不自动重启：录音已经断了，重启服务也录不回来
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
    }
}
