package com.nosdrg.downloader

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    val selectedSubtitle: SubtitleItem? = null,
    val downloadEntirePlaylist: Boolean = false,
    val customFolderUri: Uri? = null,
    val customFolderDisplayName: String = "Mặc định",
    val queueTasks: List<DownloadTask> = emptyList(),
    val errorMessage: String = ""
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = DownloaderRepository(application)
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        val savedUri = PreferencesHelper.getCustomFolderUri(application)
        val savedName = PreferencesHelper.getCustomFolderDisplayName(application)
        if (savedUri != null) {
            _uiState.update { it.copy(customFolderUri = savedUri, customFolderDisplayName = savedName) }
        }

        viewModelScope.launch {
            DownloadQueueBus.queue.collect { q ->
                _uiState.update { it.copy(queueTasks = q) }
            }
        }
    }

    fun setCustomFolder(uri: Uri?, name: String) {
        PreferencesHelper.saveCustomFolder(getApplication(), uri, name)
        _uiState.update { it.copy(customFolderUri = uri, customFolderDisplayName = name) }
    }

    fun resetToDefaultFolder() {
        PreferencesHelper.saveCustomFolder(getApplication(), null, "Mac dinh (Music/Movies)")
        _uiState.update { it.copy(customFolderUri = null, customFolderDisplayName = "Mac dinh (Music/Movies)") }
    }

    fun inspectUrl(url: String) {
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Vui long nhap lien ket") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    url = url,
                    isAnalyzing = true,
                    errorMessage = "",
                    metadata = null,
                    selectedFormat = null,
                    selectedSubtitle = null,
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
                            selectedFormat = data.formats.firstOrNull(),
                            selectedSubtitle = null
                        )
                    }
                }.onFailure { err ->
                    _uiState.update {
                        it.copy(
                            isAnalyzing = false,
                            errorMessage = err.message ?: "Phan tich URL that bai"
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        errorMessage = "Qua thoi gian ket noi (Timeout). Vui long thu lai."
                    )
                }
            }
        }
    }

    fun selectFormat(format: FormatItem) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun selectSubtitle(sub: SubtitleItem?) {
        _uiState.update { it.copy(selectedSubtitle = sub) }
    }

    fun setDownloadEntirePlaylist(entire: Boolean) {
        _uiState.update { it.copy(downloadEntirePlaylist = entire) }
    }

    fun addToQueue() {
        val state = _uiState.value
        val meta = state.metadata ?: return
        val format = state.selectedFormat ?: return

        val newTask = DownloadTask(
            url = state.url,
            title = meta.title,
            thumbnail = meta.thumbnail,
            formatItem = format,
            subLang = state.selectedSubtitle?.code,
            downloadPlaylist = state.downloadEntirePlaylist,
            customFolderUri = state.customFolderUri
        )

        DownloadQueueBus.enqueue(newTask)

        val intent = Intent(getApplication(), DownloadService::class.java).apply {
            action = DownloadService.ACTION_PROCESS_QUEUE
        }
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun removeQueueItem(taskId: String) {
        DownloadQueueBus.removeTask(taskId)
    }

    fun clearCompletedQueue() {
        DownloadQueueBus.clearCompleted()
    }
}