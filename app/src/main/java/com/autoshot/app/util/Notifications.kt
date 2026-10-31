package com.autoshot.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.autoshot.app.R
import com.autoshot.app.camera.CaptureService
import com.autoshot.app.ui.MainActivity

object Notifications {

    const val CHANNEL_CAPTURE = "capture"
    const val CAPTURE_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val capture = NotificationChannel(
            CHANNEL_CAPTURE,
            context.getString(R.string.notif_channel_capture),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "应用正在后台使用摄像头拍照并上传到你自己的服务器"
            setShowBadge(false)
        }

        nm.createNotificationChannel(capture)
    }

    /**
     * 前台服务通知。按规范必须是「用户可见、不可静默隐藏」的常驻通知，
     * 不做隐藏图标 / 伪装通知等规避系统提示的处理。
     */
    fun captureNotification(context: Context, text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, CaptureService::class.java).setAction(CaptureService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_dog)
            .setContentTitle("小狗")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
