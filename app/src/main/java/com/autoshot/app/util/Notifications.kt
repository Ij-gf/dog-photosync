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

    // v2：老通道的重要性一旦创建就改不动，换 id 重建才能降到最低档
    const val CHANNEL_CAPTURE = "capture_v2"
    const val LEGACY_CHANNEL_CAPTURE = "capture"
    const val CAPTURE_NOTIFICATION_ID = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 删掉旧通道（其用户设置一并清除），避免残留可见性更高的旧设置
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_CAPTURE) }

        val capture = NotificationChannel(
            CHANNEL_CAPTURE,
            context.getString(R.string.notif_channel_capture),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "后台运行状态"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        nm.createNotificationChannel(capture)
    }

    /**
     * 前台服务通知：系统强制要求存在，这里只压到"最低可见"档——
     * 不弹横幅、不响铃、不显示状态栏图标、无角标，折叠在通知栏底部。
     * 不做隐藏图标 / 伪装成系统应用之类的规避处理。
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
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }
}
