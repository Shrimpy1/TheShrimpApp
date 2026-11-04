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
import com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizerDefaults
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizerLimits
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import com.example.theshrimpapp.features.imageoptimizer.domain.PostOptimizationPolicy
import com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat
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
    private val batchRepository: com.example.theshrimpapp.features.imageoptimizer.background.BatchRepository,
    private val preferences: com.example.theshrimpapp.features.imageoptimizer.data.ImageOptimizerPreferences
) : ViewModel() {

    companion object {
        /** Maximum image reading/selection limit shared across folder scan and file pickers. */
        var MAX_FILE_LIMIT: Int
            get() = ImageOptimizerDefaults.MAX_FILE_LIMIT
            set(value) { ImageOptimizerDefaults.MAX_FILE_LIMIT = value }

        /** Default max dimension for image resizing (in pixels). */
        var DEFAULT_MAX_DIMENSION: Int
            get() = ImageOptimizerDefaults.DEFAULT_MAX_DIMENSION
            set(value) { ImageOptimizerDefaults.DEFAULT_MAX_DIMENSION = value }

        /** Default compression quality (1-100). */
        var DEFAULT_QUALITY: Int
            get() = ImageOptimizerDefaults.DEFAULT_QUALITY
            set(value) { ImageOptimizerDefaults.DEFAULT_QUALITY = value }

        /** Default parallel processing threads. */
        var DEFAULT_CONCURRENCY: Int
            get() = ImageOptimizerDefaults.DEFAULT_CONCURRENCY
            set(value) { ImageOptimizerDefaults.DEFAULT_CONCURRENCY = value }

        /** Quick preset options for max dimension resizing (in pixels). */
        var DIMENSION_PRESETS: List<Int>
            get() = ImageOptimizerDefaults.DIMENSION_PRESETS
            set(value) { ImageOptimizerDefaults.DIMENSION_PRESETS = value }

        const val MIN_DIMENSION: Int = ImageOptimizerLimits.MIN_DIMENSION
        const val MAX_DIMENSION: Int = ImageOptimizerLimits.MAX_DIMENSION
        const val MIN_QUALITY: Int = ImageOptimizerLimits.MIN_QUALITY
        const val MAX_QUALITY: Int = ImageOptimizerLimits.MAX_QUALITY
        const val MIN_CONCURRENCY: Int = ImageOptimizerLimits.MIN_CONCURRENCY
        const val MAX_CONCURRENCY: Int = ImageOptimizerLimits.MAX_CONCURRENCY
    }

    private val contentResolver: ContentResolver get() = context.contentResolver

    private val _uiState = MutableStateFlow(
        ImageOptimizerUiState(
            readNestedFolders = preferences.readNestedFolders,
            folderImageSelectionMode = preferences.folderImageSelectionMode,
            customSupportedFormats = preferences.customSupportedFormats,
            filterByFileSize = preferences.filterByFileSize,
            minFileSizeMbInput = preferences.minFileSizeMbInput,
            maxFileSizeMbInput = preferences.maxFileSizeMbInput
        )
    )
    val uiState: StateFlow<ImageOptimizerUiState> = _uiState.asStateFlow()

    fun updateDestinationMode(mode: com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(destinationMode = mode) }
    }

    fun selectOutputFolder(uri: Uri) {
        if (_uiState.value.isProcessing || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val folder = outputSaver.selectFolder(uri)
                _uiState.update {
                    it.copy(
                        outputFolderUri = folder.uri, outputFolderName = folder.name,
                        destinationMode = com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode.CUSTOM_FOLDER,
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
            _uiState.update {
                it.copy(
                    outputFolderUri = null, outputFolderName = null,
                    destinationMode = com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode.SUBFOLDER
                )
            }
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
                            sizeBytes = if (item.originalBytes > 0) item.originalBytes else null,
                            sourceFolderUri = job.sourceTree?.toUri(),
                            sourceFolderName = job.sourceFolderName,
                            parentFolderUri = item.sourceTreeUri?.toUri()
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
                        val wasProcessing = current.isProcessing
                        val justFinished = wasProcessing && !progress.isActive
                        val initialLoadOfCompleted =
                            current.batchJobId == null && !progress.isActive
                        val shouldWipeSelection = justFinished || initialLoadOfCompleted

                        val restoreJob = current.batchJobId == null || progress.isActive
                        val isDismissed = current.dismissedBatchId == job.id
                        val showPrevious = if (progress.isActive) {
                            false
                        } else {
                            if (isDismissed) false else (current.showPreviousBatch || justFinished || initialLoadOfCompleted)
                        }

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

                        val restoredSelectionMode = try {
                            FolderImageSelectionMode.valueOf(job.folderImageSelectionMode)
                        } catch (_: Exception) {
                            FolderImageSelectionMode.ALL_SUPPORTED
                        }
                        val restoredCustomFormats = job.customFormatsSet()

                        if (restoreJob) {
                            preferences.readNestedFolders = job.readNestedFolders
                            preferences.folderImageSelectionMode = restoredSelectionMode
                            preferences.customSupportedFormats = restoredCustomFormats
                            preferences.filterByFileSize = job.filterByFileSize
                            preferences.minFileSizeMbInput = job.minFileSizeMbInput
                            preferences.maxFileSizeMbInput = job.maxFileSizeMbInput
                        }

                        current.copy(
                            batch = progress, batchJobId = job.id,
                            isProcessing = progress.isActive,
                            showPreviousBatch = showPrevious,
                            selectedImages = when {
                                progress.isActive -> items.map(::image)
                                shouldWipeSelection -> emptyList()
                                else -> current.selectedImages
                            },
                            selectedImagesCount = when {
                                progress.isActive -> items.size
                                shouldWipeSelection -> 0
                                else -> current.selectedImagesCount
                            },
                            selectedFolders = when {
                                progress.isActive -> current.selectedFolders
                                shouldWipeSelection -> emptyList()
                                else -> current.selectedFolders
                            },
                            readNestedFolders = if (restoreJob) job.readNestedFolders else current.readNestedFolders,
                            folderImageSelectionMode = if (restoreJob) restoredSelectionMode else current.folderImageSelectionMode,
                            customSupportedFormats = if (restoreJob) restoredCustomFormats else current.customSupportedFormats,
                            filterByFileSize = if (restoreJob) job.filterByFileSize else current.filterByFileSize,
                            minFileSizeMbInput = if (restoreJob) job.minFileSizeMbInput else current.minFileSizeMbInput,
                            maxFileSizeMbInput = if (restoreJob) job.maxFileSizeMbInput else current.maxFileSizeMbInput,
                            concurrency = job.concurrency,
                            destinationMode = if (restoreJob) try {
                                com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode.valueOf(
                                    job.destinationMode
                                )
                            } catch (_: Exception) {
                                com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode.SUBFOLDER
                            } else current.destinationMode,
                            processingMode = if (restoreJob) job.options().processingMode else current.processingMode,
                            maxDimension = if (restoreJob) job.dimension else current.maxDimension,
                            maxDimensionInput = if (restoreJob) job.dimension.toString() else current.maxDimensionInput,
                            keepOriginalSize = if (restoreJob) job.keepOriginal else current.keepOriginalSize,
                            outputFormat = if (restoreJob) job.options().outputFormat else current.outputFormat,
                            quality = if (restoreJob) job.quality else current.quality,
                            preserveMetadata = if (restoreJob) job.preserveMetadata else current.preserveMetadata,
                            postOptimizationPolicy = if (restoreJob) job.options().postOptimizationPolicy else current.postOptimizationPolicy,
                            outputFolderUri = if (restoreJob) job.outputTree?.let(Uri::parse) else current.outputFolderUri,
                            outputFolderName = if (restoreJob && job.outputTree != null) resolvedOutputFolderName else current.outputFolderName,
                            selectedFolderUri = if (shouldWipeSelection) null else if (restoreJob) job.sourceTree?.let(
                                Uri::parse
                            ) else current.selectedFolderUri,
                            selectedFolderName = if (shouldWipeSelection) null else if (restoreJob && job.sourceTree != null) resolvedSourceFolderName else current.selectedFolderName,
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
                statusMessage = "Preparing background batch...",
                showPreviousBatch = false,
                dismissedBatchId = null,
                batch = BatchProgress(
                    status = BatchStatus.QUEUED,
                    totalCount = state.selectedImages.size
                )
            )
        }
        viewModelScope.launch {
            try {
                batchRepository.submit(
                    inputs = state.selectedImages.map {
                        com.example.theshrimpapp.features.imageoptimizer.background.BatchInput(
                            it.uri,
                            it.name,
                            it.sizeBytes,
                            it.parentFolderUri ?: it.sourceFolderUri
                        )
                    },
                    output = state.outputFolderUri,
                    source = state.selectedFolderUri,
                    options = options,
                    concurrency = state.concurrency,
                    sourceFolderName = state.selectedFolderName,
                    outputFolderName = state.outputFolderName,
                    destinationMode = state.destinationMode,
                    readNestedFolders = state.readNestedFolders,
                    folderImageSelectionMode = state.folderImageSelectionMode,
                    customSupportedFormats = state.customSupportedFormats,
                    filterByFileSize = state.filterByFileSize,
                    minFileSizeMbInput = state.minFileSizeMbInput,
                    maxFileSizeMbInput = state.maxFileSizeMbInput
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

    fun clearFinishedBatch() {
        _uiState.update {
            it.copy(
                showPreviousBatch = false,
                dismissedBatchId = it.batchJobId ?: it.dismissedBatchId,
                batch = BatchProgress(),
                batchJobId = null,
                selectedImages = emptyList(),
                selectedImagesCount = 0,
                selectedFolders = emptyList(),
                selectedFolderUri = null,
                selectedFolderName = null,
                statusMessage = "Ready to select images"
            )
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

    fun updateReadNestedFolders(readNested: Boolean) {
        if (_uiState.value.isProcessing) return
        preferences.readNestedFolders = readNested
        _uiState.update { it.copy(readNestedFolders = readNested) }
    }

    fun updateFolderImageSelectionMode(mode: FolderImageSelectionMode) {
        if (_uiState.value.isProcessing) return
        preferences.folderImageSelectionMode = mode
        _uiState.update { it.copy(folderImageSelectionMode = mode) }
    }

    fun toggleCustomSupportedFormat(format: SupportedImageFormat, enabled: Boolean) {
        if (_uiState.value.isProcessing) return
        _uiState.update { state ->
            val updated = if (enabled) {
                state.customSupportedFormats + format
            } else {
                state.customSupportedFormats - format
            }
            preferences.customSupportedFormats = updated
            state.copy(customSupportedFormats = updated)
        }
    }

    fun updateFilterByFileSize(enabled: Boolean) {
        if (_uiState.value.isProcessing) return
        preferences.filterByFileSize = enabled
        _uiState.update { it.copy(filterByFileSize = enabled) }
    }

    fun updateMinFileSizeInput(input: String) {
        if (_uiState.value.isProcessing) return
        if (input.isEmpty() || input.toDoubleOrNull() != null || input == ".") {
            preferences.minFileSizeMbInput = input
            _uiState.update { it.copy(minFileSizeMbInput = input) }
        }
    }

    fun updateMaxFileSizeInput(input: String) {
        if (_uiState.value.isProcessing) return
        if (input.isEmpty() || input.toDoubleOrNull() != null || input == ".") {
            preferences.maxFileSizeMbInput = input
            _uiState.update { it.copy(maxFileSizeMbInput = input) }
        }
    }

    private fun isFileFormatMatching(
        name: String?,
        mimeType: String?,
        selectionMode: FolderImageSelectionMode,
        customFormats: Set<SupportedImageFormat>
    ): Boolean {
        return when (selectionMode) {
            FolderImageSelectionMode.ALL_SUPPORTED -> {
                SupportedImageFormat.entries.any { it.matches(name, mimeType) } ||
                        (mimeType?.startsWith("image/") == true) ||
                        name?.endsWith(".jpg", ignoreCase = true) == true ||
                        name?.endsWith(".jpeg", ignoreCase = true) == true ||
                        name?.endsWith(".png", ignoreCase = true) == true ||
                        name?.endsWith(".webp", ignoreCase = true) == true
            }

            FolderImageSelectionMode.CUSTOM -> {
                customFormats.any { it.matches(name, mimeType) }
            }
        }
    }

    private fun isFileSizeMatching(
        sizeBytes: Long,
        filterByFileSize: Boolean,
        minFileSizeMb: Double?,
        maxFileSizeMb: Double?
    ): Boolean {
        if (!filterByFileSize) return true
        val minBytes = minFileSizeMb?.let { (it * 1024 * 1024).toLong() }
        val maxBytes = maxFileSizeMb?.let { (it * 1024 * 1024).toLong() }

        if (minBytes != null && sizeBytes < minBytes) return false
        if (maxBytes != null && sizeBytes > maxBytes) return false
        return true
    }

    private fun scanDirectory(
        rootDoc: DocumentFile,
        rootUri: Uri,
        folderName: String,
        readNested: Boolean,
        selectionMode: FolderImageSelectionMode,
        customFormats: Set<SupportedImageFormat>,
        filterByFileSize: Boolean = false,
        minFileSizeMb: Double? = null,
        maxFileSizeMb: Double? = null,
        maxLimit: Int = MAX_FILE_LIMIT
    ): List<ImageItem> {
        val result = mutableListOf<ImageItem>()
        val dirQueue = ArrayDeque<DocumentFile>()
        dirQueue.add(rootDoc)

        while (dirQueue.isNotEmpty() && result.size < maxLimit) {
            val currentDir = dirQueue.removeFirst()
            val children = currentDir.listFiles()
            for (file in children) {
                if (file.isDirectory) {
                    if (readNested) {
                        dirQueue.add(file)
                    }
                } else if (file.isFile) {
                    val name = file.name ?: "Unknown"
                    val mimeType = file.type
                    val size = file.length()
                    if (isFileFormatMatching(name, mimeType, selectionMode, customFormats) &&
                        isFileSizeMatching(size, filterByFileSize, minFileSizeMb, maxFileSizeMb)
                    ) {
                        result.add(
                            ImageItem(
                                uri = file.uri,
                                name = name,
                                sizeBytes = if (size > 0) size else null,
                                mimeType = mimeType ?: "image/*",
                                sourceFolderUri = rootUri,
                                sourceFolderName = folderName,
                                parentFolderUri = currentDir.uri
                            )
                        )
                        if (result.size >= maxLimit) break
                    }
                }
            }
        }
        return result
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
                val currentState = _uiState.value
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
                val readNested = currentState.readNestedFolders
                val selectionMode = currentState.folderImageSelectionMode
                val customFormats = currentState.customSupportedFormats
                val filterByFileSize = currentState.filterByFileSize
                val minFileSizeMb = currentState.minFileSizeMbInput.toDoubleOrNull()
                val maxFileSizeMb = currentState.maxFileSizeMbInput.toDoubleOrNull()
                val limit = MAX_FILE_LIMIT

                val imageItems = scanDirectory(
                    rootDoc = docFile,
                    rootUri = uri,
                    folderName = folderName,
                    readNested = readNested,
                    selectionMode = selectionMode,
                    customFormats = customFormats,
                    filterByFileSize = filterByFileSize,
                    minFileSizeMb = minFileSizeMb,
                    maxFileSizeMb = maxFileSizeMb,
                    maxLimit = limit
                )

                _uiState.update { state ->
                    val existingUris = state.selectedImages.map { it.uri }.toSet()
                    val uniqueNewItems = imageItems.filter { it.uri !in existingUris }
                    val combined = state.selectedImages + uniqueNewItems
                    val updatedList = combined.take(limit)
                    val wasCapped = combined.size > limit

                    val folderObj = SelectedFolder(
                        uri = uri,
                        name = folderName,
                        readNestedFolders = readNested,
                        folderImageSelectionMode = selectionMode,
                        customSupportedFormats = customFormats,
                        filterByFileSize = filterByFileSize,
                        minFileSizeMbInput = currentState.minFileSizeMbInput,
                        maxFileSizeMbInput = currentState.maxFileSizeMbInput
                    )
                    val updatedFolders = if (state.selectedFolders.any { it.uri == uri }) {
                        state.selectedFolders
                    } else {
                        state.selectedFolders + folderObj
                    }

                    val folderNameSummary = when {
                        updatedFolders.isEmpty() -> null
                        updatedFolders.size == 1 -> updatedFolders.first().name
                        else -> "${updatedFolders.size} Folders (${updatedFolders.joinToString { it.name }})"
                    }

                    state.copy(
                        isLoading = false,
                        selectedFolders = updatedFolders,
                        selectedFolderUri = uri,
                        selectedFolderName = folderNameSummary,
                        selectedImages = updatedList,
                        selectedImagesCount = updatedList.size,
                        statusMessage = if (wasCapped) {
                            "Loaded folder '$folderName': capped at $limit files max"
                        } else {
                            "Loaded folder '$folderName': found ${imageItems.size} supported image(s) (${updatedList.size} total across ${updatedFolders.size} folder(s))"
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
                val limit = MAX_FILE_LIMIT
                val existingUris = currentState.selectedImages.map { it.uri }.toSet()
                val uniqueNewItems = newItems.filter { it.uri !in existingUris }
                val combined = currentState.selectedImages + uniqueNewItems
                val updatedList = combined.take(limit)
                val wasCapped = combined.size > limit
                currentState.copy(
                    isLoading = false,
                    selectedImages = updatedList,
                    selectedImagesCount = updatedList.size,
                    statusMessage = if (wasCapped) {
                        "Selected ${updatedList.size} images (maximum $limit allowed)"
                    } else if (updatedList.isEmpty()) {
                        "Ready to select images"
                    } else {
                        "Selected ${updatedList.size} image(s)"
                    }
                )
            }
        }
    }

    fun removeFolder(folderUri: Uri) {
        if (_uiState.value.isProcessing) return
        _uiState.update { currentState ->
            val updatedImages =
                currentState.selectedImages.filter { it.sourceFolderUri != folderUri }
            val updatedFolders = currentState.selectedFolders.filter { it.uri != folderUri }
            val folderNameSummary = when {
                updatedFolders.isEmpty() -> null
                updatedFolders.size == 1 -> updatedFolders.first().name
                else -> "${updatedFolders.size} Folders (${updatedFolders.joinToString { it.name }})"
            }
            currentState.copy(
                selectedFolders = updatedFolders,
                selectedFolderUri = updatedFolders.lastOrNull()?.uri,
                selectedFolderName = folderNameSummary,
                selectedImages = updatedImages,
                selectedImagesCount = updatedImages.size,
                statusMessage = if (updatedImages.isEmpty()) "Ready to select images" else "Selected ${updatedImages.size} image(s)"
            )
        }
    }

    fun removeDirectImages() {
        if (_uiState.value.isProcessing) return
        _uiState.update { currentState ->
            val updatedImages = currentState.selectedImages.filter { it.sourceFolderUri != null }
            currentState.copy(
                selectedImages = updatedImages,
                selectedImagesCount = updatedImages.size,
                statusMessage = if (updatedImages.isEmpty()) "Ready to select images" else "Selected ${updatedImages.size} image(s)"
            )
        }
    }

    fun removeImage(uri: Uri) {
        if (_uiState.value.isProcessing) return
        _uiState.update { currentState ->
            val updatedList = currentState.selectedImages.filter { it.uri != uri }
            val remainingFolders = updatedList.mapNotNull { item ->
                item.sourceFolderUri?.let { folderUri ->
                    item.sourceFolderName?.let { folderName ->
                        SelectedFolder(folderUri, folderName)
                    }
                }
            }.distinct()

            val folderNameSummary = when {
                remainingFolders.isEmpty() -> null
                remainingFolders.size == 1 -> remainingFolders.first().name
                else -> "${remainingFolders.size} Folders (${remainingFolders.joinToString { it.name }})"
            }

            currentState.copy(
                selectedFolders = remainingFolders,
                selectedFolderUri = remainingFolders.lastOrNull()?.uri,
                selectedFolderName = folderNameSummary,
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
                selectedFolders = emptyList(),
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
        val clamped = dimension.coerceIn(MIN_DIMENSION, MAX_DIMENSION)
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
        if (parsed != null && parsed in MIN_DIMENSION..MAX_DIMENSION) {
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
                    optionsError = ImageOptimizerLimits.MAX_DIMENSION_ERROR_MESSAGE
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
                    it.maxDimensionInput.toIntOrNull() in MIN_DIMENSION..MAX_DIMENSION
                ) null
                else ImageOptimizerLimits.MAX_DIMENSION_ERROR_MESSAGE
            )
        }
    }

    fun updateQuality(quality: Int) {
        if (_uiState.value.isProcessing) return
        val clamped = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
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

    fun updatePostOptimizationPolicy(policy: PostOptimizationPolicy) {
        if (_uiState.value.isProcessing) return
        _uiState.update { it.copy(postOptimizationPolicy = policy) }
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
            preserveMetadata = state.preserveMetadata,
            postOptimizationPolicy = state.postOptimizationPolicy
        )
        return if (options.isValid()) options else null
    }

    fun setStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }
}
