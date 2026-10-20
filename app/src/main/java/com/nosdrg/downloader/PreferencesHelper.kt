package com.nosdrg.downloader

import android.content.Context
import android.net.Uri

object PreferencesHelper {
    private const val PREF_NAME = "downloader_prefs"
    private const val KEY_FOLDER_URI = "key_custom_folder_uri"
    private const val KEY_FOLDER_NAME = "key_custom_folder_name"

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
        return prefs.getString(KEY_FOLDER_NAME, "Mặc định") ?: "Mặc định"
    }
}