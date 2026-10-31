package com.autoshot.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 所有后台任务的调度入口。
 *
 * 上传：任何可用网络都传（NetworkType.CONNECTED）。
 * 注意不能用 UNMETERED——热点、部分 4G/WiFi 会被系统标成计费网络，
 * 那样任务会永远停在 ENQUEUED，看起来就是"连了 WiFi 也不上传"。
 * 相册：一次性扫描（启动/文件变化触发）+ 每日一次兜底全量校验。
 */
object SyncScheduler {

    private const val WORK_UPLOAD_ONCE = "upload_once"
    private const val WORK_UPLOAD_PERIODIC = "upload_periodic"
    private const val WORK_SCAN_ONCE = "album_scan_once"
    private const val WORK_SCAN_DAILY = "album_scan_daily"

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** 立即排一次上传；相同任务已在队列中时不重复入队（KEEP） */
    fun requestUpload(context: Context, wifiOnly: Boolean = true) {
        val constraints = if (wifiOnly) networkConstraints()
        else Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_UPLOAD_ONCE,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** 15 分钟兜底重试（断网恢复后也能自动续传） */
    fun schedulePeriodicUpload(context: Context) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_UPLOAD_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    /** 立即增量扫描相册 */
    fun requestAlbumScan(context: Context) {
        val request = OneTimeWorkRequestBuilder<AlbumScanWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_SCAN_ONCE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** 每日一次全量校验，防止 ContentObserver 漏事件 */
    fun scheduleDailyScan(context: Context) {
        val request = PeriodicWorkRequestBuilder<AlbumScanWorker>(1, TimeUnit.DAYS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_SCAN_DAILY,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleAll(context: Context) {
        schedulePeriodicUpload(context)
        scheduleDailyScan(context)
    }
}
