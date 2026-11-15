package com.autoshot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoshot.app.sync.SyncScheduler

/**
 * 开机后恢复同步任务。
 *
 * Android 14+ 不允许后台启动 camera 类型前台服务，所以这里**不启动拍摄**，
 * 只把上传/相册扫描的调度重新挂上；拍摄会在用户下次打开应用时自动恢复。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        SyncScheduler.scheduleAll(context)
        SyncScheduler.requestAlbumScan(context)
        SyncScheduler.requestUpload(context)
    }
}
