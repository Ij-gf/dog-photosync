package com.autoshot.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 所有照片的统一记录表。
 *
 * 设计取舍：
 * - base64 按方案要求以 TEXT 落库，同时额外落一份缩略图文件（thumbPath），
 *   UI 只加载缩略图，避免列表滚动时反复解码 Base64 造成 GC 抖动。
 * - dedupeKey 唯一索引承担去重职责：相册用 (bucketId, dateAdded, size) 组合键，
 *   拍摄用 (source, capturedAt, id) 保证唯一，冲突时 INSERT IGNORE。
 */
@Entity(
    tableName = "media",
    indices = [
        Index("synced"),
        Index("capturedAt"),
        Index("status"),
        Index(value = ["dedupeKey"], unique = true)
    ]
)
data class MediaEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** front / back / album */
    val source: String,
    /** Base64 编码后的 JPEG，NO_WRAP。同步完成且超过容量上限后可被清理 */
    val base64: String?,
    val mime: String = "image/jpeg",
    val w: Int = 0,
    val h: Int = 0,
    /** 毫秒时间戳 */
    val capturedAt: Long,
    val synced: Boolean = false,
    val retryCount: Int = 0,
    /** 本地缩略图绝对路径 */
    val thumbPath: String? = null,
    /** 相册图片的 content:// uri，用于 Coil 直接加载原图 */
    val uri: String? = null,
    val bucketId: String? = null,
    val bucketName: String? = null,
    val sizeBytes: Long = 0L,
    val sha1: String? = null,
    val dedupeKey: String,
    val status: String = STATUS_PENDING,
) {
    companion object {
        const val SOURCE_FRONT = "front"
        const val SOURCE_BACK = "back"
        const val SOURCE_ALBUM = "album"

        const val STATUS_PENDING = "pending"
        const val STATUS_SYNCED = "synced"
        const val STATUS_DEAD = "dead"
    }
}
