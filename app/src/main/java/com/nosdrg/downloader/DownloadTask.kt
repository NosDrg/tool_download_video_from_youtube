package com.nosdrg.downloader

import android.net.Uri

enum class QueueStatus {
    WAITING,
    DOWNLOADING,
    SUCCESS,
    FAILED,
    CANCELED
}

data class DownloadTask(
    val id: String = System.currentTimeMillis().toString(),
    val url: String,
    val title: String,
    val thumbnail: String,
    val formatItem: FormatItem,
    val subLang: String? = null,
    val downloadPlaylist: Boolean = false,
    val customFolderUri: Uri? = null,
    val progress: Float = 0f,
    val speed: String = "",
    val status: QueueStatus = QueueStatus.WAITING,
    val errorMessage: String = "",
    val outputUri: String = ""
)