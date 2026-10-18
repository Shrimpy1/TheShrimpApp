package com.example.theshrimpapp.features.imageoptimizer.domain

import androidx.compose.ui.graphics.Color

enum class ProcessingMode(val displayName: String) {
    LOSSLESS_OPTIMIZE("Lossless Optimize"),
    REDUCE_SIZE("Resize & Convert")
}

enum class OutputDestinationMode(val displayName: String) {
    REPLACE_ORIGINAL("Replace original"),
    SUBFOLDER("Subfolder in source folder"),
    CUSTOM_FOLDER("Custom folder")
}

enum class OutputFormat(
    val id: String,
    val displayName: String,
    val extension: String,
    val supportsQuality: Boolean
) {
    JPEG("JPEG", "JPEG", ".jpg", true),
    WEBP_LOSSY("WEBP_LOSSY", "WebP (Lossy)", ".webp", true),
    WEBP_LOSSLESS("WEBP_LOSSLESS", "WebP (Lossless)", ".webp", false),
    PNG("PNG", "PNG", ".png", false)
}

enum class JpegBackgroundColor(
    val id: String,
    val displayName: String,
    val color: Color
) {
    WHITE("WHITE", "White", Color.White),
    BLACK("BLACK", "Black", Color.Black),
    GRAY("GRAY", "Gray", Color.Gray)
}

data class ImageProcessingOptions(
    val processingMode: ProcessingMode = ProcessingMode.LOSSLESS_OPTIMIZE,
    val maxDimension: Int = 1920,
    val keepOriginalSize: Boolean = false,
    val outputFormat: OutputFormat = OutputFormat.WEBP_LOSSY,
    val quality: Int = 85,
    val backgroundColor: Color = Color.White,
    val preserveMetadata: Boolean = true
) {
    fun isValid(): Boolean {
        return when (processingMode) {
            ProcessingMode.LOSSLESS_OPTIMIZE -> true
            ProcessingMode.REDUCE_SIZE -> {
                val dimValid = keepOriginalSize || maxDimension in 1..10000
                val qualityValid = if (outputFormat.supportsQuality) quality in 1..100 else true
                dimValid && qualityValid
            }
        }
    }
}
