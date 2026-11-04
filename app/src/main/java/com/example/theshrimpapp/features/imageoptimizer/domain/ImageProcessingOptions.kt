package com.example.theshrimpapp.features.imageoptimizer.domain

import androidx.compose.ui.graphics.Color

enum class ProcessingMode(val displayName: String) {
    LOSSLESS_OPTIMIZE("Lossless Optimize"),
    REDUCE_SIZE("Resize & Convert")
}

enum class PostOptimizationPolicy(val displayName: String) {
    SKIP("Faster processing"),
    TRY_LOSSLESS("Try further size reduction")
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
    val maxDimension: Int = ImageOptimizerDefaults.DEFAULT_MAX_DIMENSION,
    val keepOriginalSize: Boolean = false,
    val outputFormat: OutputFormat = OutputFormat.WEBP_LOSSY,
    val quality: Int = ImageOptimizerDefaults.DEFAULT_QUALITY,
    val backgroundColor: Color = Color.White,
    val preserveMetadata: Boolean = true,
    val postOptimizationPolicy: PostOptimizationPolicy = PostOptimizationPolicy.SKIP
) {
    fun isValid(): Boolean {
        return when (processingMode) {
            ProcessingMode.LOSSLESS_OPTIMIZE -> true
            ProcessingMode.REDUCE_SIZE -> {
                val dimValid = keepOriginalSize || (maxDimension in ImageOptimizerLimits.MIN_DIMENSION..ImageOptimizerLimits.MAX_DIMENSION)
                val qualityValid = !outputFormat.supportsQuality || (quality in ImageOptimizerLimits.MIN_QUALITY..ImageOptimizerLimits.MAX_QUALITY)
                dimValid && qualityValid
            }
        }
    }
}
