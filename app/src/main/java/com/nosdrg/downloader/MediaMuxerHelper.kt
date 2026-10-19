package com.nosdrg.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object MediaMuxerHelper {

    suspend fun mux(videoFile: File, audioFile: File, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !audioFile.exists()) {
            return@withContext Result.failure(Exception("File video hoặc audio tạm không tồn tại"))
        }

        if (outputFile.exists()) outputFile.delete()

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // Track Video
            val vIndex = selectTrack(videoExtractor, isVideo = true)
            if (vIndex < 0) return@withContext Result.failure(Exception("Không tìm thấy luồng Video"))
            videoExtractor.selectTrack(vIndex)
            val muxerVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(vIndex))

            // Track Audio
            val aIndex = selectTrack(audioExtractor, isVideo = false)
            if (aIndex < 0) return@withContext Result.failure(Exception("Không tìm thấy luồng Audio"))
            audioExtractor.selectTrack(aIndex)
            val muxerAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(aIndex))

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            copyTrackData(videoExtractor, muxer, muxerVideoTrack, buffer, bufferInfo)
            copyTrackData(audioExtractor, muxer, muxerAudioTrack, buffer, bufferInfo)

            videoFile.delete()
            audioFile.delete()

            Result.success(outputFile)
        } catch (e: Exception) {
            outputFile.delete()
            Result.failure(e)
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        val target = if (isVideo) "video/" else "audio/"
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(target)) return i
        }
        return -1
    }

    private fun copyTrackData(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            extractor.advance()
        }
    }
}