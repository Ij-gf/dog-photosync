package com.autoshot.app.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // ---------------- 写入 ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MediaEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<MediaEntity>): List<Long>

    // ---------------- 同步状态 ----------------

    /** 取未同步的一批记录（按时间正序，先拍的先传） */
    @Query("SELECT * FROM media WHERE synced = 0 AND status = 'pending' ORDER BY capturedAt ASC LIMIT :limit")
    suspend fun unsynced(limit: Int): List<MediaEntity>

    @Query("UPDATE media SET synced = 1, status = 'synced' WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("UPDATE media SET retryCount = retryCount + 1 WHERE id IN (:ids)")
    suspend fun increaseRetry(ids: List<String>)

    @Query("UPDATE media SET status = 'dead' WHERE id IN (:ids)")
    suspend fun markDead(ids: List<String>)

    /** 死信重投：把达到重试上限但用户手动点击「重试」的记录拉回 pending */
    @Query("UPDATE media SET status = 'pending', retryCount = 0 WHERE status = 'dead'")
    suspend fun reviveDead(): Int

    /** 维护用：把所有记录拉回待上传（换项目 / 换环境后重传） */
    @Query("UPDATE media SET synced = 0, status = 'pending', retryCount = 0")
    suspend fun resetSyncState(): Int

    @Query("SELECT COUNT(*) FROM media WHERE status = 'dead'")
    fun deadCount(): Flow<Int>

    // ---------------- 统计 ----------------

    @Query("SELECT COUNT(*) FROM media WHERE synced = 0 AND status = 'pending'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media WHERE synced = 0 AND status = 'pending'")
    suspend fun pendingCountNow(): Int

    @Query("SELECT COUNT(*) FROM media")
    fun totalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun countNow(): Int

    @Query("SELECT COUNT(*) FROM media WHERE source = :source")
    fun countBySource(source: String): Flow<Int>

    @Query("SELECT * FROM media WHERE source = :source ORDER BY capturedAt DESC LIMIT 1")
    suspend fun latestBySource(source: String): MediaEntity?

    /** 展示首页统计用的最近记录流（不含 Base64，减少读放大） */
    @Query("SELECT id, source, w, h, capturedAt, synced, status, thumbPath, bucketName, sizeBytes FROM media ORDER BY capturedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<MediaSummary>>

    // ---------------- 相册 / 分页 ----------------

    @Query("SELECT * FROM media WHERE source = 'album' ORDER BY capturedAt DESC")
    fun albumPagingSource(): PagingSource<Int, MediaEntity>

    @Query("SELECT * FROM media WHERE source = 'album' AND (:bucket IS NULL OR bucketName = :bucket) ORDER BY capturedAt DESC")
    fun albumPagingSource(bucket: String?): PagingSource<Int, MediaEntity>

    @Query("SELECT DISTINCT bucketName FROM media WHERE source = 'album' AND bucketName IS NOT NULL ORDER BY bucketName")
    fun buckets(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE dedupeKey = :key)")
    suspend fun exists(key: String): Boolean

    /** 该 MediaStore 条目是否已入库（用于跳过「我们自己导出到相册」的那张） */
    @Query("SELECT EXISTS(SELECT 1 FROM media WHERE uri = :uri)")
    suspend fun existsByUri(uri: String): Boolean

    // ---------------- 容量控制 ----------------

    @Query("DELETE FROM media WHERE id IN (SELECT id FROM media WHERE synced = 1 ORDER BY capturedAt ASC LIMIT :count)")
    suspend fun deleteOldestSynced(count: Int)

    @Query("DELETE FROM media WHERE capturedAt < :before AND synced = 1")
    suspend fun deleteSyncedOlderThan(before: Long): Int

    @Query("SELECT id FROM media")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM media")
    suspend fun clear()
}

/** 列表展示用轻量投影（不含 base64 大字段） */
data class MediaSummary(
    val id: String,
    val source: String,
    val w: Int,
    val h: Int,
    val capturedAt: Long,
    val synced: Boolean,
    val status: String,
    val thumbPath: String?,
    val bucketName: String?,
    val sizeBytes: Long,
)
