package com.nosdrg.downloader

import android.content.Context
import android.net.Uri
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.nosdrg.downloader.AppDatabase
import com.nosdrg.downloader.DownloadHistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class DownloaderRepository(private val context: Context) {

    private val historyDao = AppDatabase.getInstance(context).historyDao()

    val downloadHistory: Flow<List<DownloadHistoryEntity>> = historyDao.getAllHistory()

    suspend fun saveHistory(
        title: String,
        url: String,
        formatLabel: String,
        isAudio: Boolean,
        outputUri: String,
        thumbnail: String
    ) {
        historyDao.insertHistory(
            DownloadHistoryEntity(
                title = title,
                url = url,
                formatLabel = formatLabel,
                isAudio = isAudio,
                outputUri = outputUri,
                thumbnail = thumbnail
            )
        )
    }

    suspend fun deleteHistory(id: Long) = historyDao.deleteById(id)

    suspend fun clearAllHistory() = historyDao.clearAll()

    interface DownloadCallback {
        fun onProgress(percent: Float, speed: String, currentTitle: String = "")
        fun onSuccess(fileUri: String)
        fun onError(error: String)
        fun onCanceled()
    }

    suspend fun analyzeUrl(url: String): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("downloader_bridge")
            val cookiePath = PreferencesHelper.getCookieFilePath(context)
            val rawJson = bridge.callAttr("extract_media_info", url, cookiePath).toString()
            val json = JSONObject(rawJson)

            if (json.has("error")) {
                return@withContext Result.failure(Exception(json.getString("error")))
            }

            val formatList = mutableListOf<FormatItem>()
            val formatsArr = json.getJSONArray("formats")
            for (i in 0 until formatsArr.length()) {
                val item = formatsArr.getJSONObject(i)
                val isAudio = item.getString("type") == "audio"
                formatList.add(
                    FormatItem(
                        id = item.getString("id"),
                        label = item.getString("label"),
                        formatSelector = if (isAudio) "audio" else item.optInt("height", 720).toString(),
                        isAudio = isAudio
                    )
                )
            }

            val subList = mutableListOf<SubtitleItem>()
            val subsArr = json.optJSONArray("subtitles")
            if (subsArr != null) {
                for (i in 0 until subsArr.length()) {
                    val s = subsArr.getJSONObject(i)
                    subList.add(SubtitleItem(s.getString("code"), s.getString("name")))
                }
            }

            Result.success(
                MediaMetadata(
                    title = json.getString("title"),
                    thumbnail = json.getString("thumbnail"),
                    channel = json.getString("channel"),
                    duration = json.getString("duration"),
                    formats = formatList,
                    subtitles = subList,
                    isPlaylist = json.optBoolean("is_playlist", false)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMedia(
        url: String,
        title: String,
        formatItem: FormatItem,
        downloadPlaylist: Boolean,
        subLang: String?,
        customFolderUri: Uri?,
        cancelToken: CancelToken,
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("downloader_bridge")
            val cacheDir = context.cacheDir.absolutePath
            val cookiePath = PreferencesHelper.getCookieFilePath(context)

            val formatType = if (formatItem.isAudio) "audio" else "video"
            val height = if (formatItem.isAudio) 0 else (formatItem.formatSelector.toIntOrNull() ?: 720)

            val rawResult = bridge.callAttr(
                "download_media_task",
                url,
                cacheDir,
                formatType,
                height,
                downloadPlaylist,
                subLang,
                cookiePath,
                cancelToken,
                object {
                    @Suppress("unused")
                    fun onProgress(percent: Float, speed: String, currentTitle: String) {
                        callback.onProgress(percent, speed, currentTitle)
                    }
                }
            ).toString()

            val resJson = JSONObject(rawResult)
            val status = resJson.getString("status")

            if (status == "error") {
                val errorMsg = resJson.optString("error", "Loi khong xac dinh tu Playlist")
                callback.onError(errorMsg)
                return@withContext
            }

            val isAudio = resJson.getBoolean("is_audio")

            if (status == "playlist_completed") {
                val playlistDirStr = resJson.getString("playlist_dir")
                val playlistDir = File(playlistDirStr)

                val audioExts = setOf("m4a", "mp3", "opus", "aac", "ogg", "wav", "flac")
                val videoExts = setOf("mp4", "webm", "mkv", "3gp", "mov", "avi")
                val allSupportedExts = audioExts + videoExts

                val files = playlistDir.walkTopDown()
                    .filter { file ->
                        file.isFile &&
                        allSupportedExts.contains(file.extension.lowercase()) &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl")
                    }
                    .sortedBy { it.name }
                    .toList()

                if (files.isEmpty()) {
                    playlistDir.deleteRecursively()
                    callback.onError("Khong tim thay tep hop le nao duoc tai ve tu Playlist.")
                    return@withContext
                }

                // Uu tien lua chon Audio tu UI neu nguoi dung da chu dong chon
                val isOverallAudio = formatItem.isAudio || isAudio

                var savedCount = 0
                for (file in files) {
                    val itemTitle = file.nameWithoutExtension
                    val fileExt = file.extension.lowercase()
                    val isCurrentFileAudio = isOverallAudio || audioExts.contains(fileExt)

                    val uri = if (customFolderUri != null) {
                        StorageHelper.exportToCustomTreeUri(context, file, itemTitle, customFolderUri, isCurrentFileAudio)
                    } else {
                        if (isCurrentFileAudio) StorageHelper.exportAudioToPublic(context, file, itemTitle)
                        else StorageHelper.exportVideoToPublic(context, file, itemTitle)
                    }

                    if (uri != null) {
                        savedCount++
                        saveHistory(itemTitle, url, formatItem.label, isCurrentFileAudio, uri.toString(), "")
                    }
                }

                playlistDir.deleteRecursively()

                if (savedCount == 0) {
                    callback.onError("Loi: Khong the luu tep vao bo nho thiet bi.")
                } else {
                    callback.onSuccess("Da luu thanh cong $savedCount tep tu Playlist.")
                }
                return@withContext
            }

            val finalFile: File
            if (status == "mux_required") {
                callback.onProgress(100f, "Dang ghep...", "Dang ghep luong MediaMuxer")
                val vFile = File(resJson.getString("video_path"))
                val aFile = File(resJson.getString("audio_path"))
                val mergedFile = File(cacheDir, "completed_video_${System.currentTimeMillis()}.mp4")

                val muxResult = MediaMuxerHelper.mux(vFile, aFile, mergedFile)
                if (muxResult.isFailure) {
                    callback.onError("Loi MediaMuxer: ${muxResult.exceptionOrNull()?.message}")
                    return@withContext
                }
                finalFile = muxResult.getOrThrow()
            } else {
                finalFile = File(resJson.getString("file_path"))
            }

            val subPath = resJson.optString("sub_path", "")
            if (subPath.isNotBlank()) {
                val subFile = File(subPath)
                if (subFile.exists()) {
                    StorageHelper.exportSubtitleToPublic(context, subFile, title, customFolderUri)
                }
            }

            val finalUri = if (customFolderUri != null) {
                StorageHelper.exportToCustomTreeUri(context, finalFile, title, customFolderUri, isAudio)
            } else {
                if (isAudio) StorageHelper.exportAudioToPublic(context, finalFile, title)
                else StorageHelper.exportVideoToPublic(context, finalFile, title)
            }

            if (finalUri != null) {
                callback.onSuccess(finalUri.toString())
            } else {
                callback.onError("Khong the luu file vao bo nho may.")
            }
        } catch (e: PyException) {
            val msg = e.message ?: ""
            if (msg.contains("DownloadInterruptedException") || msg.contains("huy") || msg.contains("hủy")) {
                callback.onCanceled()
            } else {
                callback.onError(msg)
            }
        } catch (e: Exception) {
            callback.onError(e.localizedMessage ?: "Loi he thong")
        }
    }
}