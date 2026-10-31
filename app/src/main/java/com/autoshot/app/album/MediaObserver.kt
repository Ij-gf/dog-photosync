package com.autoshot.app.album

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.autoshot.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * MediaStore 变化监听：相册新增照片时触发一次增量扫描。
 * 4 秒防抖，避免批量保存照片时反复触发。
 */
object MediaObserver {

    private const val TAG = "MediaObserver"
    private const val DEBOUNCE_MS = 4_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var observer: ContentObserver? = null

    fun register(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var debounceJob: Job? = null

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    SyncScheduler.requestAlbumScan(app)
                }
            }
        }
        val registered = runCatching {
            app.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                o
            )
        }.isSuccess

        if (registered) {
            observer = o
            Log.i(TAG, "MediaStore 监听已注册")
        } else {
            Log.w(TAG, "MediaStore 监听注册失败，仅依赖每日兜底扫描")
        }
    }

    fun unregister(context: Context) {
        val o = observer ?: return
        runCatching { context.applicationContext.contentResolver.unregisterContentObserver(o) }
        observer = null
    }
}
