package com.autoshot.app.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autoshot.app.di.ServiceLocator
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 批量上传 Worker。
 *
 * 策略：
 * - 每批 50 条，单批 60s 超时；
 * - 失败 -> retryCount+1，由 WorkManager 指数退避重试（30s 起）；
 * - 累计尝试达到 MAX_ATTEMPTS 后移入死信（status=dead），不再占用重试配额。
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = ServiceLocator.repository
        val api = ServiceLocator.supabase

        if (!api.isConfigured) {
            // 配置是编译期写进 BuildConfig 的；这里为空说明打包时 local.properties 没填或没重新编译
            Log.e(TAG, "Supabase 未配置（SUPABASE_URL/ANON_KEY 为空），上传被跳过")
            ServiceLocator.settings.setLastError("Supabase 未配置，未上传")
            return Result.success()
        }
        if (repo.pendingCountNow() == 0) {
            Log.d(TAG, "没有待同步记录")
            return Result.success()
        }

        var uploadedTotal = 0
        var batchIndex = 0
        while (batchIndex < MAX_BATCHES_PER_RUN) {
            val batch = repo.unsyncedBatch(BATCH_SIZE)
            if (batch.isEmpty()) break

            val ids = batch.map { it.id }
            val result = withTimeoutOrNull(BATCH_TIMEOUT_MS) { api.uploadBatch(batch) }

            if (result == null) {
                // 超时：不算硬失败，交给退避重试
                repo.increaseRetry(ids)
                return retryOrDead(ids, "单批上传超时(60s)")
            }

            if (result.isSuccess) {
                repo.markSynced(ids)
                uploadedTotal += batch.size
                batchIndex++
                ServiceLocator.settings.setLastSyncAt(System.currentTimeMillis())
            } else {
                repo.increaseRetry(ids)
                val message = result.exceptionOrNull()?.message ?: "未知错误"
                return retryOrDead(ids, message)
            }
        }

        Log.i(TAG, "本轮上传完成，共 ${uploadedTotal} 条")
        return Result.success()
    }

    private suspend fun retryOrDead(ids: List<String>, message: String): Result {
        val attempts = runAttemptCount + 1
        Log.w(TAG, "上传失败(第 $attempts 次)：$message")
        if (attempts >= MAX_ATTEMPTS) {
            ServiceLocator.repository.markDead(ids)
            ServiceLocator.settings.setLastError("超过重试上限，${ids.size} 条转入死信：$message")
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val BATCH_SIZE = 20
        const val MAX_BATCHES_PER_RUN = 10
        const val MAX_ATTEMPTS = 12
        private const val BATCH_TIMEOUT_MS = 60_000L
    }
}
