package com.nosdrg.downloader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DownloadQueueBus {
    private val _queue = MutableStateFlow<List<DownloadTask>>(emptyList())
    val queue = _queue.asStateFlow()

    fun enqueue(task: DownloadTask) {
        _queue.update { it + task }
    }

    fun removeTask(taskId: String) {
        _queue.update { list -> list.filterNot { it.id == taskId } }
    }

    fun getNextWaitingTask(): DownloadTask? {
        return _queue.value.firstOrNull { it.status == QueueStatus.WAITING }
    }

    fun updateTaskProgress(taskId: String, progress: Float, speed: String, currentTitle: String = "") {
        _queue.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    val displayTitle = if (currentTitle.isNotBlank()) currentTitle else task.title
                    task.copy(
                        title = displayTitle,
                        progress = progress,
                        speed = speed,
                        status = QueueStatus.DOWNLOADING
                    )
                } else task
            }
        }
    }

    fun setTaskSuccess(taskId: String, outputUri: String) {
        _queue.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(status = QueueStatus.SUCCESS, progress = 100f, outputUri = outputUri)
                } else task
            }
        }
    }

    fun setTaskFailed(taskId: String, error: String) {
        _queue.update { list ->
            list.map { task ->
                if (task.id == taskId) {
                    task.copy(status = QueueStatus.FAILED, errorMessage = error)
                } else task
            }
        }
    }

    fun setTaskCanceled(taskId: String) {
        _queue.update { list ->
            list.map { task ->
                if (task.id == taskId) task.copy(status = QueueStatus.CANCELED) else task
            }
        }
    }

    fun clearCompleted() {
        _queue.update { list ->
            list.filter { it.status == QueueStatus.DOWNLOADING || it.status == QueueStatus.WAITING }
        }
    }
}