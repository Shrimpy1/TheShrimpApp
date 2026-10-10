package com.example.theshrimpapp.features.imageoptimizer.domain

sealed interface ImageProcessingResult {
    data class Success(
        val originalSizeBytes: Long,
        val outputSizeBytes: Long,
        val format: OutputFormat,
        val width: Int,
        val height: Int,
        val bytesSaved: Long,
        val percentageReduction: Float,
        val mode: ProcessingMode
    ) : ImageProcessingResult

    data class Unchanged(
        val reason: String
    ) : ImageProcessingResult

    data class Failed(
        val error: String
    ) : ImageProcessingResult
}
