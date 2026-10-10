package com.example.theshrimpapp.features.imageoptimizer.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.example.theshrimpapp.features.imageoptimizer.data.ImageOutputSaver
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import kotlinx.coroutines.CancellationException

@HiltViewModel
class ImageOptimizerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outputSaver: ImageOutputSaver
) : ViewModel() {

    private val contentResolver: ContentResolver get() = context.contentResolver

    private val _uiState = MutableStateFlow(ImageOptimizerUiState())
    val uiState: StateFlow<ImageOptimizerUiState> = _uiState.asStateFlow()

    fun selectOutputFolder(uri: Uri) {
        if (_uiState.value.isProcessing || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val folder = outputSaver.selectFolder(uri)
                _uiState.update { it.copy(outputFolderUri = folder.uri, outputFolderName = folder.name,
                    statusMessage = "Output folder: ${folder.name}") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = e.localizedMessage ?: "Cannot access output folder") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun useDefaultOutputFolder() {
        if (!_uiState.value.isProcessing) {
            _uiState.update { it.copy(outputFolderUri = null, outputFolderName = null) }
        }
    }

    fun saveImage(uri: Uri) {
        val state = _uiState.value
        if (state.isProcessing || state.isLoading || state.optionsError != null) return
        val item = state.selectedImages.find { it.uri == uri } ?: return
        val options = getProcessingOptions() ?: return
        _uiState.update { it.copy(isProcessing = true, statusMessage = "Processing ${item.name ?: "image"}...") }
        viewModelScope.launch {
            try {
                val result = outputSaver.save(uri, item.name, state.outputFolderUri,
                    state.selectedFolderUri, options)
                _uiState.update { it.copy(statusMessage = when (result) {
                    is SaveImageResult.Saved -> "Saved ${result.name} (${result.details.outputSizeBytes} bytes). Originals kept."
                    is SaveImageResult.Unchanged -> "No lossless savings: ${result.reason}"
                    is SaveImageResult.Failed -> "Save failed: ${result.error}"
                }) }
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    fun selectFolder(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, statusMessage = "Loading folder contents...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Some providers do not support persistable permissions
            }

            try {
                val docFile = DocumentFile.fromTreeUri(context, uri)
                if (docFile == null || !docFile.canRead()) {
                    _uiState.update { it.copy(isLoading = false, statusMessage = "Error: Cannot read selected folder") }
                    return@launch
                }

                val folderName = docFile.name ?: "Selected Folder"
                val imageItems = mutableListOf<ImageItem>()

                docFile.listFiles().forEach { file ->
                    if (file.isFile) {
                        val name = file.name ?: "Unknown"
                        val mimeType = file.type
                        val size = file.length()
                        val isSupported = (mimeType?.startsWith("image/") == true) ||
                            name.endsWith(".jpg", ignoreCase = true) ||
                            name.endsWith(".jpeg", ignoreCase = true) ||
                            name.endsWith(".png", ignoreCase = true) ||
                            name.endsWith(".webp", ignoreCase = true)

                        if (isSupported) {
                            imageItems.add(
                                ImageItem(
                                    uri = file.uri,
                                    name = name,
                                    sizeBytes = if (size > 0) size else null,
                                    mimeType = mimeType ?: "image/*"
                                )
                            )
                        }
                    }
                }

                _uiState.update { currentState ->
                    val existingUris = currentState.selectedImages.map { it.uri }.toSet()
                    val uniqueNewItems = imageItems.filter { it.uri !in existingUris }
                    val combined = currentState.selectedImages + uniqueNewItems
                    val updatedList = combined.take(2000)
                    val wasCapped = combined.size > 2000
                    currentState.copy(
                        isLoading = false,
                        selectedFolderUri = uri,
                        selectedFolderName = folderName,
                        selectedImages = updatedList,
                        selectedImagesCount = updatedList.size,
                        statusMessage = if (wasCapped) {
                            "Loaded folder '$folderName': capped at 2000 files max"
                        } else {
                            "Loaded folder '$folderName': found ${imageItems.size} supported image(s)"
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, statusMessage = "Error loading folder: ${e.localizedMessage ?: "Unknown error"}") }
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        _uiState.update { it.copy(isLoading = true, statusMessage = "Loading selected images...") }
        viewModelScope.launch(Dispatchers.IO) {
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
                    // Ignore query failures
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
                val combined = currentState.selectedImages + uniqueNewItems
                val updatedList = combined.take(2000)
                val wasCapped = combined.size > 2000
                currentState.copy(
                    isLoading = false,
                    selectedImages = updatedList,
                    selectedImagesCount = updatedList.size,
                    statusMessage = if (wasCapped) {
                        "Selected ${updatedList.size} images (maximum 2000 allowed)"
                    } else if (updatedList.isEmpty()) {
                        "Ready to select images"
                    } else {
                        "Selected ${updatedList.size} image(s)"
                    }
                )
            }
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
                selectedFolderUri = null,
                selectedFolderName = null,
                selectedImages = emptyList(),
                selectedImagesCount = 0,
                statusMessage = "Ready to select images"
            )
        }
    }

    fun updateProcessingMode(mode: ProcessingMode) {
        _uiState.update { it.copy(processingMode = mode, optionsError = null) }
    }

    fun updateMaxDimension(dimension: Int) {
        val clamped = dimension.coerceIn(1, 10000)
        _uiState.update {
            it.copy(
                maxDimension = clamped,
                maxDimensionInput = clamped.toString(),
                optionsError = null
            )
        }
    }

    fun updateMaxDimensionInput(input: String) {
        val parsed = input.toIntOrNull()
        if (parsed != null && parsed in 1..10000) {
            _uiState.update {
                it.copy(
                    maxDimension = parsed,
                    maxDimensionInput = input,
                    optionsError = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    maxDimensionInput = input,
                    optionsError = "Max dimension must be between 1 and 10,000 px"
                )
            }
        }
    }

    fun updateOutputFormat(format: OutputFormat) {
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun updateQuality(quality: Int) {
        val clamped = quality.coerceIn(1, 100)
        _uiState.update {
            it.copy(
                quality = clamped,
                qualityInput = clamped.toString(),
                optionsError = null
            )
        }
    }

    fun updateQualityInput(input: String) {
        val parsed = input.toIntOrNull()
        if (parsed != null && parsed in 1..100) {
            _uiState.update {
                it.copy(
                    quality = parsed,
                    qualityInput = input,
                    optionsError = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    qualityInput = input,
                    optionsError = "Quality must be between 1 and 100"
                )
            }
        }
    }

    fun updateJpegBackgroundColor(color: JpegBackgroundColor) {
        _uiState.update { it.copy(jpegBackgroundColor = color) }
    }

    fun updatePreserveMetadata(preserve: Boolean) {
        _uiState.update { it.copy(preserveMetadata = preserve) }
    }

    fun getProcessingOptions(): ImageProcessingOptions? {
        val state = _uiState.value
        val options = ImageProcessingOptions(
            processingMode = state.processingMode,
            maxDimension = state.maxDimension,
            outputFormat = state.outputFormat,
            quality = state.quality,
            backgroundColor = state.jpegBackgroundColor.color,
            preserveMetadata = state.preserveMetadata
        )
        return if (options.isValid()) options else null
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
