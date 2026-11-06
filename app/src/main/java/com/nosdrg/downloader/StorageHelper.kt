package com.nosdrg.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream

object StorageHelper {

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun getMimeType(file: File, isAudio: Boolean): String {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!mime.isNullOrBlank()) return mime
        return if (isAudio) "audio/*" else "video/*"
    }

    fun exportAudioToPublic(context: Context, srcFile: File, title: String): Uri? {
        if (!srcFile.exists()) return null
        val safeTitle = sanitizeFileName(title).ifBlank { "Audio_${System.currentTimeMillis()}" }

        val rawExt = srcFile.extension.lowercase()
        val ext = if (rawExt in setOf("mp4", "m4v")) "m4a" else rawExt.ifBlank { "m4a" }
        val fileName = "$safeTitle.$ext"
        val mimeType = if (ext == "m4a") "audio/mp4" else getMimeType(srcFile, true)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MediaDownloader")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(srcFile).use { input -> input.copyTo(out) }
                out.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            srcFile.delete()
            scanMedia(context, uri)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun exportVideoToPublic(context: Context, srcFile: File, title: String): Uri? {
        if (!srcFile.exists()) return null
        val safeTitle = sanitizeFileName(title).ifBlank { "Video_${System.currentTimeMillis()}" }
        val ext = srcFile.extension.lowercase().ifBlank { "mp4" }
        val fileName = "$safeTitle.$ext"
        val mimeType = getMimeType(srcFile, false)

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MediaDownloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collection, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(srcFile).use { input -> input.copyTo(out) }
                out.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            srcFile.delete()
            scanMedia(context, uri)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun exportToCustomTreeUri(context: Context, srcFile: File, title: String, treeUri: Uri, isAudio: Boolean): Uri? {
        if (!srcFile.exists()) return null
        return try {
            val safeTitle = sanitizeFileName(title).ifBlank { "Media_${System.currentTimeMillis()}" }
            val rawExt = srcFile.extension.lowercase()
            val ext = if (isAudio && rawExt in setOf("mp4", "m4v")) "m4a" else srcFile.extension.ifBlank { if (isAudio) "m4a" else "mp4" }
            val mimeType = if (isAudio && ext == "m4a") "audio/mp4" else getMimeType(srcFile, isAudio)
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: return null

            val targetFile = pickedDir.createFile(mimeType, "$safeTitle.$ext") ?: return null
            context.contentResolver.openOutputStream(targetFile.uri)?.use { out ->
                FileInputStream(srcFile).use { input -> input.copyTo(out) }
                out.flush()
            }
            srcFile.delete()
            targetFile.uri
        } catch (e: Exception) {
            null
        }
    }

    fun exportSubtitleToPublic(context: Context, subFile: File, baseName: String, customFolderUri: Uri? = null) {
        if (!subFile.exists()) return
        val safeTitle = sanitizeFileName(baseName)
        val fileName = "$safeTitle.vtt"

        if (customFolderUri != null) {
            try {
                val pickedDir = DocumentFile.fromTreeUri(context, customFolderUri) ?: return
                val newFile = pickedDir.createFile("text/vtt", fileName) ?: return
                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    FileInputStream(subFile).use { it.copyTo(out) }
                }
                subFile.delete()
            } catch (_: Exception) {}
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/vtt")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/MediaDownloader")
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val uri = context.contentResolver.insert(collection, values) ?: return
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(subFile).use { it.copyTo(out) }
            }
            subFile.delete()
        } catch (_: Exception) {}
    }

    private fun scanMedia(context: Context, uri: Uri) {
        try {
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val path = cursor.getString(0)
                    if (!path.isNullOrBlank()) {
                        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun playMediaFile(context: Context, uriString: String, isAudio: Boolean) {
        if (uriString.isBlank()) {
            Toast.makeText(context, "Khong tim thay duong dan tep", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = Uri.parse(uriString)
            val mimeType = if (isAudio) "audio/*" else "video/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Chon ung dung de phat").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(context, "Khong co ung dung ho tro phat tep nay", Toast.LENGTH_SHORT).show()
        }
    }

    fun openDownloadFolder(context: Context, customFolderUri: Uri?, isAudio: Boolean) {
        try {
            if (customFolderUri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(customFolderUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(
                    if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    if (isAudio) "vnd.android.cursor.dir/audio" else "vnd.android.cursor.dir/video"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Hay kiem tra tep trong thu muc Music hoac Movies tren may", Toast.LENGTH_LONG).show()
        }
    }
}