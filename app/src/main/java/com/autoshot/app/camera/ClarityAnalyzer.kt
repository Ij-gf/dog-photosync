package com.autoshot.app.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs

/**
 * ImageAnalysis 抽帧分析器：只读 Y 平面（亮度），按步长采样算拉普拉斯方差与平均亮度。
 *
 * 为什么不用它产图：YUV_420_888 → JPEG 需要额外做 NV21 拼装与 YuvImage 压缩，
 * 稳定性不如 ImageCapture 直写文件；因此这里只承担「实时取景质量评估」，
 * 真正入库与选帧用的是 ImageCapture 连拍出来的原始 JPEG（见 CaptureService）。
 */
class ClarityAnalyzer : ImageAnalysis.Analyzer {

    @Volatile
    var lastSharpness: Double = 0.0
        private set

    @Volatile
    var lastLuma: Double = 0.0
        private set

    @Volatile
    var frames: Int = 0
        private set

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val step = 8

            if (width < step * 3 || height < step * 3) return

            // 采样成小网格，避免逐像素遍历
            val gw = width / step
            val gh = height / step
            val gray = IntArray(gw * gh)

            var minLuma = 255
            var maxLuma = 0
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    val x = gx * step
                    val y = gy * step
                    val index = y * rowStride + x * pixelStride
                    val v = if (index < buffer.limit()) buffer.get(index).toInt() and 0xFF else 0
                    gray[gy * gw + gx] = v
                    if (v < minLuma) minLuma = v
                    if (v > maxLuma) maxLuma = v
                }
            }

            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (gy in 1 until gh - 1) {
                for (gx in 1 until gw - 1) {
                    val i = gy * gw + gx
                    val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - gw] - gray[i + gw]).toDouble()
                    sum += lap
                    sumSq += lap * lap
                    n++
                }
            }

            if (n > 0) {
                val mean = sum / n
                lastSharpness = (sumSq / n - mean * mean).coerceAtLeast(0.0)
            }
            lastLuma = (minLuma + maxLuma) / 2.0
            frames++

            // 曝光异常（全黑/全白）时给出明确信号，方便上层放弃等待 AE 收敛
            if (abs(maxLuma - minLuma) < 3) lastSharpness = 0.0
        } catch (t: Throwable) {
            // 分析失败不能影响拍照主流程
        } finally {
            image.close()
        }
    }
}
