package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.net.Uri
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.LosslessImageOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Dispatches to a format-specific optimizer based on file signatures, not filenames or MIME
 * declarations alone. Each backend owns its format-specific validation and candidate creation.
 */
class DefaultLosslessImageOptimizer @Inject constructor(
    private val pngOptimizer: PngLosslessOptimizer,
    private val jpegOptimizer: JpegLosslessOptimizer,
    private val webpOptimizer: WebpLosslessOptimizer,
    private val contentResolver: ContentResolver
) : LosslessImageOptimizer {

    override suspend fun optimize(
        inputUri: Uri,
        outputUri: Uri,
        options: ImageProcessingOptions
    ): ImageOptimizationResult = withContext(Dispatchers.IO) {
        if (inputUri == outputUri) {
            return@withContext ImageOptimizationResult.Failed(
                "Input and output must be different URIs. The source was not modified."
            )
        }

        val format = try {
            detectFormat(inputUri)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (e: Exception) {
            return@withContext ImageOptimizationResult.Failed(
                e.localizedMessage ?: "Could not read the selected image."
            )
        }

        when (format) {
            InputFormat.PNG -> pngOptimizer.optimize(inputUri, outputUri, options)
            InputFormat.JPEG -> jpegOptimizer.optimize(inputUri, outputUri, options)
            InputFormat.WEBP -> webpOptimizer.optimize(inputUri, outputUri, options)
            null -> ImageOptimizationResult.Unsupported(
                "The selected file is not a supported PNG, JPEG, or WebP image."
            )
        }
    }

    private fun detectFormat(uri: Uri): InputFormat? {
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open the selected image.")
        input.use { stream ->
            val header = ByteArray(12)
            var offset = 0
            while (offset < header.size) {
                val count = stream.read(header, offset, header.size - offset)
                if (count < 0) break
                if (count == 0) continue
                offset += count
            }
            if (offset >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
                return InputFormat.PNG
            }
            if (offset >= 3 &&
                header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
            ) {
                return InputFormat.JPEG
            }
            if (offset >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            ) {
                return InputFormat.WEBP
            }
        }
        return null
    }

    private enum class InputFormat { PNG, JPEG, WEBP }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
