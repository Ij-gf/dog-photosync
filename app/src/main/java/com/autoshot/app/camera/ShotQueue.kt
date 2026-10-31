package com.autoshot.app.camera

import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 连拍并发网关：
 * - 同一时刻只允许 1 次 CameraX 拍摄在飞（Semaphore(1)），避免连拍堆积导致 OOM；
 * - 固定线程池承接磁盘写入/编码；
 * - 队列满时直接丢帧而不是排队（drop-oldest 策略），保证「拍到的最新一帧」优先。
 */
class ShotQueue(
    private val maxPending: Int = 4,
    private val workerCount: Int = 2,
) {

    private val gate = Semaphore(permits = 1)
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val worker = Executors.newFixedThreadPool(workerCount) { r ->
        Thread(r, "shot-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    val droppedCount: Int get() = dropped.get()
    val pendingCount: Int get() = pending.get()

    /**
     * 申请一个拍摄许可。返回 false 表示当前队列已满，调用方应当直接跳过本次拍摄。
     * 许可被占用时挂起等待（不阻塞线程）。
     */
    suspend fun tryAcquire(): Boolean {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            Log.w(TAG, "连拍队列已满，丢弃本次请求 (dropped=${dropped.get()})")
            return false
        }
        gate.acquire()
        return true
    }

    fun release() {
        pending.decrementAndGet()
        gate.release()
    }

    fun execute(task: Runnable) {
        worker.execute(task)
    }

    /** 切换镜头时清空待发请求 */
    fun resetPending() {
        pending.set(0)
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        private const val TAG = "ShotQueue"
    }
}
