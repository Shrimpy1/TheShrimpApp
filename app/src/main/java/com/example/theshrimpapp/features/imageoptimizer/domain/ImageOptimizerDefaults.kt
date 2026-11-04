package com.example.theshrimpapp.features.imageoptimizer.domain

/**
 * Default initial configuration values for Image Optimizer options and state.
 */
object ImageOptimizerDefaults {
    /** Maximum number of image files to load/select from folders or file pickers by default. */
    var MAX_FILE_LIMIT: Int = 4000

    /** Preset options for max dimension resizing (in pixels). */
    var DIMENSION_PRESETS: List<Int> = listOf(1920, 2560, 3840)

    /** Default max dimension for image resizing (in pixels). */
    var DEFAULT_MAX_DIMENSION: Int = DIMENSION_PRESETS[2] // 2560

    /** Default compression quality (1-100). */
    var DEFAULT_QUALITY: Int = 85

    /** Default parallel processing threads. */
    var DEFAULT_CONCURRENCY: Int = 4
}
