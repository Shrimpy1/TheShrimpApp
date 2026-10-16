package com.example.theshrimpapp.features.imageoptimizer.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.theshrimpapp.features.imageoptimizer.data.ImageOutputSaver
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchProgress
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchStatus
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImageOptimizerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outputSaver: ImageOutputSaver,
    private val batchRepository: com.example.theshrimpapp.features.imageoptimizer.background.BatchRepository
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
                _uiState.update {
                    it.copy(
                        outputFolderUri = folder.uri, outputFolderName = folder.name,
                        statusMessage = "Output folder: ${folder.name}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        statusMessage = e.localizedMessage ?: "Cannot access output folder"
                    )
                }
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

    init {
        viewModelScope.launch {
            batchRepository.latest.collect { stored ->
                if (stored != null) {
                    val job = stored.job
                    val items = stored.items.sortedBy { it.position }
                    val status = BatchStatus.valueOf(job.state)
                    fun image(item: com.example.theshrimpapp.features.imageoptimizer.background.BatchItem) =
                        ImageItem(
                            uri = item.originalUri.toUri(),
                            name = item.name,
                            sizeBytes = if (item.originalBytes > 0) item.originalBytes else null
                        )

                    val results =
                        items.filter { it.terminal && it.state != "CANCELLED" }.map { item ->
                            val result: SaveImageResult = when (item.state) {
                                "SAVED" -> SaveImageResult.Saved(
                                    Uri.parse(item.outputUri),
                                    item.outputName ?: "Output",
                                    item.details(job.options().processingMode)
                                )

                                "UNCHANGED" -> SaveImageResult.Unchanged(
                                    item.reason ?: "No lossless savings"
                                )

                                else -> SaveImageResult.Failed(item.reason ?: "Processing failed")
                            }
                            com.example.theshrimpapp.features.imageoptimizer.domain.BatchItemResult(
                                image(item),
                                result
                            )
                        }
                    val progress = BatchProgress(
                        status, items.size,
                        if (status in listOf(BatchStatus.PROCESSING, BatchStatus.CANCELLING))
                            items.filter { it.state == "RUNNING" || it.state == "PREPARED" }
                                .map(::image) else emptyList(), results
                    )
                    _uiState.update { current ->
                        val restoreJob = current.batchJobId == null || progress.isActive
                        val resolvedSourceFolderName = when {
                            job.sourceFolderName != null -> job.sourceFolderName
                            current.selectedFolderName != null && current.selectedFolderUri == job.sourceTree?.let(
                                Uri::parse
                            ) -> current.selectedFolderName

                            job.sourceTree != null -> {
                                try {
                                    DocumentFile.fromTreeUri(context, job.sourceTree.toUri())?.name
                                        ?: "Authorized source folder"
                                } catch (_: Exception) {
                                    "Authorized source folder"
                                }
                            }

                            else -> null
                        }
                        val resolvedOutputFolderName = when {
                            job.outputFolderName != null -> job.outputFolderName
                            current.outputFolderName != null && current.outputFolderUri == job.outputTree?.let(
                                Uri::parse
                            ) -> current.outputFolderName

                            job.outputTree != null -> {
                                try {
                                    DocumentFile.fromTreeUri(context, job.outputTree.toUri())?.name
                                        ?: "Authorized output folder"
                                } catch (_: Exception) {
                                    "Authorized output folder"
                                }
                            }

                            else -> null
                        }

                        current.copy(
                            batch = progress, batchJobId = job.id,
                            isProcessing = progress.isActive,
                            selectedImages = if (current.batchJobId == null || progress.isActive) items.map(
                                ::image
                            ) else current.selectedImages,
                            selectedImagesCount = if (current.batchJobId == null || progress.isActive) items.size else current.selectedImagesCount,
                            concurrency = job.concurrency,
                            processingMode = if (restoreJob) job.options().processingMode else current.processingMode,
                            maxDimension = if (restoreJob) job.dimension else current.maxDimension,
                            maxDimensionInput = if (restoreJob) job.dimension.toString() else current.maxDimensionInput,
                            keepOriginalSize = if (restoreJob) job.keepOriginal else current.keepOriginalSize,
                            outputFormat = if (restoreJob) job.options().outputFormat else current.outputFormat,
                            quality = if (restoreJob) job.quality else current.quality,
                            preserveMetadata = if (restoreJob) job.preserveMetadata else current.preserveMetadata,
                            outputFolderUri = if (restoreJob) job.outputTree?.let(Uri::parse) else current.outputFolderUri,
                            outputFolderName = if (restoreJob && job.outputTree != null) resolvedOutputFolderName else current.outputFolderName,
                            selectedFolderUri = if (restoreJob) job.sourceTree?.let(Uri::parse) else current.selectedFolderUri,
                            selectedFolderName = if (restoreJob && job.sourceTree != null) resolvedSourceFolderName else current.selectedFolderName,
                            jpegBackgroundColor = if (restoreJob) JpegBackgroundColor.entries.firstOrNull {
                                it.color.value.toLong() == job.background
                            } ?: JpegBackgroundColor.WHITE else current.jpegBackgroundColor,
                            optionsError = if (restoreJob) null else current.optionsError,
                            statusMessage = when (status) {
                                BatchStatus.QUEUED -> "Batch queued for background execution"
                                BatchStatus.PROCESSING -> "Processing ${progress.completedCount} of ${progress.totalCount}"
                                BatchStatus.CANCELLING -> "Cancelling after active images finish..."
                                BatchStatus.INTERRUPTED -> "Execution interrupted; Android will resume this batch. ${job.error.orEmpty()}"
                                BatchStatus.COMPLETED -> "Batch completed. Originals kept."
                                BatchStatus.CANCELLED -> "Batch cancelled. Outputs kept; ${progress.remainingCount} image(s) not processed." +
                                        if (items.any { it.reason == "Incomplete output could not be removed." }) " An incomplete output could not be removed." else ""

                                BatchStatus.FAILED -> job.error ?: "Batch failed"
                                BatchStatus.IDLE -> "Ready to process images"
                            }
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            try {
                batchRepository.restore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Could not restore background work: ${e.localizedMessage}") }
            }
        }
    }

    fun updateConcurrency(limit: Int) {
        if (_uiState.value.isProcessing) return
        _uiState.update {
            it.copy(
                concurrency = limit.coerceIn(
                    1,
                    com.example.theshrimpapp.features.imageoptimizer.domain.MAX_BATCH_CONCURRENCY
                )
            )
        }
    }

    fun processBatch() {
        val state = _uiState.value
        if (state.isProcessing || state.isLoading || state.optionsError != null || state.selectedImages.isEmpty()) return
        val options = getProcessingOptions() ?: return
        _uiState.update {
            it.copy(
                isLoading = true,
                statusMessage = "Preparing background batch..."
            )
        }
        viewModelScope.launch {
            try {
                batchRepository.submit(
                    inputs = state.selectedImages.map {
                        com.example.theshrimpapp.features.imageoptimizer.background.BatchInput(
                            it.uri,
                            it.name,
                            it.sizeBytes
                        )
                    },
                    output = state.outputFolderUri,
                    source = state.selectedFolderUri,
                    options = options,
                    concurrency = state.concurrency,
                    sourceFolderName = state.selectedFolderName,
                    outputFolderName = state.outputFolderName
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Cannot submit batch: ${e.localizedMessage}") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun cancelBatch() {
        val id = _uiState.value.batchJobId ?: return
        viewModelScope.launch {
            try {
                batchRepository.cancel(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(statusMessage = "Could not cancel batch: ${e.localizedMessage}") }
            }
        }
    }

    fun selectFolder(uri: Uri) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(isLoading = true, statusMessage = "Loading folder contents...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val flags =
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {
                // Some providers do not support persistable permissions
            }

            try {
                val docFile = DocumentFile.fromTreeUri(context, uri)
                if (docFile == null || !docFile.canRead()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Error: Cannot read selected folder"
                        )
                    }
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
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Error loading folder: ${e.localizedMessage ?: "Unknown error"}"
                    )
                }
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        if (_uiState.value.isProcessing) return
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
        if (_uiState.value.isProcessing) return
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
        if (_uiState.value.isProcessing) return
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
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(processingMode = mode, optionsError = null) }
    }

    fun updateMaxDimension(dimension: Int) {
        if (_uiState.value.isProcessing) return
        val clamped = dimension.coerceIn(1, 10000)
        _uiState.update {
            it.copy(
                maxDimension = clamped,
                keepOriginalSize = false,
                maxDimensionInput = clamped.toString(),
                optionsError = null
            )
        }
    }

    fun updateMaxDimensionInput(input: String) {
        if (_uiState.value.isProcessing) return
        val parsed = input.toIntOrNull()
        if (parsed != null && parsed in 1..10000) {
            _uiState.update {
                it.copy(
                    maxDimension = parsed,
                    keepOriginalSize = false,
                    maxDimensionInput = input,
                    optionsError = null
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    maxDimensionInput = input,
                    keepOriginalSize = false,
                    optionsError = "Max dimension must be between 1 and 10,000 px"
                )
            }
        }
    }

    fun updateOutputFormat(format: OutputFormat) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(outputFormat = format) }
    }

    fun updateKeepOriginalSize(keep: Boolean) {
        if (_uiState.value.isProcessing) return
        _uiState.update {
            it.copy(
                keepOriginalSize = keep, optionsError = if (keep ||
                    it.maxDimensionInput.toIntOrNull() in 1..10000
                ) null
                else "Max dimension must be between 1 and 10,000 px"
            )
        }
    }

    fun updateQuality(quality: Int) {
        if (_uiState.value.isProcessing) return
        val clamped = quality.coerceIn(1, 100)
        _uiState.update {
            it.copy(
                quality = clamped
            )
        }
    }

    fun updateJpegBackgroundColor(color: JpegBackgroundColor) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(jpegBackgroundColor = color) }
    }

    fun updatePreserveMetadata(preserve: Boolean) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(preserveMetadata = preserve) }
    }

    fun getProcessingOptions(): ImageProcessingOptions? {
        val state = _uiState.value
        val options = ImageProcessingOptions(
            processingMode = state.processingMode,
            maxDimension = state.maxDimension,
            keepOriginalSize = state.keepOriginalSize,
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
