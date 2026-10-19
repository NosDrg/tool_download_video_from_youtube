package com.nosdrg.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DownloadProgressState(
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val speed: String = "",
    val currentTitle: String = "",
    val status: TaskStatus = TaskStatus.IDLE,
    val errorMessage: String = "",
    val outputUri: String = ""
)

object DownloadStateBus {
    private val _state = MutableStateFlow(DownloadProgressState())
    val state = _state.asStateFlow()

    fun updateProgress(progress: Float, speed: String, currentTitle: String) {
        _state.update {
            it.copy(
                isRunning = true,
                progress = progress,
                speed = speed,
                currentTitle = currentTitle,
                status = TaskStatus.DOWNLOADING
            )
        }
    }

    fun setSuccess(outputUri: String) {
        _state.update {
            it.copy(
                isRunning = false,
                progress = 100f,
                status = TaskStatus.SUCCESS,
                outputUri = outputUri,
                currentTitle = ""
            )
        }
    }

    fun setError(error: String) {
        _state.update {
            it.copy(
                isRunning = false,
                status = TaskStatus.FAILED,
                errorMessage = error,
                currentTitle = ""
            )
        }
    }

    fun setCanceled() {
        _state.update {
            it.copy(
                isRunning = false,
                status = TaskStatus.CANCELED,
                errorMessage = "Đã dừng tải về",
                currentTitle = ""
            )
        }
    }

    fun reset() {
        _state.value = DownloadProgressState()
    }
}