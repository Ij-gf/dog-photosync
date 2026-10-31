package com.autoshot.app.camera

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 拍摄服务的进程内状态（UI 直接订阅，避免 Activity 与 Service 直接耦合）。
 */
object CaptureState {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastCaptureAt = MutableStateFlow(0L)
    val lastCaptureAt: StateFlow<Long> = _lastCaptureAt.asStateFlow()

    private val _sessionCount = MutableStateFlow(0)
    val sessionCount: StateFlow<Int> = _sessionCount.asStateFlow()

    fun setRunning(value: Boolean) {
        _running.value = value
        if (!value) _status.value = "已停止"
    }

    fun setStatus(text: String) {
        _status.value = text
    }

    fun onCaptured() {
        _lastCaptureAt.value = System.currentTimeMillis()
        _sessionCount.value = _sessionCount.value + 1
    }
}
