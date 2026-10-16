package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.get
import androidx.exifinterface.media.ExifInterface
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import javax.inject.Inject

/**
 * Re-encodes static WebP images using Android's lossless WebP encoder and verifies decoded pixels.
 * This is pixel-lossless relative to Android's decoded bitmap, not a byte-preserving bitstream
 * optimization. ICC-profiled inputs and unknown chunks are conservatively rejected because the
 * platform bitmap path cannot guarantee their original color/metadata semantics.
 */
class WebpLosslessOptimizer @Inject constructor(
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

        val temporaries = mutableListOf<File>()
        var outputWriteAttempted = false
        var outputCommitted = false
        var originalBitmap: Bitmap? = null
        var expectedBitmap: Bitmap? = null
        try {
            val inputFile = newTempFile("shrimp-webp-input-", ".webp").also(temporaries::add)
            copyUriToFile(inputUri, inputFile)
            val originalSize = inputFile.length()
            if (originalSize <= 0L) {
                return@withContext ImageOptimizationResult.Failed("The input is empty or could not be read.")
            }

            val sourceIndex = parseWebp(inputFile)
            if (sourceIndex.isAnimated) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "Animated WebP is not supported by the current lossless optimizer. The source was not modified."
                )
            }
            if (sourceIndex.chunks.any { it.type == "ICCP" }) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "This WebP contains an ICC color profile. The current bitmap encoder path cannot guarantee profile-preserving re-encoding. The source was not modified."
                )
            }
            val knownChunkTypes =
                setOf("VP8X", "VP8 ", "VP8L", "ALPH", "EXIF", "XMP ", "ICCP", "ANIM", "ANMF")
            if (sourceIndex.chunks.any { it.type !in knownChunkTypes }) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "This WebP contains an unrecognized container chunk that the current optimizer cannot safely preserve."
                )
            }
            if (sourceIndex.chunks.none { it.type == "VP8 " || it.type == "VP8L" }) {
                return@withContext ImageOptimizationResult.Failed("The WebP file does not contain a supported image bitstream.")
            }

            ensureWebpOutputTarget(outputUri)?.let { message ->
                return@withContext ImageOptimizationResult.Failed(message)
            }

            val sourceOrientation = readExifOrientation(inputFile)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(inputFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext ImageOptimizationResult.Failed("Android could not read the WebP dimensions.")
            }
            if (bounds.outWidth.toLong() * bounds.outHeight.toLong() > MAX_BITMAP_PIXELS) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "This image is too large for the current bitmap-based WebP optimizer. The source was not modified."
                )
            }
            originalBitmap = BitmapFactory.decodeFile(inputFile.absolutePath)
                ?: return@withContext ImageOptimizationResult.Failed("Android could not decode the WebP image.")

            // If metadata is being removed, normalize orientation into pixels before discarding EXIF.
            // Otherwise keep stored pixels unchanged and carry EXIF through the WebP container.
            expectedBitmap = if (!options.preserveMetadata) {
                applyExifOrientation(originalBitmap!!, sourceOrientation)
            } else {
                originalBitmap
            }

            val encodedFile = newTempFile("shrimp-webp-encoded-", ".webp").also(temporaries::add)
            FileOutputStream(encodedFile).use { output ->
                val success =
                    expectedBitmap!!.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)
                if (!success) throw IOException("Android's lossless WebP encoder failed.")
            }

            val candidateFile =
                newTempFile("shrimp-webp-candidate-", ".webp").also(temporaries::add)
            if (options.preserveMetadata) {
                val metadata = sourceIndex.chunks.filter { it.type == "EXIF" || it.type == "XMP " }
                if (metadata.isNotEmpty()) {
                    rebuildWithMetadata(
                        encodedFile = encodedFile,
                        sourceFile = inputFile,
                        metadataChunks = metadata,
                        width = expectedBitmap!!.width,
                        height = expectedBitmap!!.height,
                        hasAlpha = expectedBitmap!!.hasAlpha(),
                        outputFile = candidateFile
                    )
                } else {
                    encodedFile.copyTo(candidateFile, overwrite = true)
                }
            } else {
                encodedFile.copyTo(candidateFile, overwrite = true)
            }

            // Verify RIFF structure and decoded bitmap values before considering the candidate.
            parseWebp(candidateFile)
            verifyBitmapPixels(expectedBitmap!!, candidateFile)
            if (options.preserveMetadata) {
                verifyMetadataPreserved(inputFile, candidateFile, sourceIndex)
            }
            val candidateSize = candidateFile.length()
            if (candidateSize >= originalSize) {
                return@withContext ImageOptimizationResult.Unchanged(
                    "Lossless WebP re-encoding did not produce a smaller file. The original was retained."
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
            writeFileToUri(candidateFile, outputUri)

            val readBack = newTempFile("shrimp-webp-readback-", ".webp").also(temporaries::add)
            copyUriToFile(outputUri, readBack)
            val readBackIndex = parseWebp(readBack)
            verifyBitmapPixels(expectedBitmap!!, readBack)
            if (options.preserveMetadata) verifyMetadataPreserved(inputFile, readBack, sourceIndex)

            val actualOutputSize = readBack.length()
            if (actualOutputSize >= originalSize) {
                return@withContext ImageOptimizationResult.Failed(
                    "The saved WebP was not smaller than the original after writing. It must not be treated as an optimization."
                )
            }
            if (readBackIndex.width != expectedBitmap!!.width || readBackIndex.height != expectedBitmap!!.height) {
                return@withContext ImageOptimizationResult.Failed(
                    "The saved WebP changed dimensions. Check the output document provider."
                )
            }

            val bytesSaved = originalSize - actualOutputSize
            val reportedDimensions = if (options.preserveMetadata) {
                displayedDimensions(
                    expectedBitmap!!.width,
                    expectedBitmap!!.height,
                    sourceOrientation
                )
            } else {
                expectedBitmap!!.width to expectedBitmap!!.height
            }
            outputCommitted = true
            ImageOptimizationResult.Optimized(
                originalSizeBytes = originalSize,
                outputSizeBytes = actualOutputSize,
                format = OutputFormat.WEBP_LOSSLESS,
                width = reportedDimensions.first,
                height = reportedDimensions.second,
                bytesSaved = bytesSaved,
                percentageReduction = (bytesSaved.toDouble() * 100.0 / originalSize.toDouble()).toFloat()
            )
        } catch (e: WebpFormatException) {
            ImageOptimizationResult.Failed(e.message ?: "The WebP file is invalid or unsupported.")
        } catch (e: OutOfMemoryError) {
            ImageOptimizationResult.Failed("The WebP is too large to process safely in available memory.")
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            ImageOptimizationResult.Failed(e.localizedMessage ?: "Unknown WebP optimization error.")
        } finally {
            if (expectedBitmap != null && expectedBitmap !== originalBitmap) {
                cancellationAwareRunCatching { expectedBitmap?.recycle() }
            }
            cancellationAwareRunCatching { originalBitmap?.recycle() }
            if (outputWriteAttempted && !outputCommitted) {
                cancellationAwareRunCatching { contentResolver.delete(outputUri, null, null) }
            }
            temporaries.forEach { cancellationAwareRunCatching { it.delete() } }
        }
    }

    private fun applyExifOrientation(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == ExifInterface.ORIENTATION_UNDEFINED || orientation == ExifInterface.ORIENTATION_NORMAL) {
            return source
        }
        val width = source.width.toFloat()
        val height = source.height.toFloat()
        val matrix = Matrix()
        val values = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> floatArrayOf(
                -1f,
                0f,
                width,
                0f,
                1f,
                0f,
                0f,
                0f,
                1f
            )

            ExifInterface.ORIENTATION_ROTATE_180 -> floatArrayOf(
                -1f,
                0f,
                width,
                0f,
                -1f,
                height,
                0f,
                0f,
                1f
            )

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> floatArrayOf(
                1f,
                0f,
                0f,
                0f,
                -1f,
                height,
                0f,
                0f,
                1f
            )

            ExifInterface.ORIENTATION_TRANSPOSE -> floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
            ExifInterface.ORIENTATION_ROTATE_90 -> floatArrayOf(
                0f,
                -1f,
                height,
                1f,
                0f,
                0f,
                0f,
                0f,
                1f
            )

            ExifInterface.ORIENTATION_TRANSVERSE -> floatArrayOf(
                0f,
                -1f,
                height,
                -1f,
                0f,
                width,
                0f,
                0f,
                1f
            )

            ExifInterface.ORIENTATION_ROTATE_270 -> floatArrayOf(
                0f,
                1f,
                0f,
                -1f,
                0f,
                width,
                0f,
                0f,
                1f
            )

            else -> return source
        }
        matrix.setValues(values)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
    }

    private fun verifyBitmapPixels(expected: Bitmap, encodedFile: File) {
        val decoded = BitmapFactory.decodeFile(encodedFile.absolutePath)
            ?: throw WebpFormatException("The candidate WebP could not be decoded.")
        try {
            if (decoded.width != expected.width || decoded.height != expected.height) {
                throw WebpFormatException("Lossless WebP re-encoding changed image dimensions.")
            }
            for (y in 0 until expected.height) {
                for (x in 0 until expected.width) {
                    if (expected[x, y] != decoded[x, y]) {
                        throw WebpFormatException("Lossless WebP validation found a changed pixel at ($x, $y).")
                    }
                }
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun verifyMetadataPreserved(source: File, candidate: File, sourceIndex: WebpIndex) {
        val candidateIndex = parseWebp(candidate)
        for (type in listOf("EXIF", "XMP ")) {
            val sourcePayloads =
                sourceIndex.chunks.filter { it.type == type }.map { readChunkPayload(source, it) }
            val outputPayloads = candidateIndex.chunks.filter { it.type == type }
                .map { readChunkPayload(candidate, it) }
            if (sourcePayloads.size != outputPayloads.size || sourcePayloads.indices.any { index ->
                    !sourcePayloads[index].contentEquals(outputPayloads[index])
                }
            ) {
                throw WebpFormatException("The candidate did not preserve the source $type metadata chunk(s).")
            }
        }
    }

    private fun readChunkPayload(file: File, chunk: WebpChunk): ByteArray {
        if (chunk.dataLength > MAX_METADATA_CHUNK_BYTES) {
            throw WebpFormatException("WebP metadata chunk is too large to validate safely.")
        }
        val bytes = ByteArray(chunk.dataLength)
        RandomAccessFile(file, "r").use { input ->
            input.seek(chunk.dataStart)
            input.readFully(bytes)
        }
        return bytes
    }

    private fun rebuildWithMetadata(
        encodedFile: File,
        sourceFile: File,
        metadataChunks: List<WebpChunk>,
        width: Int,
        height: Int,
        hasAlpha: Boolean,
        outputFile: File
    ) {
        val encodedIndex = parseWebp(encodedFile)
        if (width !in 1..MAX_WEBP_DIMENSION || height !in 1..MAX_WEBP_DIMENSION) {
            throw WebpFormatException("WebP dimensions exceed the extended WebP container limit.")
        }
        val imageChunks =
            encodedIndex.chunks.filter { it.type != "VP8X" && it.type != "EXIF" && it.type != "XMP " && it.type != "ICCP" }
        val hasExif = metadataChunks.any { it.type == "EXIF" }
        val hasXmp = metadataChunks.any { it.type == "XMP " }
        val flags = (if (hasAlpha) VP8X_ALPHA_FLAG else 0) or
                (if (hasExif) VP8X_EXIF_FLAG else 0) or
                (if (hasXmp) VP8X_XMP_FLAG else 0)
        val vp8x = byteArrayOf(
            flags.toByte(),
            0,
            0,
            0,
            ((width - 1) and 0xFF).toByte(),
            (((width - 1) shr 8) and 0xFF).toByte(),
            (((width - 1) shr 16) and 0xFF).toByte(),
            ((height - 1) and 0xFF).toByte(),
            (((height - 1) shr 8) and 0xFF).toByte(),
            (((height - 1) shr 16) and 0xFF).toByte()
        )

        DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(output, 0) // Patch RIFF size after writing all chunks.
            output.write("WEBP".toByteArray(Charsets.US_ASCII))
            writeWebpChunk(output, "VP8X", vp8x)
            RandomAccessFile(encodedFile, "r").use { encoded ->
                imageChunks.forEach { chunk ->
                    copyRange(
                        encoded,
                        chunk.rawStart,
                        chunk.rawEnd - chunk.rawStart,
                        output
                    )
                }
            }
            // Metadata chunks are required after image bitstream chunks in an extended WebP.
            RandomAccessFile(sourceFile, "r").use { source ->
                metadataChunks.sortedBy { if (it.type == "EXIF") 0 else 1 }.forEach { chunk ->
                    copyRange(source, chunk.rawStart, chunk.rawEnd - chunk.rawStart, output)
                }
            }
        }
        RandomAccessFile(outputFile, "rw").use { output ->
            output.seek(4L)
            writeLittleEndianInt(output, (output.length() - 8L).toInt())
        }
        parseWebp(outputFile)
    }

    private fun parseWebp(file: File): WebpIndex {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 20L) throw WebpFormatException("WebP file is too short.")
            val riff = ByteArray(4)
            input.readFully(riff)
            if (String(
                    riff,
                    Charsets.US_ASCII
                ) != "RIFF"
            ) throw WebpFormatException("Invalid WebP RIFF signature.")
            val riffSize = readLittleEndianUInt(input)
            val webp = ByteArray(4)
            input.readFully(webp)
            if (String(
                    webp,
                    Charsets.US_ASCII
                ) != "WEBP"
            ) throw WebpFormatException("Invalid WebP signature.")
            if (riffSize + 8L != input.length()) throw WebpFormatException("WebP RIFF size does not match the file length.")

            val chunks = mutableListOf<WebpChunk>()
            var animated = false
            var declaredWidth: Int? = null
            var declaredHeight: Int? = null
            while (input.filePointer < input.length()) {
                val rawStart = input.filePointer
                if (input.length() - rawStart < 8L) throw WebpFormatException("Truncated WebP chunk header.")
                val typeBytes = ByteArray(4)
                input.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                val dataLengthLong = readLittleEndianUInt(input)
                if (dataLengthLong > Int.MAX_VALUE) throw WebpFormatException("WebP chunk is too large.")
                val dataStart = input.filePointer
                val paddedLength = dataLengthLong + (dataLengthLong and 1L)
                if (paddedLength > input.length() - dataStart) throw WebpFormatException("WebP chunk extends past end of file.")
                val dataLength = dataLengthLong.toInt()
                val rawEnd = dataStart + paddedLength
                if (type == "ANIM" || type == "ANMF") animated = true
                if (type == "VP8X") {
                    if (dataLength < 10) throw WebpFormatException("Invalid WebP VP8X chunk.")
                    val header = ByteArray(10)
                    input.readFully(header)
                    val flags = header[0].toInt() and 0xFF
                    if (flags and VP8X_ANIMATION_FLAG != 0) animated = true
                    declaredWidth = 1 + readUInt24LE(header, 4)
                    declaredHeight = 1 + readUInt24LE(header, 7)
                }
                chunks += WebpChunk(type, rawStart, dataStart, dataLength, rawEnd)
                input.seek(rawEnd)
            }
            val hasImage = chunks.any { it.type == "VP8 " || it.type == "VP8L" }
            if (!hasImage) throw WebpFormatException("WebP image data is missing.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = declaredWidth ?: bounds.outWidth
            val height = declaredHeight ?: bounds.outHeight
            if (width <= 0 || height <= 0) throw WebpFormatException("WebP dimensions are invalid.")
            return WebpIndex(width, height, chunks, animated)
        }
    }

    private fun displayedDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> height to width

            else -> width to height
        }

    private fun readExifOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_UNDEFINED
    }

    private fun ensureWebpOutputTarget(uri: Uri): String? {
        val mimeType = cancellationAwareRunCatching { contentResolver.getType(uri) }.getOrNull()
        if (mimeType != null && mimeType !in setOf("image/webp", "application/octet-stream")) {
            return "The output destination is not identified as WebP. Choose a .webp destination."
        }
        val name = queryDisplayName(uri)
        if (name != null && name.contains('.') && !name.endsWith(".webp", ignoreCase = true)) {
            return "The output filename must end in .webp for lossless WebP optimization."
        }
        return null
    }

    private fun isOutputTargetEmpty(uri: Uri): Boolean? {
        val reportedSize = cancellationAwareRunCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
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
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? = cancellationAwareRunCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(
                    cursor.getColumnIndexOrThrow(
                        OpenableColumns.DISPLAY_NAME
                    )
                ) else null
            }
    }.getOrNull()

    private fun copyUriToFile(uri: Uri, destination: File) {
        val input =
            contentResolver.openInputStream(uri) ?: throw IOException("Could not open source URI.")
        input.use { source ->
            BufferedOutputStream(FileOutputStream(destination)).use { it ->
                source.copyTo(
                    it,
                    IO_BUFFER_SIZE
                )
            }
        }
    }

    private fun writeFileToUri(source: File, destinationUri: Uri) {
        val output = contentResolver.openOutputStream(destinationUri, "wt")
            ?: throw IOException("Could not open output URI.")
        output.use { destination ->
            BufferedInputStream(FileInputStream(source)).use { it ->
                it.copyTo(
                    destination,
                    IO_BUFFER_SIZE
                )
            }
            destination.flush()
        }
    }

    private fun newTempFile(prefix: String, suffix: String) =
        File.createTempFile(prefix, suffix, context.cacheDir)

    private fun copyRange(
        input: RandomAccessFile,
        start: Long,
        length: Long,
        output: OutputStream
    ) {
        input.seek(start)
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0L) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun writeWebpChunk(output: DataOutputStream, type: String, data: ByteArray) {
        output.write(type.toByteArray(Charsets.US_ASCII))
        writeLittleEndianInt(output, data.size)
        output.write(data)
        if (data.size and 1 == 1) output.write(0)
    }

    private fun writeLittleEndianInt(output: DataOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeLittleEndianInt(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun readLittleEndianUInt(input: RandomAccessFile): Long {
        val b0 = input.readUnsignedByte().toLong()
        val b1 = input.readUnsignedByte().toLong()
        val b2 = input.readUnsignedByte().toLong()
        val b3 = input.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)

    private data class WebpChunk(
        val type: String,
        val rawStart: Long,
        val dataStart: Long,
        val dataLength: Int,
        val rawEnd: Long
    )

    private data class WebpIndex(
        val width: Int,
        val height: Int,
        val chunks: List<WebpChunk>,
        val isAnimated: Boolean
    )

    private class WebpFormatException(message: String) : Exception(message)

    private companion object {
        const val MAX_BITMAP_PIXELS = 16_000_000L
        const val MAX_WEBP_DIMENSION = 16_777_216
        const val MAX_METADATA_CHUNK_BYTES = 16 * 1024 * 1024
        const val IO_BUFFER_SIZE = 64 * 1024
        const val VP8X_ICC_FLAG = 0x20
        const val VP8X_ALPHA_FLAG = 0x10
        const val VP8X_EXIF_FLAG = 0x08
        const val VP8X_XMP_FLAG = 0x04
        const val VP8X_ANIMATION_FLAG = 0x02
    }
}
