package com.example.theshrimpapp.features.imageoptimizer.ui

import android.net.Uri

data class ImageItem(
    val uri: Uri,
    val name: String? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null
)

data class ImageOptimizerUiState(
    val selectedImages: List<ImageItem> = emptyList(),
    val selectedImagesCount: Int = 0,
    val maxDimension: Int = 1920,
    val outputFormat: String = "WEBP_LOSSY",
    val quality: Int = 85,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Ready to select images"
)
