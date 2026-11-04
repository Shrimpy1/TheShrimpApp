package com.example.theshrimpapp.features.imageoptimizer.ui

import android.net.Uri
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchProgress
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.PostOptimizationPolicy

import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizerDefaults

data class SelectedFolder(
    val uri: Uri,
    val name: String,
    val readNestedFolders: Boolean = false,
    val folderImageSelectionMode: com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode = com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode.ALL_SUPPORTED,
    val customSupportedFormats: Set<com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat> = emptySet(),
    val filterByFileSize: Boolean = false,
    val minFileSizeMbInput: String = "",
    val maxFileSizeMbInput: String = ""
)

data class ImageItem(
    val uri: Uri,
    val name: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val sourceFolderUri: Uri? = null,
    val sourceFolderName: String? = null,
    val parentFolderUri: Uri? = null
)

data class ImageOptimizerUiState(
    val selectedImages: List<ImageItem> = emptyList(),
    val selectedImagesCount: Int = 0,
    val selectedFolders: List<SelectedFolder> = emptyList(),
    val selectedFolderUri: Uri? = null,
    val selectedFolderName: String? = null,
    val outputFolderUri: Uri? = null,
    val outputFolderName: String? = null,
    val readNestedFolders: Boolean = false,
    val folderImageSelectionMode: com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode = com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode.ALL_SUPPORTED,
    val customSupportedFormats: Set<com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat> = com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat.entries.toSet(),
    val filterByFileSize: Boolean = false,
    val minFileSizeMbInput: String = "",
    val maxFileSizeMbInput: String = "",
    val destinationMode: OutputDestinationMode = OutputDestinationMode.SUBFOLDER,
    val processingMode: ProcessingMode = ProcessingMode.LOSSLESS_OPTIMIZE,
    val maxDimension: Int = ImageOptimizerDefaults.DEFAULT_MAX_DIMENSION,
    val keepOriginalSize: Boolean = false,
    val maxDimensionInput: String = ImageOptimizerDefaults.DEFAULT_MAX_DIMENSION.toString(),
    val outputFormat: OutputFormat = OutputFormat.WEBP_LOSSY,
    val quality: Int = ImageOptimizerDefaults.DEFAULT_QUALITY,
    val jpegBackgroundColor: JpegBackgroundColor = JpegBackgroundColor.WHITE,
    val preserveMetadata: Boolean = true,
    val postOptimizationPolicy: PostOptimizationPolicy = PostOptimizationPolicy.SKIP,
    val optionsError: String? = null,
    val isProcessing: Boolean = false,
    val batchJobId: String? = null,
    val concurrency: Int = ImageOptimizerDefaults.DEFAULT_CONCURRENCY,
    val batch: BatchProgress<ImageItem, SaveImageResult> = BatchProgress(),
    val showPreviousBatch: Boolean = true,
    val dismissedBatchId: String? = null,
    val isLoading: Boolean = false,
    val statusMessage: String = "Ready to select images or folder"
)
