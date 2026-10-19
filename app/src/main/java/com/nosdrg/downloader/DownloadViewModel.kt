package com.nosdrg.downloader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class UiState(
    val url: String = "",
    val isAnalyzing: Boolean = false,
    val metadata: MediaMetadata? = null,
    val selectedFormat: FormatItem? = null,
    val downloadEntirePlaylist: Boolean = false,
    val customFolderUri: Uri? = null,
    val customFolderDisplayName: String = "Mặc định (Music/Movies)",
    val progress: Float = 0f,
    val speed: String = "",
    val currentDownloadingTitle: String = "",
    val status: TaskStatus = TaskStatus.IDLE,
    val errorMessage: String = "",
    val outputUri: String = ""
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloaderRepository(application)
    private var downloadJob: Job? = null
    private var currentCancelToken: CancelToken? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun inspectUrl(url: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(status = TaskStatus.FAILED, errorMessage = "Vui lòng nhập liên kết") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    url = url,
                    isAnalyzing = true,
                    status = TaskStatus.IDLE,
                    errorMessage = "",
                    metadata = null,
                    selectedFormat = null,
                    downloadEntirePlaylist = false
                )
            }

            try {
                val result = withTimeout(20_000L) {
                    repository.analyzeUrl(url)
                }

                result.onSuccess { data ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            metadata = data,
                            selectedFormat = data.formats.firstOrNull()
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            status = TaskStatus.FAILED,
                            errorMessage = err.message ?: "Phân tích URL thất bại"
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        status = TaskStatus.FAILED,
                        errorMessage = "Quá thời gian kết nối (Timeout). Vui lòng thử lại."
                    )
                }
            }
        }
    }

    fun selectFormat(format: FormatItem) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun setDownloadEntirePlaylist(entire: Boolean) {
        _uiState.update { it.copy(downloadEntirePlaylist = entire) }
    }

    fun setCustomFolder(uri: Uri?, name: String) {
        _uiState.update {
            it.copy(customFolderUri = uri, customFolderDisplayName = name)
        }
    }

    fun startDownload() {
        val state = _uiState.value
        val format = state.selectedFormat ?: return
        val url = state.url

        downloadJob?.cancel()
        val token = CancelToken()
        currentCancelToken = token

        _uiState.update {
            it.copy(
                progress = 0f,
                speed = "Đang kết nối...",
                status = TaskStatus.DOWNLOADING,
                errorMessage = ""
            )
        }

        downloadJob = viewModelScope.launch {
            repository.downloadMedia(
                url = url,
                formatItem = format,
                downloadPlaylist = state.downloadEntirePlaylist,
                customFolderUri = state.customFolderUri,
                cancelToken = token,
                callback = object : DownloaderRepository.DownloadCallback {
                    override fun onProgress(percent: Float, speed: String, currentTitle: String) {
                        _uiState.update {
                            it.copy(
                                progress = percent,
                                speed = speed,
                                currentDownloadingTitle = currentTitle
                            )
                        }
                    }

                    override fun onSuccess(fileUri: String) {
                        _uiState.update {
                            it.copy(
                                progress = 100f,
                                status = TaskStatus.SUCCESS,
                                outputUri = fileUri,
                                currentDownloadingTitle = ""
                            )
                        }
                    }

                    override fun onError(error: String) {
                        _uiState.update {
                            it.copy(
                                status = TaskStatus.FAILED,
                                errorMessage = error,
                                currentDownloadingTitle = ""
                            )
                        }
                    }

                    override fun onCanceled() {
                        _uiState.update {
                            it.copy(
                                status = TaskStatus.CANCELED,
                                errorMessage = "Đã dừng tải về",
                                currentDownloadingTitle = ""
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
            it.copy(status = TaskStatus.CANCELED, errorMessage = "Đã dừng tiến trình")
        }
    }
}