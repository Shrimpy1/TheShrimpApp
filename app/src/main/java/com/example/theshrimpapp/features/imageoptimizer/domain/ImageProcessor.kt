package com.example.theshrimpapp.features.imageoptimizer.domain

import android.net.Uri

interface ImageProcessor {
    suspend fun process(
        inputUri: Uri,
        outputUri: Uri,
        options: ImageProcessingOptions
    ): ImageProcessingResult
}
