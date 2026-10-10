package com.example.theshrimpapp.features.imageoptimizer.data

/** Thin JNI bridge to the coefficient-domain JPEG optimizer built from libjpeg-turbo. */
internal object JpegTranNative {
    const val STATUS_OK = 0
    const val STATUS_FAILED = 1
    const val STATUS_UNAVAILABLE = 2

    private val loadStatus: Int by lazy {
        try {
            System.loadLibrary("jpeg_lossless_optimizer")
            STATUS_OK
        } catch (_: UnsatisfiedLinkError) {
            STATUS_UNAVAILABLE
        } catch (_: SecurityException) {
            STATUS_UNAVAILABLE
        }
    }

    fun isAvailable(): Boolean = loadStatus == STATUS_OK

    fun optimizeFile(inputPath: String, outputPath: String, preserveMetadata: Boolean): Int {
        if (!isAvailable()) return STATUS_UNAVAILABLE
        return try {
            nativeOptimizeFile(inputPath, outputPath, preserveMetadata)
        } catch (_: UnsatisfiedLinkError) {
            STATUS_UNAVAILABLE
        }
    }

    private external fun nativeOptimizeFile(
        inputPath: String,
        outputPath: String,
        preserveMetadata: Boolean
    ): Int
}
