package com.example.theshrimpapp.features.imageoptimizer.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class ImageOptimizerViewModel @Inject constructor(
    private val contentResolver: ContentResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImageOptimizerUiState())
    val uiState: StateFlow<ImageOptimizerUiState> = _uiState.asStateFlow()

    fun addImages(uris: List<Uri>) {
        val newItems = uris.map { uri ->
            var name: String? = null
            var sizeBytes: Long? = null
            try {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {
                // Ignore query failures, fallback to uri last path segment
            }
            if (name == null) {
                name = uri.lastPathSegment ?: "Unknown image"
            }
            val mimeType = contentResolver.getType(uri)
            ImageItem(uri = uri, name = name, sizeBytes = sizeBytes, mimeType = mimeType)
        }

        _uiState.update { currentState ->
            val existingUris = currentState.selectedImages.map { it.uri }.toSet()
            val uniqueNewItems = newItems.filter { it.uri !in existingUris }
            val updatedList = currentState.selectedImages + uniqueNewItems
            currentState.copy(
                selectedImages = updatedList,
                selectedImagesCount = updatedList.size,
                statusMessage = if (updatedList.isEmpty()) "Ready to select images" else "Selected ${updatedList.size} image(s)"
            )
        }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { currentState ->
            val updatedList = currentState.selectedImages.filter { it.uri != uri }
            currentState.copy(
                selectedImages = updatedList,
                selectedImagesCount = updatedList.size,
                statusMessage = if (updatedList.isEmpty()) "Ready to select images" else "Selected ${updatedList.size} image(s)"
            )
        }
    }

    fun clearImages() {
        _uiState.update { currentState ->
            currentState.copy(
                selectedImages = emptyList(),
                selectedImagesCount = 0,
                statusMessage = "Ready to select images"
            )
        }
    }

    fun updateMaxDimension(dimension: Int) {
        _uiState.update { it.copy(maxDimension = dimension) }
    }

    fun updateOutputFormat(format: String) {
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun updateQuality(quality: Int) {
        _uiState.update { it.copy(quality = quality) }
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
