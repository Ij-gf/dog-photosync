package com.autoshot.app.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机控制器：绑定 → 预置 3A 模式 → 直写文件连拍 → 解绑。
 *
 * 实现要点：
 * 1. 不绑 Preview：后台服务没有稳定 Surface，ImageCapture + ImageAnalysis 足以出图；
 * 2. 绑定/解绑全部回主线程，拍摄回调走自己的单线程执行器；
 * 3. 每次切换镜头先 unbindAll，避免前后摄同时占用；
 * 4. targetRotation 取系统当前显示方向——服务里没有 window，不显式设置会导致成像横竖颠倒。
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraCaptureManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val captureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-capture").apply { priority = Thread.NORM_PRIORITY }
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-analysis").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val mutex = Mutex()
    private var provider: ProcessCameraProvider? = null

    @Volatile
    var analyzer: ClarityAnalyzer? = null
        private set

    private suspend fun getProvider(): ProcessCameraProvider = withContext(Dispatchers.IO) {
        provider ?: ProcessCameraProvider.getInstance(context).get().also { provider = it }
    }

    private fun currentRotation(): Int {
        val rotation = runCatching {
            val dm = context.getSystemService(DisplayManager::class.java)
            dm?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> Surface.ROTATION_90
            Surface.ROTATION_180 -> Surface.ROTATION_180
            Surface.ROTATION_270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    suspend fun hasCamera(lensFacing: Int): Boolean {
        val p = runCatching { getProvider() }.getOrNull() ?: return false
        return runCatching {
            p.hasCamera(CameraSelector.Builder().requireLensFacing(lensFacing).build())
        }.getOrDefault(false)
    }

    /**
     * 用指定镜头连拍 [count] 张，返回落地成功的文件；返回时相机已解绑。
     * 全过程与其它调用串行（Mutex），不会出现两路绑定互相打断。
     */
    suspend fun captureBurst(lensFacing: Int, dir: File, count: Int = 1): List<File> = mutex.withLock {
        val p = runCatching { getProvider() }.getOrElse {
            Log.e(TAG, "相机初始化失败", it)
            return@withLock emptyList()
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        if (!runCatching { p.hasCamera(selector) }.getOrDefault(false)) {
            Log.w(TAG, "设备无此镜头 lensFacing=$lensFacing")
            return@withLock emptyList()
        }
        dir.mkdirs()

        val rotation = currentRotation()
        val imageCapture = buildImageCapture(rotation)
        val analysis = buildAnalysis(rotation)

        try {
            withContext(Dispatchers.Main) {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, imageCapture, analysis)
            }

            waitForConvergence()

            val files = ArrayList<File>(count)
            for (index in 0 until count) {
                val file = File(dir, "cap_${lensFacing}_${System.currentTimeMillis()}_$index.jpg")
                try {
                    takePicture(imageCapture, file)
                    files += file
                } catch (e: ImageCaptureException) {
                    Log.w(TAG, "拍摄失败 lensFacing=$lensFacing code=${e.imageCaptureError}", e)
                    file.delete()
                }
                if (index < count - 1) delay(BURST_GAP_MS)
            }
            files
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "连拍异常 lensFacing=$lensFacing", t)
            emptyList()
        } finally {
            // 取消也要保证解绑，否则下次 bind 会报 camera in use
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { analysis.clearAnalyzer() }
                withContext(Dispatchers.Main) {
                    runCatching { p.unbindAll() }
                }
            }
        }
    }

    suspend fun captureOne(lensFacing: Int, dir: File): File? =
        captureBurst(lensFacing, dir, 1).firstOrNull()

    /** 等分析器产出几帧，让 AE/AWB 收敛；最多 600ms，绝不无限等 */
    private suspend fun waitForConvergence() {
        val deadline = System.currentTimeMillis() + 600
        while (System.currentTimeMillis() < deadline) {
            val a = analyzer
            if (a != null && a.frames >= 2) return
            delay(40)
        }
    }

    private fun buildImageCapture(rotation: Int): ImageCapture {
        val builder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(90)
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1080, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )

        // 预置 3A 模式：跳过首帧的测光对焦轮次，省 200~500ms。
        // 只锁「模式」不锁 AE_LOCK，否则首帧还未测光就锁，出图会偏暗。
        try {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                )
        } catch (t: Throwable) {
            Log.w(TAG, "Camera2Interop 不可用，使用默认参数", t)
        }
        return builder.build()
    }

    private fun buildAnalysis(rotation: Int): ImageAnalysis {
        val a = ClarityAnalyzer()
        analyzer = a
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(rotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
            .also { it.setAnalyzer(analysisExecutor, a) }
    }

    private suspend fun takePicture(imageCapture: ImageCapture, file: File) =
        suspendCancellableCoroutine<Unit> { cont ->
            val options = ImageCapture.OutputFileOptions.Builder(file).build()
            imageCapture.takePicture(
                options,
                captureExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                }
            )
        }

    /**
     * 解绑相机。必须在主线程同步完成——CameraX 解绑会同步回调 takePicture 的 onError，
     * 若此时线程池已关闭，会抛出 RejectedExecutionException 并击穿生命周期回调导致进程崩溃。
     */
    fun release() {
        val p = provider ?: return
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                p.unbindAll()
            } else {
                mainExecutor.execute { runCatching { p.unbindAll() } }
            }
        }.onFailure { Log.w(TAG, "unbindAll 失败", it) }
    }

    /** 解绑完成后再优雅关闭线程池（shutdown 而非 shutdownNow，保留已在队列中的回调） */
    fun shutdownExecutors() {
        runCatching { captureExecutor.shutdown() }
        runCatching { analysisExecutor.shutdown() }
    }

    companion object {
        private const val TAG = "CameraCapture"
        private const val BURST_GAP_MS = 120L
    }
}
