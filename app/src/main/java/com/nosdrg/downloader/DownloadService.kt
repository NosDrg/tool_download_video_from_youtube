package com.nosdrg.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: DownloaderRepository
    private var cancelToken: CancelToken? = null
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "channel_media_download"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ACTION_START_DOWNLOAD"
        const val ACTION_STOP = "ACTION_STOP_DOWNLOAD"

        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_FORMAT_ID = "EXTRA_FORMAT_ID"
        const val EXTRA_FORMAT_LABEL = "EXTRA_FORMAT_LABEL"
        const val EXTRA_FORMAT_SELECTOR = "EXTRA_FORMAT_SELECTOR"
        const val EXTRA_IS_AUDIO = "EXTRA_IS_AUDIO"
        const val EXTRA_DOWNLOAD_PLAYLIST = "EXTRA_DOWNLOAD_PLAYLIST"
        const val EXTRA_CUSTOM_FOLDER_URI = "EXTRA_CUSTOM_FOLDER_URI"
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloaderRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val formatId = intent.getStringExtra(EXTRA_FORMAT_ID) ?: ""
                val formatLabel = intent.getStringExtra(EXTRA_FORMAT_LABEL) ?: ""
                val formatSelector = intent.getStringExtra(EXTRA_FORMAT_SELECTOR) ?: "720"
                val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
                val downloadPlaylist = intent.getBooleanExtra(EXTRA_DOWNLOAD_PLAYLIST, false)
                val customFolderUriStr = intent.getStringExtra(EXTRA_CUSTOM_FOLDER_URI)
                val customFolderUri = customFolderUriStr?.let { Uri.parse(it) }

                val formatItem = FormatItem(
                    id = formatId,
                    label = formatLabel,
                    formatSelector = formatSelector,
                    isAudio = isAudio
                )

                startForegroundDownload(url, formatItem, downloadPlaylist, customFolderUri)
            }
            ACTION_STOP -> {
                cancelToken?.cancel()
                DownloadStateBus.setCanceled()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundDownload(
        url: String,
        formatItem: FormatItem,
        downloadPlaylist: Boolean,
        customFolderUri: Uri?
    ) {
        val token = CancelToken()
        cancelToken = token

        val initialNotification = buildNotification(0, "Đang kết nối...", "Đang chuẩn bị tải")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch {
            repository.downloadMedia(
                url = url,
                formatItem = formatItem,
                downloadPlaylist = downloadPlaylist,
                customFolderUri = customFolderUri,
                cancelToken = token,
                callback = object : DownloaderRepository.DownloadCallback {
                    override fun onProgress(percent: Float, speed: String, currentTitle: String) {
                        DownloadStateBus.updateProgress(percent, speed, currentTitle)
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            buildNotification(percent.toInt(), speed, currentTitle.ifBlank { "Đang tải xuống..." })
                        )
                    }

                    override fun onSuccess(fileUri: String) {
                        DownloadStateBus.setSuccess(fileUri)
                        showCompletedNotification("Tải hoàn tất!", "Tệp đã được lưu an toàn.")
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }

                    override fun onError(error: String) {
                        DownloadStateBus.setError(error)
                        showCompletedNotification("Tải thất bại", error)
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }

                    override fun onCanceled() {
                        DownloadStateBus.setCanceled()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            )
        }
    }

    private fun buildNotification(progress: Int, speed: String, contentTitle: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(contentTitle)
            .setContentText("$progress% • $speed")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hủy", pendingStop)
            .setSilent(true)
            .build()
    }

    private fun showCompletedNotification(title: String, message: String) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingOpenApp)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tác vụ tải Media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Hiển thị tiến độ và tốc độ tải video/audio"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}