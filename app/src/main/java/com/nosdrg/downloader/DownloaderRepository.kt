package com.nosdrg.downloader

import android.content.Context
import com.chaquo.python.PyException
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CancelToken {
    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    @Suppress("unused")
    fun isCancelled(): Boolean = isCancelled
}

class DownloaderRepository(private val context: Context) {

    interface DownloadCallback {
        fun onProgress(percent: Float, speed: String)
        fun onSuccess(fileUri: String)
        fun onError(error: String)
        fun onCanceled()
    }

    suspend fun downloadMedia(
        url: String,
        isAudioOnly: Boolean,
        resolution: String,
        cancelToken: CancelToken,
        callback: DownloadCallback
    ) = withContext(Dispatchers.IO) {
        try {
            val py = Python.getInstance()
            val bridge = py.getModule("downloader_bridge")
            val cacheDir = context.cacheDir.absolutePath

            val resultPath = bridge.callAttr(
                "download_media",
                url,
                cacheDir,
                isAudioOnly,
                resolution,
                object {
                    @Suppress("unused")
                    fun onProgress(percent: Float, speed: String) {
                        callback.onProgress(percent, speed)
                    }
                },
                cancelToken
            ).toString()

            val downloadedFile = File(resultPath)
            if (!downloadedFile.exists()) {
                callback.onError("Không tìm thấy file sau khi tải.")
                return@withContext
            }

            val finalUri = if (isAudioOnly) {
                StorageHelper.exportAudioToPublic(context, downloadedFile)
            } else {
                StorageHelper.exportVideoToPublic(context, downloadedFile)
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
            callback.onError(e.localizedMessage ?: "Lỗi ngoại lệ hệ thống.")
        }
    }
}