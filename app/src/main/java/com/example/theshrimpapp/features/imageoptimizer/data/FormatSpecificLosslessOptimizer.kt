package com.example.theshrimpapp.features.imageoptimizer.data

import android.net.Uri
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions

/** A lossless optimizer dedicated to one detected container format. */
interface FormatSpecificLosslessOptimizer {
    suspend fun optimize(
        inputUri: Uri,
        outputUri: Uri,
        options: ImageProcessingOptions
    ): ImageOptimizationResult
}
