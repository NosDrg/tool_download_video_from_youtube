package com.nosdrg.downloader

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val url: String,
    val formatLabel: String,
    val isAudio: Boolean,
    val outputUri: String,
    val thumbnail: String,
    val downloadedAt: Long = System.currentTimeMillis()
)