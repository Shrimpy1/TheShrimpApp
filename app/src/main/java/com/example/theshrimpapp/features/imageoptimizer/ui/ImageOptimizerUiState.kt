package com.example.theshrimpapp.features.imageoptimizer.ui

import android.net.Uri
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode

data class ImageItem(
    val uri: Uri,
    val name: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null
)

data class ImageOptimizerUiState(
    val selectedImages: List<ImageItem> = emptyList(),
    val selectedImagesCount: Int = 0,
    val selectedFolderUri: Uri? = null,
    val selectedFolderName: String? = null,
    val outputFolderUri: Uri? = null,
    val outputFolderName: String? = null,
    val processingMode: ProcessingMode = ProcessingMode.LOSSLESS_OPTIMIZE,
    val maxDimension: Int = 1920,
    val keepOriginalSize: Boolean = false,
    val maxDimensionInput: String = "1920",
    val outputFormat: OutputFormat = OutputFormat.WEBP_LOSSY,
    val quality: Int = 85,
    val jpegBackgroundColor: JpegBackgroundColor = JpegBackgroundColor.WHITE,
    val preserveMetadata: Boolean = true,
    val optionsError: String? = null,
    val isProcessing: Boolean = false,
    val isLoading: Boolean = false,
    val statusMessage: String = "Ready to select images or folder"
)
