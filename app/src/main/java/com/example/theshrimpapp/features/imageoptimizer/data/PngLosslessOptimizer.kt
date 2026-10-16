package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.content.Context
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
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject

/**
 * PNG-specific lossless optimizer.
 *
 * Recompresses PNG IDAT data without decoding pixels. The decompressed PNG scanline stream is
 * checked byte-for-byte, while chunk CRCs, image properties, and the final file are validated.
 */
class PngLosslessOptimizer @Inject constructor(
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

        val temporaryFiles = mutableListOf<File>()
        var outputWriteAttempted = false
        var outputCommitted = false
        try {
            val inputFile = newTempFile("shrimp-input-", ".bin").also(temporaryFiles::add)
            copyUriToFile(inputUri, inputFile)
            val originalSize = inputFile.length()
            if (originalSize <= 0L) {
                return@withContext ImageOptimizationResult.Failed("The input is empty or could not be read.")
            }

            val format = detectFormat(inputFile)
                ?: return@withContext ImageOptimizationResult.Unsupported(
                    "The selected file is not a supported PNG, JPEG, or WebP image."
                )

            if (format != DetectedFormat.PNG) {
                val label = when (format) {
                    DetectedFormat.JPEG -> "JPEG"
                    DetectedFormat.WEBP -> "WebP"
                    DetectedFormat.PNG -> "PNG"
                }
                return@withContext ImageOptimizationResult.Unsupported(
                    "No verified lossless $label optimizer is configured. The source and destination were not modified."
                )
            }

            ensurePngOutputTarget(outputUri)?.let { message ->
                return@withContext ImageOptimizationResult.Failed(message)
            }

            val candidateFile = newTempFile("shrimp-png-candidate-", ".png")
                .also(temporaryFiles::add)
            val originalIndex = indexPng(inputFile)
            if (originalIndex.isAnimated) {
                return@withContext ImageOptimizationResult.Unsupported(
                    "Animated PNG is not supported by the current lossless optimizer. The source was not modified."
                )
            }

            val orientation = readExifOrientation(inputFile)
            val originalPixelDigest = recompressPngIdat(inputFile, originalIndex, candidateFile, options.preserveMetadata)
            val candidateIndex = indexPng(candidateFile)
            val candidateDigest = digestInflatedPngIdat(candidateFile, candidateIndex)
            if (!originalPixelDigest.contentEquals(candidateDigest)) {
                return@withContext ImageOptimizationResult.Failed(
                    "Lossless validation failed: the optimized PNG scanline data does not match the source."
                )
            }

            val candidateSize = candidateFile.length()
            if (candidateSize >= originalSize) {
                return@withContext ImageOptimizationResult.Unchanged(
                    "No lossless savings. The original is already as small or smaller than the candidate."
                )
            }

            // Validate once more immediately before writing. Output URIs may point to provider-backed
            // documents, so never assume they behave like regular filesystem paths.
            validatePngOutputTarget(outputUri)?.let { message ->
                return@withContext ImageOptimizationResult.Failed(message)
            }
            when (isOutputTargetEmpty(outputUri)) {
                true -> Unit
                false -> return@withContext ImageOptimizationResult.Failed(
                    "The output destination already contains data. Choose a new, empty output file to avoid overwriting existing content."
                )
                null -> return@withContext ImageOptimizationResult.Failed(
                    "The app could not verify that the output destination is empty. Choose a new output file."
                )
            }
            outputWriteAttempted = true
            writeFileToUri(candidateFile, outputUri)

            val readBackFile = newTempFile("shrimp-output-check-", ".png")
                .also(temporaryFiles::add)
            copyUriToFile(outputUri, readBackFile)
            val readBackIndex = indexPng(readBackFile)
            val readBackDigest = digestInflatedPngIdat(readBackFile, readBackIndex)
            val actualOutputSize = readBackFile.length()

            if (readBackIndex.width != originalIndex.width || readBackIndex.height != originalIndex.height) {
                return@withContext ImageOptimizationResult.Failed(
                    "The destination changed the image dimensions. Check the output document provider."
                )
            }
            if (!originalPixelDigest.contentEquals(readBackDigest)) {
                return@withContext ImageOptimizationResult.Failed(
                    "The saved output failed lossless validation. Do not replace the original with this file."
                )
            }
            if (actualOutputSize >= originalSize) {
                return@withContext ImageOptimizationResult.Failed(
                    "The destination output was not smaller than the original after writing. The output should not be treated as a successful optimization."
                )
            }

            val displaySize = displayedDimensions(
                originalIndex.width,
                originalIndex.height,
                orientation
            )
            val bytesSaved = originalSize - actualOutputSize
            val result = ImageOptimizationResult.Optimized(
                originalSizeBytes = originalSize,
                outputSizeBytes = actualOutputSize,
                format = OutputFormat.PNG,
                width = displaySize.first,
                height = displaySize.second,
                bytesSaved = bytesSaved,
                percentageReduction = (bytesSaved.toDouble() * 100.0 / originalSize.toDouble()).toFloat()
            )
            outputCommitted = true
            result
        } catch (e: PngFormatException) {
            ImageOptimizationResult.Failed(e.message ?: "The PNG file is invalid or unsupported.")
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (e: Exception) {
            ImageOptimizationResult.Failed(
                e.localizedMessage ?: "Unknown lossless optimization error. The original was not intentionally modified."
            )
        } finally {
            // The destination was verified empty before writing. If writing or validation fails,
            // attempt to remove the incomplete output; never delete a pre-existing non-empty file.
            if (outputWriteAttempted && !outputCommitted) {
                cancellationAwareRunCatching { contentResolver.delete(outputUri, null, null) }
            }
            temporaryFiles.forEach { file ->
                cancellationAwareRunCatching { file.delete() }
            }
        }
    }

    /**
     * Kept for callers that optimize in-memory inputs. Only PNG currently has a configured
     * stream-level optimizer. Other formats are returned unchanged rather than transcoded.
     */
    suspend fun optimizeBytes(
        inputBytes: ByteArray,
        mimeType: String,
        options: ImageProcessingOptions
    ): ByteArrayResult = withContext(Dispatchers.IO) {
        val temporaryFiles = mutableListOf<File>()
        try {
            val inputFile = newTempFile("shrimp-bytes-input-", ".bin").also(temporaryFiles::add)
            FileOutputStream(inputFile).use { it.write(inputBytes) }
            val originalSize = inputFile.length()
            val detectedFormat = detectFormat(inputFile)
            val declaredFormat = when {
                mimeType.contains("png", ignoreCase = true) -> DetectedFormat.PNG
                mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> DetectedFormat.JPEG
                mimeType.contains("webp", ignoreCase = true) -> DetectedFormat.WEBP
                else -> null
            }
            if (detectedFormat != DetectedFormat.PNG || (declaredFormat != null && declaredFormat != detectedFormat)) {
                return@withContext ByteArrayResult(inputBytes, false, originalSize, originalSize)
            }

            val index = indexPng(inputFile)
            if (index.isAnimated) {
                return@withContext ByteArrayResult(inputBytes, false, originalSize, originalSize)
            }
            val candidate = newTempFile("shrimp-bytes-candidate-", ".png").also(temporaryFiles::add)
            val sourceDigest = recompressPngIdat(inputFile, index, candidate, options.preserveMetadata)
            val candidateIndex = indexPng(candidate)
            val candidateDigest = digestInflatedPngIdat(candidate, candidateIndex)
            if (!sourceDigest.contentEquals(candidateDigest) || candidate.length() >= originalSize) {
                return@withContext ByteArrayResult(inputBytes, false, originalSize, originalSize)
            }

            val bytes = candidate.readBytes()
            ByteArrayResult(bytes, true, originalSize, bytes.size.toLong())
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) {
            ByteArrayResult(inputBytes, false, inputBytes.size.toLong(), inputBytes.size.toLong())
        } finally {
            temporaryFiles.forEach { cancellationAwareRunCatching { it.delete() } }
        }
    }

    private fun recompressPngIdat(
        sourceFile: File,
        source: PngIndex,
        candidateFile: File,
        preserveMetadata: Boolean
    ): ByteArray {
        if (source.idatChunks.isEmpty()) throw PngFormatException("PNG contains no image data.")

        val sourceZlib = newTempFile("shrimp-png-source-zlib-", ".bin")
        val inflatedData = newTempFile("shrimp-png-raw-", ".bin")
        val candidateZlib = newTempFile("shrimp-png-new-zlib-", ".bin")
        try {
            concatenateIdatData(sourceFile, source.idatChunks, sourceZlib)
            val rawDigest = inflateToFileAndDigest(sourceZlib, inflatedData)
            validatePngRawStream(source, inflatedData.length())
            val recompressedDigest = deflateFileAndDigest(inflatedData, candidateZlib)
            if (!rawDigest.contentEquals(recompressedDigest)) {
                throw PngFormatException("PNG recompression changed the uncompressed image data.")
            }
            writePngWithReplacementIdat(sourceFile, source, candidateFile, candidateZlib, preserveMetadata)
            return rawDigest
        } finally {
            sourceZlib.delete()
            inflatedData.delete()
            candidateZlib.delete()
        }
    }

    private fun indexPng(file: File): PngIndex {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < PNG_SIGNATURE.size) throw PngFormatException("PNG file is too short.")
            val signature = ByteArray(PNG_SIGNATURE.size)
            raf.readFully(signature)
            if (!signature.contentEquals(PNG_SIGNATURE)) throw PngFormatException("Invalid PNG signature.")

            val chunks = mutableListOf<PngChunk>()
            val idatChunks = mutableListOf<PngChunk>()
            var width = 0
            var height = 0
            var bitDepth = -1
            var colorType = -1
            var interlaceMethod = -1
            var seenIdat = false
            var endedIdat = false
            var sawIend = false
            var animated = false

            while (raf.filePointer < raf.length()) {
                val chunkStart = raf.filePointer
                if (raf.length() - chunkStart < 12L) throw PngFormatException("Truncated PNG chunk header.")
                val unsignedLength = raf.readInt().toLong() and 0xFFFF_FFFFL
                if (unsignedLength > Int.MAX_VALUE || unsignedLength > raf.length() - raf.filePointer - 8L) {
                    throw PngFormatException("PNG contains an invalid chunk length.")
                }
                val length = unsignedLength.toInt()
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, Charsets.US_ASCII)
                val dataStart = raf.filePointer
                val crc = CRC32().apply { update(typeBytes) }
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size)
                    raf.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    remaining -= count
                }
                val storedCrc = raf.readInt().toLong() and 0xFFFF_FFFFL
                if (crc.value != storedCrc) throw PngFormatException("PNG chunk $type has an invalid CRC.")
                val chunkEnd = raf.filePointer
                val chunk = PngChunk(type, chunkStart, dataStart, length, chunkEnd)
                chunks += chunk

                when (type) {
                    "IHDR" -> {
                        if (chunks.size != 1 || length != 13) throw PngFormatException("Invalid PNG IHDR chunk.")
                        raf.seek(dataStart)
                        width = raf.readInt()
                        height = raf.readInt()
                        bitDepth = raf.readUnsignedByte()
                        colorType = raf.readUnsignedByte()
                        val compressionMethod = raf.readUnsignedByte()
                        val filterMethod = raf.readUnsignedByte()
                        interlaceMethod = raf.readUnsignedByte()
                        raf.seek(chunkEnd)
                        if (width <= 0 || height <= 0) throw PngFormatException("PNG has invalid dimensions.")
                        if (compressionMethod != 0 || filterMethod != 0 || interlaceMethod !in 0..1) {
                            throw PngFormatException("PNG uses an unsupported compression, filter, or interlace method.")
                        }
                    }
                    "IDAT" -> {
                        if (endedIdat) throw PngFormatException("PNG IDAT chunks must be consecutive.")
                        seenIdat = true
                        idatChunks += chunk
                    }
                    "acTL" -> animated = true // APNG; do not rewrite only its default image stream.
                    else -> if (seenIdat) endedIdat = true
                }

                if (type == "IEND") {
                    if (length != 0) throw PngFormatException("Invalid PNG IEND chunk.")
                    sawIend = true
                    if (raf.filePointer != raf.length()) throw PngFormatException("Unexpected data after PNG IEND.")
                    break
                }
            }

            if (chunks.firstOrNull()?.type != "IHDR" || !seenIdat || !sawIend) {
                throw PngFormatException("PNG is missing required chunks.")
            }
            return PngIndex(width, height, bitDepth, colorType, interlaceMethod, chunks, idatChunks, animated)
        }
    }

    private fun concatenateIdatData(sourceFile: File, chunks: List<PngChunk>, outputFile: File) {
        RandomAccessFile(sourceFile, "r").use { input ->
            BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                for (chunk in chunks) {
                    input.seek(chunk.dataStart)
                    var remaining = chunk.dataLength
                    while (remaining > 0) {
                        val count = minOf(remaining, buffer.size)
                        input.readFully(buffer, 0, count)
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
        }
    }

    private fun inflateToFileAndDigest(compressedFile: File, rawFile: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        InflaterInputStream(BufferedInputStream(FileInputStream(compressedFile))).use { input ->
            BufferedOutputStream(FileOutputStream(rawFile)).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        return digest.digest()
    }

    private fun deflateFileAndDigest(rawFile: File, compressedFile: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        try {
            FileOutputStream(compressedFile).buffered(COPY_BUFFER_SIZE).use { fileOutput ->
                DeflaterOutputStream(fileOutput, deflater, COPY_BUFFER_SIZE).use { output ->
                    BufferedInputStream(FileInputStream(rawFile)).use { input ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
        } finally {
            deflater.end()
        }
        return digest.digest()
    }

    private fun digestInflatedPngIdat(file: File, index: PngIndex): ByteArray {
        val temporaryFiles = mutableListOf<File>()
        try {
            val compressed = newTempFile("shrimp-verify-zlib-", ".bin").also(temporaryFiles::add)
            val raw = newTempFile("shrimp-verify-raw-", ".bin").also(temporaryFiles::add)
            concatenateIdatData(file, index.idatChunks, compressed)
            val digest = inflateToFileAndDigest(compressed, raw)
            validatePngRawStream(index, raw.length())
            return digest
        } finally {
            temporaryFiles.forEach { cancellationAwareRunCatching { it.delete() } }
        }
    }

    /** Validates PNG scanline byte count for both standard and Adam7-interlaced images. */
    private fun validatePngRawStream(index: PngIndex, actualRawBytes: Long) {
        val channels = when (index.colorType) {
            0 -> 1 // grayscale
            2 -> 3 // truecolor
            3 -> 1 // indexed color
            4 -> 2 // grayscale + alpha
            6 -> 4 // truecolor + alpha
            else -> throw PngFormatException("PNG has an unsupported color type.")
        }
        val legalBitDepth = when (index.colorType) {
            0 -> index.bitDepth in setOf(1, 2, 4, 8, 16)
            2 -> index.bitDepth == 8 || index.bitDepth == 16
            3 -> index.bitDepth in setOf(1, 2, 4, 8)
            4 -> index.bitDepth == 8 || index.bitDepth == 16
            6 -> index.bitDepth == 8 || index.bitDepth == 16
            else -> false
        }
        if (!legalBitDepth) throw PngFormatException("PNG has an invalid bit depth for its color type.")

        val bitsPerPixel = channels.toLong() * index.bitDepth.toLong()
        val expected = if (index.interlaceMethod == 0) {
            scanlineBytes(index.width.toLong(), index.height.toLong(), bitsPerPixel)
        } else {
            val xStart = intArrayOf(0, 4, 0, 2, 0, 1, 0)
            val yStart = intArrayOf(0, 0, 4, 0, 2, 0, 1)
            val xStep = intArrayOf(8, 8, 4, 4, 2, 2, 1)
            val yStep = intArrayOf(8, 8, 8, 4, 4, 2, 2)
            var total = 0L
            for (pass in 0..6) {
                val passWidth = passDimension(index.width, xStart[pass], xStep[pass])
                val passHeight = passDimension(index.height, yStart[pass], yStep[pass])
                if (passWidth > 0L && passHeight > 0L) {
                    total = Math.addExact(total, scanlineBytes(passWidth, passHeight, bitsPerPixel))
                }
            }
            total
        }
        if (actualRawBytes != expected) {
            throw PngFormatException("PNG image-data length does not match its declared dimensions and color type.")
        }
    }

    private fun scanlineBytes(width: Long, height: Long, bitsPerPixel: Long): Long {
        val rowBytes = Math.addExact(Math.multiplyExact(width, bitsPerPixel), 7L) / 8L
        return Math.multiplyExact(height, Math.addExact(rowBytes, 1L)) // one filter byte per row
    }

    private fun passDimension(size: Int, start: Int, step: Int): Long =
        if (size <= start) 0L else (size.toLong() - start + step - 1L) / step.toLong()

    private fun writePngWithReplacementIdat(
        sourceFile: File,
        source: PngIndex,
        candidateFile: File,
        compressedIdatFile: File,
        preserveMetadata: Boolean
    ) {
        var insertedIdat = false
        val orientation = readExifOrientation(sourceFile)
        RandomAccessFile(sourceFile, "r").use { original ->
            DataOutputStream(BufferedOutputStream(FileOutputStream(candidateFile))).use { output ->
                output.write(PNG_SIGNATURE)
                for (chunk in source.chunks) {
                    if (chunk.type == "IDAT") {
                        if (!insertedIdat) {
                            writeIdatChunks(output, compressedIdatFile)
                            insertedIdat = true
                        }
                        continue
                    }
                    if (!preserveMetadata && shouldRemoveMetadataChunk(chunk.type, orientation)) continue
                    copyRange(original, chunk.rawStart, chunk.rawEnd - chunk.rawStart, output)
                }
            }
        }
        if (!insertedIdat) throw PngFormatException("No IDAT position was found in the PNG.")
    }

    private fun writeIdatChunks(output: DataOutputStream, compressedFile: File) {
        RandomAccessFile(compressedFile, "r").use { input ->
            val buffer = ByteArray(IDAT_CHUNK_SIZE)
            val type = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
            while (true) {
                var total = 0
                while (total < buffer.size) {
                    val count = input.read(buffer, total, buffer.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                if (total == 0) break
                output.writeInt(total)
                output.write(type)
                output.write(buffer, 0, total)
                val crc = CRC32().apply {
                    update(type)
                    update(buffer, 0, total)
                }
                output.writeInt(crc.value.toInt())
                if (total < buffer.size) break
            }
        }
    }

    private fun copyRange(input: RandomAccessFile, start: Long, length: Long, output: OutputStream) {
        input.seek(start)
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = length
        while (remaining > 0L) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            output.write(buffer, 0, count)
            remaining -= count.toLong()
        }
    }

    private fun shouldRemoveMetadataChunk(type: String, orientation: Int): Boolean {
        // Keep color-rendering chunks (iCCP, sRGB, gAMA, cHRM) and other image-semantic chunks.
        // If the EXIF orientation is non-normal, keep the eXIf chunk so the image does not render
        // sideways; this also means GPS/camera fields within that same EXIF chunk are retained.
        return when (type) {
            "tEXt", "zTXt", "iTXt", "tIME" -> true
            "eXIf" -> orientation == ExifInterface.ORIENTATION_NORMAL ||
                orientation == ExifInterface.ORIENTATION_UNDEFINED
            else -> false
        }
    }

    private fun readExifOrientation(file: File): Int = try {
        FileInputStream(file).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }
    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun displayedDimensions(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> height to width
            else -> width to height
        }

    private fun detectFormat(file: File): DetectedFormat? {
        FileInputStream(file).buffered().use { input ->
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val read = input.read(header, count, header.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            if (count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return DetectedFormat.PNG
            if (count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                return DetectedFormat.JPEG
            }
            if (count >= 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            ) return DetectedFormat.WEBP
        }
        return null
    }

    private fun ensurePngOutputTarget(uri: Uri): String? = validatePngOutputTarget(uri)

    private fun validatePngOutputTarget(uri: Uri): String? {
        val mimeType = cancellationAwareRunCatching { contentResolver.getType(uri) }.getOrNull()
        if (mimeType != null && mimeType != "image/png" && mimeType != "application/octet-stream") {
            return "The output destination is not identified as PNG. Choose or create a .png destination."
        }
        val displayName = queryDisplayName(uri)
        if (displayName != null && displayName.contains('.') && !displayName.endsWith(".png", ignoreCase = true)) {
            return "The output filename must end in .png for lossless PNG optimization."
        }
        return null
    }

    /**
     * Returns true only if the target can be verified empty, false if non-empty, or null when
     * its state cannot be verified. This prevents accidental overwrite through a distinct URI.
     */
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
        val input = contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open the source URI for reading.")
        input.use { source ->
            BufferedOutputStream(FileOutputStream(destination)).use { output -> source.copyTo(output, COPY_BUFFER_SIZE) }
        }
    }

    private fun writeFileToUri(source: File, destinationUri: Uri) {
        val output = contentResolver.openOutputStream(destinationUri, "wt")
            ?: throw IllegalStateException("Could not open the output URI for writing.")
        output.use { destination ->
            BufferedInputStream(FileInputStream(source)).use { input -> input.copyTo(destination, COPY_BUFFER_SIZE) }
            destination.flush()
        }
    }

    private fun newTempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix, context.cacheDir)

    private enum class DetectedFormat { PNG, JPEG, WEBP }

    private data class PngChunk(
        val type: String,
        val rawStart: Long,
        val dataStart: Long,
        val dataLength: Int,
        val rawEnd: Long
    )

    private data class PngIndex(
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        val interlaceMethod: Int,
        val chunks: List<PngChunk>,
        val idatChunks: List<PngChunk>,
        val isAnimated: Boolean
    )

    private class PngFormatException(message: String) : Exception(message)

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val IDAT_CHUNK_SIZE = 64 * 1024
    }
}

data class ByteArrayResult(
    val bytes: ByteArray,
    val isOptimized: Boolean,
    val originalSize: Long,
    val outputSize: Long
)
