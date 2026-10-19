package com.nosdrg.downloader

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
    val isPlaylist: Boolean = false,
    val playlistTitle: String = ""
)