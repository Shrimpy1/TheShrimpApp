package com.example.theshrimpapp.features.imageoptimizer.domain

import android.net.Uri

/** Result of attempting lossless optimization. No lossy fallback is permitted. */
sealed interface ImageOptimizationResult {
    data class Optimized(
        val originalSizeBytes: Long,
        val outputSizeBytes: Long,
        val format: OutputFormat,
        val width: Int,
        val height: Int,
        val bytesSaved: Long,
        val percentageReduction: Float
    ) : ImageOptimizationResult

    /** No output was written because no valid, strictly smaller candidate was found. */
    data class Unchanged(
        val reason: String
    ) : ImageOptimizationResult

    /** The format or a required lossless backend is not supported by this implementation. */
    data class Unsupported(
        val reason: String
    ) : ImageOptimizationResult

    data class Failed(
        val error: String
    ) : ImageOptimizationResult
}

interface LosslessImageOptimizer {
    /**
     * Attempts lossless optimization from [inputUri] to [outputUri]. The source is never modified.
     * An Unchanged or Unsupported result means the destination was not written by this call.
     * If the caller pre-created an empty output document, the caller should delete that unused
     * document when the result is not Optimized.
     */
    suspend fun optimize(
        inputUri: Uri,
        outputUri: Uri,
        options: ImageProcessingOptions
    ): ImageOptimizationResult
}