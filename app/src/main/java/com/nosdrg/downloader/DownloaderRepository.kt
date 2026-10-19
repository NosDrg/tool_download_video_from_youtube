package com.nosdrg.downloader

import android.content.Context
import android.net.Uri
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class DownloaderRepository(private val context: Context) {

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
            val rawJson = bridge.callAttr("extract_media_info", url).toString()
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

            Result.success(
                MediaMetadata(
                    title = json.getString("title"),
                    thumbnail = json.getString("thumbnail"),
                    channel = json.getString("channel"),
                    duration = json.getString("duration"),
                    formats = formatList,
                    isPlaylist = json.optBoolean("is_playlist", false)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadMedia(
        url: String,
        formatItem: FormatItem,
        downloadPlaylist: Boolean,
        customFolderUri: Uri?,
        cancelToken: CancelToken,
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("downloader_bridge")
            val cacheDir = context.cacheDir.absolutePath

            val formatType = if (formatItem.isAudio) "audio" else "video"
            val height = if (formatItem.isAudio) 0 else (formatItem.formatSelector.toIntOrNull() ?: 720)

            val rawResult = bridge.callAttr(
                "download_media_task",
                url,
                cacheDir,
                formatType,
                height,
                downloadPlaylist,
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
            val isAudio = resJson.getBoolean("is_audio")

            if (status == "playlist_completed") {
                val cacheFolder = File(cacheDir)
                val files = cacheFolder.listFiles { f ->
                    if (isAudio) f.extension.equals("m4a", true)
                    else f.extension.equals("mp4", true)
                } ?: emptyArray()

                var savedCount = 0
                for (file in files) {
                    val uri = if (customFolderUri != null) {
                        StorageHelper.exportToCustomTreeUri(context, file, customFolderUri, isAudio)
                    } else {
                        if (isAudio) StorageHelper.exportAudioToPublic(context, file)
                        else StorageHelper.exportVideoToPublic(context, file)
                    }
                    if (uri != null) savedCount++
                }

                callback.onSuccess("Đã lưu $savedCount tệp từ Playlist vào máy.")
                return@withContext
            }

            val finalFile: File
            if (status == "mux_required") {
                callback.onProgress(100f, "Đang ghép...", "Đang ghép video và audio bằng MediaMuxer")
                val vFile = File(resJson.getString("video_path"))
                val aFile = File(resJson.getString("audio_path"))
                val mergedFile = File(cacheDir, "completed_video_${System.currentTimeMillis()}.mp4")

                val muxResult = MediaMuxerHelper.mux(vFile, aFile, mergedFile)
                if (muxResult.isFailure) {
                    callback.onError("Lỗi ghép MediaMuxer: ${muxResult.exceptionOrNull()?.message}")
                    return@withContext
                }
                finalFile = muxResult.getOrThrow()
            } else {
                finalFile = File(resJson.getString("file_path"))
            }

            val finalUri = if (customFolderUri != null) {
                StorageHelper.exportToCustomTreeUri(context, finalFile, customFolderUri, isAudio)
            } else {
                if (isAudio) StorageHelper.exportAudioToPublic(context, finalFile)
                else StorageHelper.exportVideoToPublic(context, finalFile)
            }

            if (finalUri != null) {
                callback.onSuccess(finalUri.toString())
            } else {
                callback.onError("Không thể lưu file vào bộ nhớ thiết bị.")
            }

        } catch (e: PyException) {
            val msg = e.message ?: ""
            if (msg.contains("DownloadInterruptedException") || msg.contains("hủy")) {
                callback.onCanceled()
            } else {
                callback.onError(msg)
            }
        } catch (e: Exception) {
            callback.onError(e.localizedMessage ?: "Lỗi hệ thống")
        }
    }
}