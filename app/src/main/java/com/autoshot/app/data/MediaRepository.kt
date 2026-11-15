package com.autoshot.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.autoshot.app.data.db.MediaDao
import com.autoshot.app.data.db.MediaEntity
import com.autoshot.app.util.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 照片落库 + 相册扫描 + 容量治理的统一入口。
 */
class MediaRepository(
    private val context: Context,
    private val dao: MediaDao,
    private val settings: SettingsStore,
) {

    private val thumbDir = File(context.filesDir, "thumbs")
    private val tempDir = File(context.filesDir, "tmp")

    // ---------------- 对外只读 ----------------

    val pendingCount: Flow<Int> = dao.pendingCount()
    val totalCount: Flow<Int> = dao.totalCount()
    val deadCount: Flow<Int> = dao.deadCount()
    val frontCount: Flow<Int> = dao.countBySource(MediaEntity.SOURCE_FRONT)
    val albumCount: Flow<Int> = dao.countBySource(MediaEntity.SOURCE_ALBUM)
    val buckets: Flow<List<String>> = dao.buckets()

    fun recent(limit: Int = 20) = dao.recent(limit)

    fun tempDirectory(): File = tempDir.apply { mkdirs() }

    suspend fun pendingCountNow(): Int = dao.pendingCountNow()

    suspend fun reviveDead() = dao.reviveDead()

    // ---------------- 拍摄入库 ----------------

    /**
     * 把拍到的原始 JPEG 转换为：Base64(入库) + 缩略图(文件) + 头像(仅前摄)。
     * 完成后删除原始临时文件，避免同一张照片占两份空间。
     */
    suspend fun saveCapture(file: File, source: String): MediaEntity? = withContext(Dispatchers.IO) {
        val capturedAt = System.currentTimeMillis()
        var bitmap = ImageUtils.decodeSampled(file, CAPTURE_MAX_DIM)
        if (bitmap == null) {
            Log.w(TAG, "解码失败，丢弃该帧: ${file.absolutePath}")
            file.delete()
            return@withContext null
        }

        val rotation = ImageUtils.readOrientation(file)
        if (rotation != 0) bitmap = ImageUtils.rotate(bitmap, rotation)
        // 前置摄像头出图需要水平翻转
        if (source == MediaEntity.SOURCE_FRONT) bitmap = ImageUtils.mirror(bitmap)

        val id = UUID.randomUUID().toString()
        val jpeg = ImageUtils.encodeJpeg(bitmap, CAPTURE_QUALITY)

        val thumbFile = File(thumbDir, "$id.jpg").apply { parentFile?.mkdirs() }
        val thumb = ImageUtils.scaleToMax(bitmap, THUMB_DIM)
        ImageUtils.saveJpeg(thumb, thumbFile, 80)
        if (thumb !== bitmap) thumb.recycle()

        val width = bitmap.width
        val height = bitmap.height
        if (source == MediaEntity.SOURCE_FRONT) updateAvatarLocked(bitmap)
        bitmap.recycle()
        file.delete()

        // 不再写入系统相册：照片只留在应用私有目录与数据库里
        val entity = MediaEntity(
            id = id,
            source = source,
            base64 = ImageUtils.toBase64(jpeg),
            mime = "image/jpeg",
            w = width,
            h = height,
            capturedAt = capturedAt,
            synced = false,
            thumbPath = thumbFile.absolutePath,
            uri = null,
            sizeBytes = jpeg.size.toLong(),
            sha1 = ImageUtils.sha1Hex(jpeg),
            dedupeKey = "cap:$source:$capturedAt:$id",
        )

        val rowId = dao.insert(entity)
        if (rowId == -1L) {
            Log.w(TAG, "插入冲突，丢弃重复帧 $id")
            return@withContext null
        }
        trim()
        entity
    }

    /** 用最新前摄照片刷新头像文件（方形 512px，存 filesDir/avatar.jpg） */
    private suspend fun updateAvatarLocked(bitmap: android.graphics.Bitmap) {
        runCatching {
            val square = ImageUtils.centerSquare(bitmap, AVATAR_SIZE)
            val out = File(context.filesDir, "avatar.jpg")
            ImageUtils.saveJpeg(square, out, 90)
            if (square !== bitmap) square.recycle()
            settings.setAvatarPath(out.absolutePath)
        }.onFailure { Log.w(TAG, "更新头像失败", it) }
    }

    // ---------------- 相册扫描 ----------------

    data class ScanResult(val scanned: Int, val added: Int, val message: String? = null)

    /**
     * 增量扫描相册：只读 DATE_ADDED 水位线之后的新图，最多 [limit] 张。
     */
    suspend fun scanAlbum(limit: Int = DEFAULT_SCAN_LIMIT): ScanResult = withContext(Dispatchers.IO) {
        if (!hasMediaPermission()) {
            return@withContext ScanResult(0, 0, "缺少相册读取权限")
        }
        val cfg = settings.current()
        // 留 24 小时回溯窗口：MediaStore 索引可能晚于文件写入，
        // 若水位线一路前推，这类「晚索引」的文件会被永久漏掉；重复项由 dedupeKey 过滤，代价很小
        val since = if (cfg.lastScanAt > 0L) {
            (cfg.lastScanAt - SCAN_LOOKBACK_SEC).coerceAtLeast(0L)
        } else {
            0L
        }
        var added = 0
        var scanned = 0
        var maxDateAdded = since

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.ORIENTATION,
        )
        val selection = if (since > 0) "${MediaStore.Images.Media.DATE_ADDED} > ?" else null
        val args = if (since > 0) arrayOf(since.toString()) else null
        val order = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                order
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val orientationCol = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)

                while (cursor.moveToNext() && added < limit) {
                    scanned++
                    val dateAdded = cursor.getLong(dateCol)
                    if (dateAdded > maxDateAdded) maxDateAdded = dateAdded

                    val mediaId = cursor.getLong(idCol)
                    val bucketId = cursor.getString(bucketIdCol)
                    val bucketName = cursor.getString(bucketNameCol)
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val size = cursor.getLong(sizeCol)
                    val orientation = if (orientationCol >= 0) cursor.getInt(orientationCol) else 0

                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        mediaId.toString()
                    )
                    val ok = runCatching {
                        insertAlbumItem(uri, dateAdded, bucketId, bucketName, mime, size, orientation)
                    }.onFailure {
                        Log.w(TAG, "相册条目处理失败 uri=$uri", it)
                    }.getOrDefault(false)

                    if (ok) added++
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "相册扫描失败", t)
            return@withContext ScanResult(scanned, added, t.message)
        }

        if (maxDateAdded > since) settings.setLastScanAt(maxDateAdded)
        trim()
        ScanResult(scanned, added, null)
    }

    private suspend fun insertAlbumItem(
        uri: Uri,
        dateAddedSec: Long,
        bucketId: String?,
        bucketName: String?,
        mime: String,
        size: Long,
        orientation: Int,
    ): Boolean {
        if (size <= 0L || size > MAX_ALBUM_BYTES) return false
        if (!mime.startsWith("image/")) return false

        val dedupeKey = "album:${bucketId ?: "0"}:$dateAddedSec:$size"
        if (dao.exists(dedupeKey)) return false

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        var bitmap = ImageUtils.decodeSampled(bytes, ALBUM_MAX_DIM) ?: return false

        val degrees = if (orientation != 0) orientation else ImageUtils.readOrientation(bytes)
        if (degrees != 0) bitmap = ImageUtils.rotate(bitmap, degrees)

        // 保留常见图片格式：PNG / WebP 原样保留，其余（JPG/GIF/BMP/HEIC…）统一转 JPEG
        var encoded = ImageUtils.encodeForUpload(bitmap, mime, ALBUM_QUALITY)
        // Base64 膨胀 1/3，超过阈值就降质；PNG/WebP 压不下来时退回 JPEG
        var quality = ALBUM_QUALITY
        while (encoded.first.size > MAX_ENCODED_BYTES && quality > 45) {
            quality -= 15
            encoded = ImageUtils.encodeForUpload(bitmap, mime, quality)
        }
        if (encoded.first.size > MAX_ENCODED_BYTES) {
            encoded = ImageUtils.encodeJpeg(bitmap, 60) to "image/jpeg"
        }
        val jpeg = encoded.first
        val outMime = encoded.second

        val id = UUID.randomUUID().toString()
        val thumbFile = File(thumbDir, "$id.jpg").apply { parentFile?.mkdirs() }
        val thumb = ImageUtils.scaleToMax(bitmap, THUMB_DIM)
        ImageUtils.saveJpeg(thumb, thumbFile, 80)
        if (thumb !== bitmap) thumb.recycle()

        val entity = MediaEntity(
            id = id,
            source = MediaEntity.SOURCE_ALBUM,
            base64 = ImageUtils.toBase64(jpeg),
            mime = outMime,
            w = bitmap.width,
            h = bitmap.height,
            capturedAt = dateAddedSec * 1000L,
            synced = false,
            thumbPath = thumbFile.absolutePath,
            uri = uri.toString(),
            bucketId = bucketId,
            bucketName = bucketName,
            sizeBytes = jpeg.size.toLong(),
            sha1 = ImageUtils.sha1Hex(jpeg),
            dedupeKey = dedupeKey,
        )
        bitmap.recycle()

        return dao.insert(entity) != -1L
    }

    // ---------------- 容量治理 ----------------

    /**
     * 三档淘汰：条数上限 → 天数过期 → 孤儿缩略图。
     * 只淘汰已同步（已进 Supabase）的记录，未同步的一条都不动。
     */
    suspend fun trim() = withContext(Dispatchers.IO) {
        val cfg = settings.current()

        val count = dao.countNow()
        if (count > cfg.maxRows) {
            dao.deleteOldestSynced(count - cfg.maxRows)
        }

        val cutoff = System.currentTimeMillis() - cfg.retentionDays * DAY_MS
        dao.deleteSyncedOlderThan(cutoff)

        sweepThumbs()
    }

    private suspend fun sweepThumbs() {
        val alive = runCatching { dao.allIds().toHashSet() }.getOrElse { return }
        thumbDir.listFiles()?.forEach { f ->
            val id = f.nameWithoutExtension
            if (id !in alive) runCatching { f.delete() }
        }
    }

    // ---------------- 清理 ----------------

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        dao.clear()
        thumbDir.listFiles()?.forEach { runCatching { it.delete() } }
        tempDir.listFiles()?.forEach { runCatching { it.delete() } }
        settings.setAvatarPath(null)
        settings.setLastScanAt(0L)
    }

    // ---------------- 同步用 ----------------

    suspend fun unsyncedBatch(limit: Int): List<MediaEntity> = dao.unsynced(limit)

    suspend fun markSynced(ids: List<String>) = dao.markSynced(ids)

    suspend fun increaseRetry(ids: List<String>) = dao.increaseRetry(ids)

    suspend fun markDead(ids: List<String>) = dao.markDead(ids)

    /** 维护用：全部重新排队上传 */
    suspend fun resetSyncState(): Int = dao.resetSyncState()

    // ---------------- 权限 ----------------

    private fun hasMediaPermission(): Boolean {
        val full = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (context.checkSelfPermission(full) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return true
        }
        // Android 14+ 允许「部分照片」授权，此时也能读到用户选中的那部分
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val partial = context.checkSelfPermission(
                "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
            )
            return partial == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return false
    }

    companion object {
        private const val TAG = "MediaRepository"

        private const val CAPTURE_MAX_DIM = 1024
        private const val CAPTURE_QUALITY = 80
        private const val THUMB_DIM = 320
        private const val AVATAR_SIZE = 512

        private const val ALBUM_MAX_DIM = 1600
        private const val ALBUM_QUALITY = 75
        private const val MAX_ENCODED_BYTES = 3 * 1024 * 1024L
        private const val MAX_ALBUM_BYTES = 20L * 1024 * 1024
        private const val DEFAULT_SCAN_LIMIT = 200
        private const val SCAN_LOOKBACK_SEC = 24L * 60 * 60

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
