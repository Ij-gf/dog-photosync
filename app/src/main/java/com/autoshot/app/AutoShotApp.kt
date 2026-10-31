package com.autoshot.app

import android.app.Application
import android.util.Log
import com.autoshot.app.album.MediaObserver
import com.autoshot.app.di.ServiceLocator
import com.autoshot.app.sync.SyncScheduler
import com.autoshot.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AutoShotApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        Notifications.createChannels(this)

        // 相册变化实时监听 + 每日兜底全量校验
        MediaObserver.register(this)
        SyncScheduler.scheduleAll(this)

        // 冷启动补扫一次（无权限时 Worker 会自行跳过）
        SyncScheduler.requestAlbumScan(this)

        val configured = ServiceLocator.supabase.isConfigured
        if (configured) {
            Log.i(TAG, "Supabase 地址 = ${BuildConfig.SUPABASE_URL}")
        } else {
            Log.e(TAG, "未配置 Supabase：local.properties 的 SUPABASE_URL / SUPABASE_ANON_KEY 为空（需重新编译安装）")
        }

        appScope.launch {
            // 维护开关：files/resync.flag 存在时，把所有记录拉回待上传后删掉标记文件
            runCatching {
                val flag = java.io.File(filesDir, "resync.flag")
                if (flag.exists()) {
                    val n = ServiceLocator.repository.resetSyncState()
                    flag.delete()
                    Log.i(TAG, "已重置同步状态，$n 条记录重新排队上传")
                }
            }
            // 死信拉回队列 + 排一次上传：常驻应用无人值守，不能因几次网络抖动永久放弃
            runCatching { ServiceLocator.repository.reviveDead() }
            SyncScheduler.requestUpload(applicationContext)
        }
    }

    companion object {
        private const val TAG = "AutoShotApp"
    }
}
