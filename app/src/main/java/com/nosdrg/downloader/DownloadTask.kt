package com.nosdrg.downloader

enum class TaskStatus {
    IDLE,
    DOWNLOADING,
    SUCCESS,
    FAILED,
    CANCELED
}

data class DownloadTask(
    val url: String = "",
    val progress: Float = 0f,
    val speed: String = "",
    val status: TaskStatus = TaskStatus.IDLE,
    val errorMessage: String = "",
    val outputUri: String = ""
)