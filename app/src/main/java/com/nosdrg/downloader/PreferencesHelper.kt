package com.nosdrg.downloader

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object PreferencesHelper {
    private const val PREF_NAME = "downloader_prefs"
    private const val KEY_FOLDER_URI = "key_custom_folder_uri"
    private const val KEY_FOLDER_NAME = "key_custom_folder_name"
    private const val KEY_HAS_COOKIE = "key_has_cookie"

    fun saveCustomFolder(context: Context, uri: Uri?, displayName: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (uri != null) {
                putString(KEY_FOLDER_URI, uri.toString())
                putString(KEY_FOLDER_NAME, displayName)
            } else {
                remove(KEY_FOLDER_URI)
                remove(KEY_FOLDER_NAME)
            }
            apply()
        }
    }

    fun getCustomFolderUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return runCatching { Uri.parse(uriStr) }.getOrNull()
    }

    fun getCustomFolderDisplayName(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_FOLDER_NAME, "Mac dinh (Music/Movies)") ?: "Mac dinh (Music/Movies)"
    }

    fun importCookieFile(context: Context, uri: Uri): Boolean {
        return try {
            val destinationFile = File(context.filesDir, "cookies.txt")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HAS_COOKIE, true).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun removeCookieFile(context: Context) {
        val file = File(context.filesDir, "cookies.txt")
        if (file.exists()) file.delete()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HAS_COOKIE, false).apply()
    }

    fun getCookieFilePath(context: Context): String? {
        val file = File(context.filesDir, "cookies.txt")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun hasCookie(context: Context): Boolean {
        val file = File(context.filesDir, "cookies.txt")
        return file.exists() && file.length() > 0
    }
}