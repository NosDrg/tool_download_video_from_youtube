package com.nosdrg.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
    private var isQueueLoopRunning = false
    private var currentActiveTaskId: String? = null

    companion object {
        const val CHANNEL_ID = "channel_media_download"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PROCESS_QUEUE = "ACTION_PROCESS_QUEUE"
        const val ACTION_STOP_CURRENT = "ACTION_STOP_CURRENT"
    }

    override fun onCreate() {
        super.onCreate()
        repository = DownloaderRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROCESS_QUEUE -> {
                if (!isQueueLoopRunning) {
                    isQueueLoopRunning = true
                    startQueueProcessing()
                }
            }
            ACTION_STOP_CURRENT -> {
                cancelToken?.cancel()
                currentActiveTaskId?.let { DownloadQueueBus.setTaskCanceled(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startQueueProcessing() {
        val initialNotif = buildNotification(0, "Hàng đợi", "Đang khởi tạo...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        serviceScope.launch {
            while (true) {
                val nextTask = DownloadQueueBus.getNextWaitingTask()
                if (nextTask == null) {
                    isQueueLoopRunning = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }

                currentActiveTaskId = nextTask.id
                val token = CancelToken()
                cancelToken = token

                repository.downloadMedia(
                    url = nextTask.url,
                    formatItem = nextTask.formatItem,
                    downloadPlaylist = nextTask.downloadPlaylist,
                    subLang = nextTask.subLang,
                    customFolderUri = nextTask.customFolderUri,
                    cancelToken = token,
                    callback = object : DownloaderRepository.DownloadCallback {
                        override fun onProgress(percent: Float, speed: String, currentTitle: String) {
                            DownloadQueueBus.updateTaskProgress(nextTask.id, percent, speed, currentTitle)
                            val title = if (currentTitle.isNotBlank()) currentTitle else nextTask.title
                            notificationManager.notify(NOTIFICATION_ID, buildNotification(percent.toInt(), speed, title))
                        }

                        override fun onSuccess(fileUri: String) {
                            DownloadQueueBus.setTaskSuccess(nextTask.id, fileUri)
                            showCompletedNotification("Đã tải xong", nextTask.title)
                        }

                        override fun onError(error: String) {
                            DownloadQueueBus.setTaskFailed(nextTask.id, error)
                        }

                        override fun onCanceled() {
                            DownloadQueueBus.setTaskCanceled(nextTask.id)
                        }
                    }
                )
            }
        }
    }

    private fun buildNotification(progress: Int, speed: String, title: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_STOP_CURRENT
        }
        val pStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("$progress% - $speed")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setContentIntent(pOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng bài này", pStop)
            .setSilent(true)
            .build()
    }

    private fun showCompletedNotification(title: String, message: String) {
        val pOpen = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pOpen)
            .build()
        notificationManager.notify(System.currentTimeMillis().toInt(), notif)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hàng đợi tải Media",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}