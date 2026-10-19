package com.nosdrg.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

object StorageHelper {

    fun exportToCustomTreeUri(context: Context, sourceFile: File, treeUri: Uri, isAudio: Boolean): Uri? {
        return try {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
            val mimeType = if (isAudio) "audio/mp4" else "video/mp4"
            val newFile = pickedDir.createFile(mimeType, sourceFile.name) ?: return null

            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                FileInputStream(sourceFile).use { it.copyTo(out) }
            }
            sourceFile.delete()
            newFile.uri
        } catch (e: Exception) {
            null
        }
    }

    fun exportVideoToPublic(context: Context, sourceFile: File, displayName: String? = null): Uri? {
        val fileName = if (!displayName.isNullOrBlank()) "$displayName.mp4" else sourceFile.name
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MyDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(sourceFile).use { inputStream -> inputStream.copyTo(outputStream) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        sourceFile.delete()
        return uri
    }

    fun exportAudioToPublic(context: Context, sourceFile: File, displayName: String? = null): Uri? {
        val fileName = if (!displayName.isNullOrBlank()) "$displayName.m4a" else sourceFile.name
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MyDownloader")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(sourceFile).use { inputStream -> inputStream.copyTo(outputStream) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }

        sourceFile.delete()
        return uri
    }

    fun openDownloadFolder(context: Context, customFolderUri: Uri?, isAudio: Boolean) {
        try {
            val intent = if (customFolderUri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(customFolderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                val subFolder = if (isAudio) "Music%2FMyDownloader" else "Movies%2FMyDownloader"
                val folderUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A$subFolder")
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(folderUri, "resource/folder")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                // Fallback mở trình xem file mặc định
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse("content://media/external/file"), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "Không tìm thấy trình quản lý tệp trên thiết bị", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun playMediaFile(context: Context, fileUri: String, isAudio: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(fileUri), if (isAudio) "audio/*" else "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Không tìm thấy ứng dụng phát tệp này", Toast.LENGTH_SHORT).show()
        }
    }
}