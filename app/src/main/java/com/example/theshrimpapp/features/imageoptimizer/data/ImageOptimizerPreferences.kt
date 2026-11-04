package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode
import com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageOptimizerPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("image_optimizer_prefs", Context.MODE_PRIVATE)

    var readNestedFolders: Boolean
        get() = prefs.getBoolean(KEY_READ_NESTED_FOLDERS, false)
        set(value) = prefs.edit { putBoolean(KEY_READ_NESTED_FOLDERS, value) }

    var folderImageSelectionMode: FolderImageSelectionMode
        get() {
            val name = prefs.getString(KEY_SELECTION_MODE, null)
                ?: return FolderImageSelectionMode.ALL_SUPPORTED
            return try {
                FolderImageSelectionMode.valueOf(name)
            } catch (_: Exception) {
                FolderImageSelectionMode.ALL_SUPPORTED
            }
        }
        set(value) = prefs.edit { putString(KEY_SELECTION_MODE, value.name) }

    var customSupportedFormats: Set<SupportedImageFormat>
        get() {
            val stringSet = prefs.getStringSet(KEY_CUSTOM_FORMATS, null)
            if (stringSet == null) {
                return SupportedImageFormat.entries.toSet()
            }
            return stringSet.mapNotNull { name ->
                try {
                    SupportedImageFormat.valueOf(name)
                } catch (_: Exception) {
                    null
                }
            }.toSet()
        }
        set(value) = prefs.edit { putStringSet(KEY_CUSTOM_FORMATS, value.map { it.name }.toSet()) }

    var filterByFileSize: Boolean
        get() = prefs.getBoolean(KEY_FILTER_BY_FILE_SIZE, false)
        set(value) = prefs.edit { putBoolean(KEY_FILTER_BY_FILE_SIZE, value) }

    var minFileSizeMbInput: String
        get() = prefs.getString(KEY_MIN_FILE_SIZE_MB, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MIN_FILE_SIZE_MB, value) }

    var maxFileSizeMbInput: String
        get() = prefs.getString(KEY_MAX_FILE_SIZE_MB, "") ?: ""
        set(value) = prefs.edit { putString(KEY_MAX_FILE_SIZE_MB, value) }

    companion object {
        private const val KEY_READ_NESTED_FOLDERS = "read_nested_folders"
        private const val KEY_SELECTION_MODE = "folder_image_selection_mode"
        private const val KEY_CUSTOM_FORMATS = "custom_supported_formats"
        private const val KEY_FILTER_BY_FILE_SIZE = "filter_by_file_size"
        private const val KEY_MIN_FILE_SIZE_MB = "min_file_size_mb_input"
        private const val KEY_MAX_FILE_SIZE_MB = "max_file_size_mb_input"
    }
}
