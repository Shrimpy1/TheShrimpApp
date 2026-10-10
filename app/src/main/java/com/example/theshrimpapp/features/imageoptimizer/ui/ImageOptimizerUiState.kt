package com.example.theshrimpapp.features.imageoptimizer.ui

data class ImageOptimizerUiState(
    val selectedImagesCount: Int = 0,
    val maxDimension: Int = 1920,
    val outputFormat: String = "WEBP_LOSSY",
    val quality: Int = 85,
    val isProcessing: Boolean = false,
    val statusMessage: String = "Ready to select images"
)
