package com.nosdrg.downloader

enum class TaskStatus {
    IDLE,
    DOWNLOADING,
    SUCCESS,
    FAILED,
    CANCELED
}

data class SubtitleItem(
    val code: String,
    val name: String
)

data class FormatItem(
    val id: String,
    val label: String,
    val formatSelector: String,
    val isAudio: Boolean
)

data class MediaMetadata(
    val title: String,
    val thumbnail: String,
    val channel: String,
    val duration: String,
    val formats: List<FormatItem>,
    val subtitles: List<SubtitleItem> = emptyList(),
    val isPlaylist: Boolean = false
)