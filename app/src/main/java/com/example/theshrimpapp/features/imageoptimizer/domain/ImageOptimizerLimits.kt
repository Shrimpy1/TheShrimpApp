package com.example.theshrimpapp.features.imageoptimizer.domain

import java.util.Locale

/**
 * Validation boundaries, min/max constraints, and limits for Image Optimizer operations.
 */
object ImageOptimizerLimits {
    /** Resizing dimension boundaries (in pixels). */
    const val MIN_DIMENSION: Int = 1
    const val MAX_DIMENSION: Int = 10000

    /** Compression quality boundaries (1-100). */
    const val MIN_QUALITY: Int = 1
    const val MAX_QUALITY: Int = 100

    /** Parallel processing thread boundaries. */
    const val MIN_CONCURRENCY: Int = 1
    const val MAX_CONCURRENCY: Int = MAX_BATCH_CONCURRENCY

    val MAX_DIMENSION_ERROR_MESSAGE: String
        get() = "Max dimension must be between $MIN_DIMENSION and ${String.format(Locale.US, "%,d", MAX_DIMENSION)} px"
}
