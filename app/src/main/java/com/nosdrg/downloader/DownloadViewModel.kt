package com.nosdrg.downloader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloaderRepository(application)
    private var downloadJob: Job? = null
    private var currentCancelToken: CancelToken? = null

    private val _uiState = MutableStateFlow(DownloadTask())
    val uiState: StateFlow<DownloadTask> = _uiState.asStateFlow()

    fun startDownload(url: String, isAudioOnly: Boolean, resolution: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(status = TaskStatus.FAILED, errorMessage = "Vui lòng nhập URL.") }
            return
        }

        downloadJob?.cancel()
        val token = CancelToken()
        currentCancelToken = token

        _uiState.value = DownloadTask(
            url = url,
            progress = 0f,
            speed = "Khởi tạo kết nối...",
            status = TaskStatus.DOWNLOADING
        )

        downloadJob = viewModelScope.launch {
            repository.downloadMedia(
                url = url,
                isAudioOnly = isAudioOnly,
                resolution = resolution,
                cancelToken = token,
                callback = object : DownloaderRepository.DownloadCallback {
                    override fun onProgress(percent: Float, speed: String) {
                        _uiState.update {
                            it.copy(progress = percent, speed = speed)
                        }
                    }

                    override fun onSuccess(fileUri: String) {
                        _uiState.update {
                            it.copy(
                                progress = 100f,
                                status = TaskStatus.SUCCESS,
                                outputUri = fileUri
                            )
                        }
                    }

                    override fun onError(error: String) {
                        _uiState.update {
                            it.copy(
                                status = TaskStatus.FAILED,
                                errorMessage = error
                            )
                        }
                    }

                    override fun onCanceled() {
                        _uiState.update {
                            it.copy(
                                status = TaskStatus.CANCELED,
                                errorMessage = "Đã dừng tải về."
                            )
                        }
                    }
                }
            )
        }
    }

    fun abortDownload() {
        currentCancelToken?.cancel()
        downloadJob?.cancel()
        _uiState.update {
            it.copy(status = TaskStatus.CANCELED, errorMessage = "Đã dừng tiến trình.")
        }
    }
}