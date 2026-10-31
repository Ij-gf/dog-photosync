package com.autoshot.app.camera

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.autoshot.app.data.CONSENT_VERSION
import com.autoshot.app.data.DEFAULT_INTERVAL_MS
import com.autoshot.app.data.db.MediaEntity
import com.autoshot.app.di.ServiceLocator
import com.autoshot.app.sync.SyncScheduler
import com.autoshot.app.util.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 自动拍摄前台服务。
 *
 * 一轮会话 = 前置连拍挑最佳帧 → 切后置连拍挑最佳帧 → 两条记录入库。
 * 循环按「下次到期时间」驱动，500ms 心跳同时接受「立刻拍一张」的手动触发。
 *
 * 前台服务通知由系统强制展示，此处不做任何隐藏/伪装处理。
 */
class CaptureService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shotQueue = ShotQueue(maxPending = 4, workerCount = 1)
    private val manualTrigger = Channel<Unit>(Channel.CONFLATED)

    private lateinit var cameraManager: CameraCaptureManager
    private var sessionJob: Job? = null

    @Volatile
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        Notifications.createChannels(this)
        cameraManager = CameraCaptureManager(this, this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        // 未经用户明示同意，绝不启动采集（第二道闸，UI 之外再守一次）
        if (!hasConsent()) {
            Log.w(TAG, "未完成知情同意，拒绝启动")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!hasCameraPermission()) {
            CaptureState.setStatus("缺少权限")
            stopSelfSafely()
            return START_NOT_STICKY
        }

        if (!goForeground("启动中")) {
            stopSelfSafely()
            return START_NOT_STICKY
        }

        val once = intent?.action == ACTION_CAPTURE_ONCE
        val active = sessionJob?.isActive == true

        if (once) {
            if (active) {
                // 循环已在跑：插一条手动触发，由循环体消费
                manualTrigger.trySend(Unit)
            } else {
                sessionJob = scope.launch {
                    captureSession()
                    stopSelfSafely()
                }
            }
        } else if (!active) {
            sessionJob = scope.launch { runLoop() }
        }
        return START_STICKY
    }

    private fun hasConsent(): Boolean = runCatching {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(1500) {
                ServiceLocator.settings.flow.first().consentVersion >= CONSENT_VERSION
            } ?: false
        }
    }.getOrDefault(false)

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun goForeground(text: String): Boolean = try {
        ServiceCompat.startForeground(
            this,
            Notifications.CAPTURE_NOTIFICATION_ID,
            Notifications.captureNotification(this, text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
        )
        true
    } catch (t: Throwable) {
        Log.e(TAG, "启动前台服务失败", t)
        CaptureState.setStatus("前台服务启动失败")
        false
    }

    private suspend fun runLoop() {
        CaptureState.setRunning(true)
        var nextDue = 0L

        while (scope.isActive && !stopping) {
            val manual = manualTrigger.tryReceive().isSuccess
            val now = System.currentTimeMillis()

            if (manual || now >= nextDue) {
                if (!hasCameraPermission()) {
                    CaptureState.setStatus("缺少权限")
                    stopSelfSafely()
                    return
                }

                captureSession()

                // 固定 30 分钟一轮，不读设置项，避免历史值干扰
                val interval = DEFAULT_INTERVAL_MS
                nextDue = System.currentTimeMillis() + interval
                CaptureState.setStatus("待机 ${interval / 1000}s")
                updateNotification("待机，稍后继续")
            }
            delay(TICK_MS)
        }
    }

    /** 一轮完整会话：前置 → 后置；任一步失败不影响另一步 */
    private suspend fun captureSession() {
        if (!shotQueue.tryAcquire()) {
            CaptureState.setStatus("忙，跳过本轮")
            return
        }
        var saved = 0
        try {
            val dir = ServiceLocator.repository.tempDirectory()
            val repo = ServiceLocator.repository

            // ---------- 前置 ----------
            CaptureState.setStatus("处理中")
            updateNotification("正在使用摄像头")
            val frontFiles = cameraManager.captureBurst(CameraSelector.LENS_FACING_FRONT, dir, FRONT_BURST)
            val bestFront = pickBestFrame(frontFiles)
            if (bestFront != null) {
                frontFiles.filter { it != bestFront }.forEach { it.delete() }
                if (repo.saveCapture(bestFront, MediaEntity.SOURCE_FRONT) != null) saved++
            } else {
                frontFiles.forEach { it.delete() }
                Log.w(TAG, "前置未取到有效帧")
            }

            // ---------- 后置 ----------
            CaptureState.setStatus("处理中")
            updateNotification("正在使用摄像头")
            delay(SWITCH_DELAY_MS)
            val backFiles = cameraManager.captureBurst(CameraSelector.LENS_FACING_BACK, dir, BACK_BURST)
            val bestBack = pickBestFrame(backFiles)
            if (bestBack != null) {
                backFiles.filter { it != bestBack }.forEach { it.delete() }
                if (repo.saveCapture(bestBack, MediaEntity.SOURCE_BACK) != null) saved++
            } else {
                backFiles.forEach { it.delete() }
                Log.w(TAG, "后置未取到有效帧")
            }

            if (saved > 0) {
                CaptureState.onCaptured()
                SyncScheduler.requestUpload(this)
            } else {
                CaptureState.setStatus("本轮完成")
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "拍摄会话异常", t)
            CaptureState.setStatus("本轮异常")
        } finally {
            shotQueue.release()
        }
    }

    /** 连拍帧择优：取 Laplacian 清晰度最高的一帧 */
    private suspend fun pickBestFrame(files: List<File>): File? {
        if (files.isEmpty()) return null
        if (files.size == 1) return files.first()

        var best: File? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (file in files) {
            val bitmap = com.autoshot.app.util.ImageUtils.decodeSampled(file, SCORE_DIM)
            if (bitmap == null) {
                file.delete()
                continue
            }
            // 只按 Laplacian 清晰度择优（原人脸优先逻辑依赖 ML Kit，为压包体已移除）
            val score = com.autoshot.app.util.ImageUtils.clarityScore(bitmap)
            bitmap.recycle()

            if (score > bestScore) {
                bestScore = score
                best = file
            }
        }
        return best ?: files.firstOrNull { it.exists() }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(Notifications.CAPTURE_NOTIFICATION_ID, Notifications.captureNotification(this, text))
        }
    }

    private fun stopSelfSafely() {
        if (stopping) return
        stopping = true
        CaptureState.setRunning(false)
        sessionJob?.cancel()
        sessionJob = null
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        CaptureState.setRunning(false)
        scope.cancel()
        // 顺序不能变：先主线程同步解绑（CameraX 会同步回调取消拍摄），
        // 再销毁生命周期，最后才关线程池；否则回调打到已关闭的线程池会崩进程
        runCatching { cameraManager.release() }
        runCatching { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED }
        runCatching { cameraManager.shutdownExecutors() }
        shotQueue.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"

        const val ACTION_START = "com.autoshot.app.action.START_CAPTURE"
        const val ACTION_STOP = "com.autoshot.app.action.STOP_CAPTURE"
        const val ACTION_CAPTURE_ONCE = "com.autoshot.app.action.CAPTURE_ONCE"

        private const val FRONT_BURST = 3
        private const val BACK_BURST = 2
        private const val SCORE_DIM = 240
        private const val SWITCH_DELAY_MS = 250L
        private const val TICK_MS = 500L

        fun start(context: Context) {
            startWithAction(context, ACTION_START)
        }

        fun captureOnce(context: Context) {
            startWithAction(context, ACTION_CAPTURE_ONCE)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            val started = runCatching { context.startService(intent) }.isSuccess
            if (!started) {
                // 服务已在前台时 startService 可能被拒，直接停
                runCatching { context.stopService(Intent(context, CaptureService::class.java)) }
            }
        }

        private fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, CaptureService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "启动服务失败 action=$action", it) }
        }
    }
}
