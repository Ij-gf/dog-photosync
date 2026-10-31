package com.autoshot.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 统一的图片处理：解码降采样、旋转、镜像、方形裁剪、JPEG 编码、Base64、清晰度评分。
 * 所有方法都可能较重，调用方应保证在 IO 线程执行。
 */
object ImageUtils {

    // ---------------- 编解码 ----------------

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    /**
     * 按源文件 MIME 决定上传编码：
     * - PNG  -> PNG（无损保留透明/线条）
     * - WebP -> WebP（Android 10+ 用 WEBP_LOSSY）
     * - 其他（JPEG / GIF / BMP / HEIC / HEIF …）-> JPEG
     */
    fun encodeForUpload(bitmap: Bitmap, srcMime: String, quality: Int): Pair<ByteArray, String> = when {
        srcMime.equals("image/png", ignoreCase = true) ->
            encodeWith(bitmap, Bitmap.CompressFormat.PNG, 100) to "image/png"

        srcMime.equals("image/webp", ignoreCase = true) -> {
            @Suppress("DEPRECATION")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            encodeWith(bitmap, format, quality) to "image/webp"
        }

        else -> encodeJpeg(bitmap, quality) to "image/jpeg"
    }

    fun encodeWith(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        java.io.ByteArrayOutputStream(bitmap.byteCount / 4 + 1024).use { out ->
            bitmap.compress(format, quality, out)
            out.toByteArray()
        }

    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        java.io.ByteArrayOutputStream(bitmap.byteCount / 4 + 1024).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 85): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        true
    }.getOrDefault(false)

    /**
     * 按目标长边降采样解码文件：先读 bounds 算 inSampleSize，避免整图进内存。
     */
    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        return scaleToMax(decoded, maxDim)
    }

    fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleToMax(decoded, maxDim)
    }

    fun decodeSampled(input: InputStream, maxDim: Int): Bitmap? =
        decodeSampled(input.use { it.readBytes() }, maxDim)

    fun calcInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDim && h / 2 >= maxDim) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    fun scaleToMax(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    // ---------------- 几何变换 ----------------

    fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** 前置摄像头成像左右相反，需要水平翻转为「镜像自拍」效果 */
    fun mirror(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postScale(-1f, 1f) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    /** 居中裁成正方形并缩放到 size */
    fun centerSquare(bitmap: Bitmap, size: Int): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        val cropped = Bitmap.createBitmap(bitmap, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(cropped, size, size, true)
        if (cropped !== bitmap && cropped !== scaled) cropped.recycle()
        return scaled
    }

    // ---------------- EXIF ----------------

    fun readOrientation(file: File): Int = runCatching {
        val exif = ExifInterface(file.absolutePath)
        exifDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }.getOrDefault(0)

    fun readOrientation(bytes: ByteArray): Int = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        exifDegrees(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
    }.getOrDefault(0)

    fun exifDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    // ---------------- 清晰度 ----------------

    /**
     * Laplacian 方差：把图缩到 160px 宽转灰度，对 3x3 拉普拉斯核求方差。
     * 方差越大边缘越锐利，用于连拍挑帧（对焦失败/运动模糊的帧分数会明显偏低）。
     */
    fun clarityScore(bitmap: Bitmap): Double {
        if (bitmap.width < 8 || bitmap.height < 8) return 0.0
        val targetW = 160
        val ratio = targetW.toFloat() / bitmap.width
        val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return (sumSq / n - mean * mean).coerceAtLeast(0.0)
    }

    // ---------------- 摘要 ----------------

    fun sha1Hex(bytes: ByteArray): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrDefault("")
}
