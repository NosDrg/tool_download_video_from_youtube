package com.nosdrg.downloader

class CancelToken {
    @Volatile
    private var isCancelled = false

    fun cancel() {
        isCancelled = true
    }

    @Suppress("unused")
    fun isCancelled(): Boolean = isCancelled
}