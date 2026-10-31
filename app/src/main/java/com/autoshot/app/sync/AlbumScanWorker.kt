package com.autoshot.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autoshot.app.di.ServiceLocator

/**
 * 相册增量扫描 Worker。
 * 触发来源：App 启动、MediaStore ContentObserver 变化、每日兜底全量校验。
 */
class AlbumScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ServiceLocator.repository
        val result = runCatching { repo.scanAlbum() }
        if (result.isFailure) {
            ServiceLocator.settings.setLastError("相册扫描失败：${result.exceptionOrNull()?.message}")
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }

        val outcome = result.getOrThrow()
        outcome.message?.let { ServiceLocator.settings.setLastError(it) }
        if (outcome.added > 0) {
            // 有新图立刻排一次上传（仍受 WiFi/电量约束）
            SyncScheduler.requestUpload(applicationContext)
        }
        return Result.success()
    }
}
