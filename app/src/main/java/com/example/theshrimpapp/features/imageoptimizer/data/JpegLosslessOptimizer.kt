package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * JPEG coefficient-domain optimizer. Native code rewrites entropy/Huffman coding without
 * decoding and re-encoding the JPEG pixels. A missing native backend is reported as Unsupported.
 */
class JpegLosslessOptimizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver
) : FormatSpecificLosslessOptimizer {

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
        if (!JpegTranNative.isAvailable()) {
            return@withContext ImageOptimizationResult.Unsupported(
                "The native JPEG lossless backend is not loaded. Configure app/src/main/cpp and the Android NDK build first."
            )
        }

        val temporaries = mutableListOf<File>()
        var outputWriteAttempted = false
        var outputCommitted = false
        try {
            val inputFile = newTempFile("shrimp-jpeg-input-", ".jpg").also(temporaries::add)
            copyUriToFile(inputUri, inputFile)
            val originalSize = inputFile.length()
            if (originalSize <= 0L) {
                return@withContext ImageOptimizationResult.Failed("The input is empty or could not be read.")
            }
            if (!isJpeg(inputFile)) {
                return@withContext ImageOptimizationResult.Unsupported("The selected input is not a JPEG file.")
            }

            val sourceBounds = readBounds(inputFile)
                ?: return@withContext ImageOptimizationResult.Failed("The JPEG header could not be decoded.")
            val orientation = readExifOrientation(inputFile)
            if (!options.preserveMetadata && orientation != ExifInterface.ORIENTATION_UNDEFINED &&
                orientation != ExifInterface.ORIENTATION_NORMAL
            ) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "This JPEG has a non-normal EXIF orientation. The current backend cannot strip optional EXIF metadata while retaining orientation safely. Enable metadata preservation for this image."
                )
            }
            ensureJpegOutputTarget(outputUri)?.let { message ->
                return@withContext ImageOptimizationResult.Failed(message)
            }

            val candidate = newTempFile("shrimp-jpeg-candidate-", ".jpg").also(temporaries::add)
            when (JpegTranNative.optimizeFile(inputFile.absolutePath, candidate.absolutePath, options.preserveMetadata)) {
                JpegTranNative.STATUS_OK -> Unit
                JpegTranNative.STATUS_UNAVAILABLE -> return@withContext ImageOptimizationResult.Unsupported(
                    "The native JPEG lossless backend is unavailable on this device."
                )
                else -> return@withContext ImageOptimizationResult.Failed(
                    "The native JPEG optimizer could not process this file. The source was not modified."
                )
            }
            if (!isJpeg(candidate)) {
                return@withContext ImageOptimizationResult.Failed("The native JPEG optimizer produced an invalid output.")
            }
            val candidateBounds = readBounds(candidate)
                ?: return@withContext ImageOptimizationResult.Failed("The optimized JPEG could not be decoded.")
            if (candidateBounds.first != sourceBounds.first || candidateBounds.second != sourceBounds.second) {
                return@withContext ImageOptimizationResult.Failed("JPEG optimization changed the stored image dimensions.")
            }
            val candidateSize = candidate.length()
            if (candidateSize >= originalSize) {
                return@withContext ImageOptimizationResult.Unchanged(
                    "Lossless JPEG optimization did not produce a smaller file. The original was retained."
                )
            }

            when (isOutputTargetEmpty(outputUri)) {
                true -> Unit
                false -> return@withContext ImageOptimizationResult.Failed(
                    "The output destination already contains data. Choose a new, empty output file."
                )
                null -> return@withContext ImageOptimizationResult.Failed(
                    "The app could not verify that the output destination is empty. Choose a new output file."
                )
            }
            outputWriteAttempted = true
            writeFileToUri(candidate, outputUri)

            val readBack = newTempFile("shrimp-jpeg-readback-", ".jpg").also(temporaries::add)
            copyUriToFile(outputUri, readBack)
            if (!isJpeg(readBack)) {
                return@withContext ImageOptimizationResult.Failed("The saved output is not a valid JPEG.")
            }
            val readBackBounds = readBounds(readBack)
                ?: return@withContext ImageOptimizationResult.Failed("The saved JPEG could not be validated.")
            if (readBackBounds.first != sourceBounds.first || readBackBounds.second != sourceBounds.second) {
                return@withContext ImageOptimizationResult.Failed("The saved output changed JPEG dimensions.")
            }
            val actualOutputSize = readBack.length()
            if (actualOutputSize >= originalSize) {
                return@withContext ImageOptimizationResult.Failed(
                    "The saved JPEG was not smaller than the original after writing. It must not be treated as an optimization."
                )
            }

            val shownDimensions = displayedDimensions(sourceBounds.first, sourceBounds.second, orientation)
            val bytesSaved = originalSize - actualOutputSize
            outputCommitted = true
            ImageOptimizationResult.Optimized(
                originalSizeBytes = originalSize,
                outputSizeBytes = actualOutputSize,
                format = OutputFormat.JPEG,
                width = shownDimensions.first,
                height = shownDimensions.second,
                bytesSaved = bytesSaved,
                percentageReduction = (bytesSaved.toDouble() * 100.0 / originalSize.toDouble()).toFloat()
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (e: Exception) {
            ImageOptimizationResult.Failed(e.localizedMessage ?: "Unknown JPEG optimization error.")
        } finally {
            if (outputWriteAttempted && !outputCommitted) {
                cancellationAwareRunCatching { contentResolver.delete(outputUri, null, null) }
            }
            temporaries.forEach { cancellationAwareRunCatching { it.delete() } }
        }
    }

    private fun isJpeg(file: File): Boolean = try {
        FileInputStream(file).use { input ->
            val header = ByteArray(3)
            input.readFully(header)
            header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) {
        false
    }

    private fun readBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    private fun readExifOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    private fun displayedDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> height to width
            else -> width to height
        }

    private fun ensureJpegOutputTarget(uri: Uri): String? {
        val mimeType = cancellationAwareRunCatching { contentResolver.getType(uri) }.getOrNull()
        if (mimeType != null && mimeType !in setOf("image/jpeg", "image/jpg", "application/octet-stream")) {
            return "The output destination is not identified as JPEG. Choose a .jpg or .jpeg destination."
        }
        val name = queryDisplayName(uri)
        if (name != null && name.contains('.') &&
            !name.endsWith(".jpg", ignoreCase = true) && !name.endsWith(".jpeg", ignoreCase = true)
        ) {
            return "The output filename must end in .jpg or .jpeg for lossless JPEG optimization."
        }
        return null
    }

    private fun isOutputTargetEmpty(uri: Uri): Boolean? {
        val reportedSize = cancellationAwareRunCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else null
            }
        }.getOrNull()
        if (reportedSize != null && reportedSize >= 0L) return reportedSize == 0L
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { it.read() == -1 }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = cancellationAwareRunCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()

    private fun copyUriToFile(uri: Uri, destination: File) {
        val input = contentResolver.openInputStream(uri) ?: throw IOException("Could not open source URI.")
        input.use { source -> BufferedOutputStream(FileOutputStream(destination)).use { output -> source.copyTo(output, IO_BUFFER_SIZE) } }
    }

    private fun writeFileToUri(source: File, destinationUri: Uri) {
        val output = contentResolver.openOutputStream(destinationUri, "wt") ?: throw IOException("Could not open output URI.")
        output.use { destination ->
            BufferedInputStream(FileInputStream(source)).use { input -> input.copyTo(destination, IO_BUFFER_SIZE) }
            destination.flush()
        }
    }

    private fun newTempFile(prefix: String, suffix: String) = File.createTempFile(prefix, suffix, context.cacheDir)

    private fun java.io.InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) throw IOException("JPEG file is truncated.")
            if (count == 0) continue
            offset += count
        }
    }

    private companion object {
        const val IO_BUFFER_SIZE = 64 * 1024
    }
}
