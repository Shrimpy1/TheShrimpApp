package com.example.theshrimpapp.features.imageoptimizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultLosslessImageOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.JpegLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.JpegTranNative
import com.example.theshrimpapp.features.imageoptimizer.data.PngLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.WebpLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizationResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater

@RunWith(AndroidJUnit4::class)
class LosslessImageOptimizerInstrumentedTest {

    private lateinit var context: Context
    private lateinit var optimizer: DefaultLosslessImageOptimizer
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val resolver = context.contentResolver
        optimizer = DefaultLosslessImageOptimizer(
            pngOptimizer = PngLosslessOptimizer(context, resolver),
            jpegOptimizer = JpegLosslessOptimizer(context, resolver),
            webpOptimizer = WebpLosslessOptimizer(context, resolver),
            contentResolver = resolver
        )
    }

    @After
    fun tearDown() {
        tempFiles.forEach { runCatching { it.delete() } }
    }

    private fun createTempFile(prefix: String, suffix: String): File {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        tempFiles.add(file)
        return file
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    /**
     * Builds a valid RGBA PNG with deliberately uncompressed DEFLATE data. Optional chunks are
     * inserted after IHDR and before IDAT, so tests can cover metadata and APNG detection without
     * depending on a pre-existing asset file.
     */
    private fun createPoorlyCompressedPngFile(
        width: Int,
        height: Int,
        chunksBeforeIdat: List<Pair<String, ByteArray>> = emptyList()
    ): File {
        require(width > 0 && height > 0)
        val file = createTempFile("poor-", ".png")
        val rowBytes = width * 4
        val rawData = ByteArray(height * (1 + rowBytes))
        var offset = 0
        for (y in 0 until height) {
            rawData[offset++] = 0 // PNG filter: none
            for (x in 0 until width) {
                rawData[offset++] = (x * 17 + y * 3).toByte()
                rawData[offset++] = (y * 19 + x * 5).toByte()
                rawData[offset++] = (x * 7 + y * 11).toByte()
                // Include transparent and partially transparent pixels.
                rawData[offset++] = (when ((x + y) % 4) {
                    0 -> 0
                    1 -> 85
                    2 -> 170
                    else -> 255
                }).toByte()
            }
        }

        val deflater = Deflater(Deflater.NO_COMPRESSION)
        val compressedOutput = ByteArrayOutputStream()
        try {
            deflater.setInput(rawData)
            deflater.finish()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0 && !deflater.finished()) {
                    throw IllegalStateException("Could not finish test PNG compression.")
                }
                compressedOutput.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        val zlibData = compressedOutput.toByteArray()

        FileOutputStream(file).use { fos ->
            fos.write(PNG_SIGNATURE)

            // IHDR
            val ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
                .putInt(width)
                .putInt(height)
                .put(8)  // bit depth
                .put(6)  // color type: RGBA
                .put(0)  // compression method
                .put(0)  // filter method
                .put(0)  // interlace method
                .array()
            writeChunk(fos, "IHDR", ihdr)

            chunksBeforeIdat.forEach { (type, data) -> writeChunk(fos, type, data) }
            writeChunk(fos, "IDAT", zlibData)
            writeChunk(fos, "IEND", ByteArray(0))
        }
        return file
    }

    private fun textChunk(keyword: String, text: String): ByteArray =
        "$keyword\u0000$text".toByteArray(Charsets.ISO_8859_1)

    /** Minimal little-endian TIFF payload for a PNG eXIf chunk with the given orientation. */
    private fun exifOrientationPayload(orientation: Int): ByteArray {
        val buffer = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put('I'.code.toByte())
        buffer.put('I'.code.toByte())
        buffer.putShort(42.toShort())
        buffer.putInt(8) // first IFD offset
        buffer.position(8)
        buffer.putShort(1.toShort()) // one IFD entry
        buffer.putShort(0x0112.toShort()) // Orientation tag
        buffer.putShort(3.toShort()) // TIFF type SHORT
        buffer.putInt(1) // one value
        buffer.putShort(orientation.toShort())
        buffer.putShort(0.toShort()) // padding for inline SHORT
        buffer.putInt(0) // no next IFD
        return buffer.array()
    }

    private fun animationControlChunk(frameCount: Int = 1, playCount: Int = 0): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(frameCount)
            .putInt(playCount)
            .array()

    private fun firstFrameControlChunk(width: Int, height: Int): ByteArray =
        ByteBuffer.allocate(26).order(ByteOrder.BIG_ENDIAN)
            .putInt(0) // sequence number
            .putInt(width)
            .putInt(height)
            .putInt(0) // x offset
            .putInt(0) // y offset
            .putShort(1.toShort()) // delay numerator
            .putShort(10.toShort()) // delay denominator
            .put(0) // dispose op: none
            .put(0) // blend op: source
            .array()

    private fun writeChunk(output: FileOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val dos = DataOutputStream(output)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        dos.writeInt(crc.value.toInt())
    }

    private fun readPngChunkData(file: File, wantedType: String): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        RandomAccessFile(file, "r").use { raf ->
            assertTrue("PNG signature missing", raf.length() >= PNG_SIGNATURE.size)
            val signature = ByteArray(PNG_SIGNATURE.size)
            raf.readFully(signature)
            assertArrayEquals(PNG_SIGNATURE, signature)

            while (raf.filePointer < raf.length()) {
                assertTrue("Truncated PNG chunk header", raf.length() - raf.filePointer >= 12L)
                val length = raf.readInt().toLong() and 0xFFFF_FFFFL
                assertTrue("Unreasonably large PNG chunk", length <= Int.MAX_VALUE)
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                assertTrue(
                    "PNG chunk exceeds file length",
                    length <= raf.length() - raf.filePointer - 4L
                )
                val type = String(typeBytes, Charsets.US_ASCII)
                val data = ByteArray(length.toInt())
                raf.readFully(data)
                raf.readInt() // stored CRC; production optimizer validates CRCs
                if (type == wantedType) found += data
                if (type == "IEND") break
            }
        }
        return found
    }

    private fun createValidJpegFile(): File = createBitmapEncodedFile(
        prefix = "valid-jpeg-",
        suffix = ".jpg",
        format = Bitmap.CompressFormat.JPEG
    )

    private fun createValidWebpFile(): File = createBitmapEncodedFile(
        prefix = "valid-webp-",
        suffix = ".webp",
        format = Bitmap.CompressFormat.WEBP_LOSSLESS
    )

    private fun createBitmapEncodedFile(
        prefix: String,
        suffix: String,
        format: Bitmap.CompressFormat,
        width: Int = 24,
        height: Int = 16
    ): File {
        val file = createTempFile(prefix, suffix)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    bitmap.setPixel(
                        x,
                        y,
                        Color.argb(255, (x * 9) % 256, (y * 13) % 256, ((x + y) * 7) % 256)
                    )
                }
            }
            FileOutputStream(file).use { output ->
                assertTrue(
                    "Android encoder failed to create $suffix fixture",
                    bitmap.compress(format, 90, output)
                )
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue("Encoded test image should not be empty", file.length() > 0)
        return file
    }

    private fun assertDecodedPixelsEqual(source: File, output: File) {
        val sourceBitmap = requireNotNull(BitmapFactory.decodeFile(source.absolutePath)) {
            "Source PNG could not be decoded"
        }
        val outputBitmap = requireNotNull(BitmapFactory.decodeFile(output.absolutePath)) {
            "Output PNG could not be decoded"
        }
        try {
            assertEquals("Width changed", sourceBitmap.width, outputBitmap.width)
            assertEquals("Height changed", sourceBitmap.height, outputBitmap.height)
            assertEquals("Source should expose alpha", true, sourceBitmap.hasAlpha())
            assertEquals(
                "Output should preserve alpha",
                sourceBitmap.hasAlpha(),
                outputBitmap.hasAlpha()
            )
            for (y in 0 until sourceBitmap.height) {
                for (x in 0 until sourceBitmap.width) {
                    assertEquals(
                        "Decoded pixel mismatch at ($x, $y)",
                        sourceBitmap.getPixel(x, y),
                        outputBitmap.getPixel(x, y)
                    )
                }
            }
        } finally {
            sourceBitmap.recycle()
            outputBitmap.recycle()
        }
    }

    /** JPEG has no alpha channel, so compare decoded ARGB values without asserting alpha presence. */
    private fun assertDecodedRgbPixelsEqual(source: File, output: File) {
        val sourceBitmap = requireNotNull(BitmapFactory.decodeFile(source.absolutePath)) {
            "Source image could not be decoded"
        }
        val outputBitmap = requireNotNull(BitmapFactory.decodeFile(output.absolutePath)) {
            "Output image could not be decoded"
        }
        try {
            assertEquals("Width changed", sourceBitmap.width, outputBitmap.width)
            assertEquals("Height changed", sourceBitmap.height, outputBitmap.height)
            for (y in 0 until sourceBitmap.height) {
                for (x in 0 until sourceBitmap.width) {
                    assertEquals(
                        "Decoded pixel mismatch at ($x, $y)",
                        sourceBitmap.getPixel(x, y),
                        outputBitmap.getPixel(x, y)
                    )
                }
            }
        } finally {
            sourceBitmap.recycle()
            outputBitmap.recycle()
        }
    }

    private fun readExifOrientation(file: File): Int =
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )

    private fun assertFileIsEmpty(file: File) {
        assertEquals("Destination should remain empty", 0L, file.length())
    }

    private fun corruptChunkCrc(file: File, wantedType: String) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(PNG_SIGNATURE.size.toLong())
            while (raf.filePointer < raf.length()) {
                val length = raf.readInt().toLong() and 0xFFFF_FFFFL
                require(length <= Int.MAX_VALUE)
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val dataStart = raf.filePointer
                val crcOffset = dataStart + length
                require(crcOffset + 4L <= raf.length())
                val type = String(typeBytes, Charsets.US_ASCII)
                if (type == wantedType) {
                    raf.seek(crcOffset)
                    val originalCrc = raf.readInt()
                    raf.seek(crcOffset)
                    raf.writeInt(originalCrc xor 1)
                    return
                }
                raf.seek(crcOffset + 4L)
            }
        }
        fail("Test fixture did not contain a $wantedType chunk")
    }

    @Test
    fun testSuccessfulPngOptimizationPreservesDecodedPixelsAndSource() = runBlocking {
        val inputFile = createPoorlyCompressedPngFile(32, 24)
        val outputFile = createTempFile("output-", ".png")
        outputFile.writeBytes(ByteArray(0))

        val originalSha = sha256(inputFile)
        val originalSize = inputFile.length()
        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = true)
        )

        assertTrue("Expected Optimized, got $result", result is ImageOptimizationResult.Optimized)
        val optimized = result as ImageOptimizationResult.Optimized
        assertEquals(originalSize, optimized.originalSizeBytes)
        assertEquals(
            "Reported output size must match file size",
            outputFile.length(),
            optimized.outputSizeBytes
        )
        assertTrue("Output should be smaller than source", outputFile.length() < originalSize)
        assertEquals(32, optimized.width)
        assertEquals(24, optimized.height)
        assertTrue("Output file should not be empty", outputFile.length() > 0)
        assertDecodedPixelsEqual(inputFile, outputFile)
        assertArrayEquals("Source file must remain unchanged", originalSha, sha256(inputFile))
    }

    @Test
    fun testUnchangedWhenCandidateCannotGetSmaller() = runBlocking {
        val inputFile = createPoorlyCompressedPngFile(32, 24)
        val optimizedCandidate = createTempFile("candidate-", ".png")
        optimizedCandidate.writeBytes(ByteArray(0))

        val firstResult = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(optimizedCandidate),
            ImageProcessingOptions(preserveMetadata = true)
        )
        assertTrue(
            "First pass must create a known optimized candidate, got $firstResult",
            firstResult is ImageOptimizationResult.Optimized
        )
        assertTrue("Candidate must exist and be non-empty", optimizedCandidate.length() > 0)
        assertDecodedPixelsEqual(inputFile, optimizedCandidate)

        val candidateHash = sha256(optimizedCandidate)
        val secondOutput = createTempFile("output2-", ".png")
        secondOutput.writeBytes(ByteArray(0))
        val secondResult = optimizer.optimize(
            Uri.fromFile(optimizedCandidate),
            Uri.fromFile(secondOutput),
            ImageProcessingOptions(preserveMetadata = true)
        )

        assertTrue(
            "Second pass should report Unchanged, got $secondResult",
            secondResult is ImageOptimizationResult.Unchanged
        )
        assertFileIsEmpty(secondOutput)
        assertArrayEquals(
            "Candidate/source must not be modified",
            candidateHash,
            sha256(optimizedCandidate)
        )
    }

    @Test
    fun testPngTextMetadataIsPreservedWhenRequested() = runBlocking {
        val metadata = textChunk("Comment", "fixture metadata must survive")
        val inputFile = createPoorlyCompressedPngFile(
            32,
            24,
            chunksBeforeIdat = listOf("tEXt" to metadata)
        )
        val outputFile = createTempFile("metadata-preserved-", ".png")
        outputFile.writeBytes(ByteArray(0))

        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = true)
        )

        assertTrue(
            "Expected metadata-preserving optimization, got $result",
            result is ImageOptimizationResult.Optimized
        )
        assertTrue(
            "Output should contain the original tEXt chunk",
            readPngChunkData(outputFile, "tEXt").any { it.contentEquals(metadata) }
        )
        assertDecodedPixelsEqual(inputFile, outputFile)
    }

    @Test
    fun testOptionalMetadataRemovalDoesNotBreakNonNormalOrientation() = runBlocking {
        val metadata = textChunk("Comment", "optional text metadata")
        val sourceWidth = 32
        val sourceHeight = 24
        val inputFile = createPoorlyCompressedPngFile(
            sourceWidth,
            sourceHeight,
            chunksBeforeIdat = listOf(
                "tEXt" to metadata,
                "eXIf" to exifOrientationPayload(ExifInterface.ORIENTATION_ROTATE_90)
            )
        )
        assertEquals(
            "Test fixture should have EXIF orientation 90 degrees",
            ExifInterface.ORIENTATION_ROTATE_90,
            readExifOrientation(inputFile)
        )

        val outputFile = createTempFile("metadata-removed-", ".png")
        outputFile.writeBytes(ByteArray(0))
        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = false)
        )

        assertTrue(
            "Expected successful optimization, got $result",
            result is ImageOptimizationResult.Optimized
        )
        assertTrue(
            "Optional tEXt metadata should be removed",
            readPngChunkData(outputFile, "tEXt").isEmpty()
        )
        assertEquals(
            "Non-normal orientation metadata must remain so display orientation is preserved",
            ExifInterface.ORIENTATION_ROTATE_90,
            readExifOrientation(outputFile)
        )
        val optimized = result as ImageOptimizationResult.Optimized
        assertEquals(sourceHeight, optimized.width)
        assertEquals(sourceWidth, optimized.height)
        assertDecodedPixelsEqual(inputFile, outputFile)
    }

    @Test
    fun testJpegUsesLosslessBackendOrFailsSafelyWhenUnavailable() = runBlocking {
        val jpegFile = createBitmapEncodedFile(
            prefix = "valid-jpeg-", suffix = ".jpg", format = Bitmap.CompressFormat.JPEG,
            width = 256, height = 192
        )
        val outputFile = createTempFile("output-", ".jpg")
        outputFile.writeBytes(ByteArray(0))
        val sourceHash = sha256(jpegFile)
        val originalSize = jpegFile.length()

        val result = optimizer.optimize(
            Uri.fromFile(jpegFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = true)
        )

        when (result) {
            is ImageOptimizationResult.Optimized -> {
                assertTrue("JPEG output must be smaller", outputFile.length() < originalSize)
                assertEquals(outputFile.length(), result.outputSizeBytes)
                assertDecodedRgbPixelsEqual(jpegFile, outputFile)
            }

            is ImageOptimizationResult.Unchanged -> assertFileIsEmpty(outputFile)
            is ImageOptimizationResult.Unsupported -> {
                assertFalse(
                    "Native backend should be loaded for an optimized result",
                    JpegTranNative.isAvailable()
                )
                assertFileIsEmpty(outputFile)
            }

            is ImageOptimizationResult.Failed -> fail("JPEG optimization failed: ${result.error}")
        }
        assertArrayEquals("JPEG source must remain unchanged", sourceHash, sha256(jpegFile))
    }

    @Test
    fun testJpegNativeBackendPreservesDecodedPixelsWhenAvailable() = runBlocking {
        assumeTrue(
            "Skipped until the libjpeg-turbo native backend is configured",
            JpegTranNative.isAvailable()
        )
        val jpegFile = createBitmapEncodedFile(
            prefix = "jpeg-pixel-fixture-", suffix = ".jpg", format = Bitmap.CompressFormat.JPEG,
            width = 256, height = 192
        )
        val outputFile = createTempFile("jpeg-pixel-output-", ".jpg")
        outputFile.writeBytes(ByteArray(0))
        val sourceHash = sha256(jpegFile)
        val result = optimizer.optimize(
            Uri.fromFile(jpegFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = true)
        )

        when (result) {
            is ImageOptimizationResult.Optimized -> {
                assertTrue(outputFile.length() < jpegFile.length())
                assertDecodedRgbPixelsEqual(jpegFile, outputFile)
            }

            is ImageOptimizationResult.Unchanged -> assertFileIsEmpty(outputFile)
            else -> fail("Configured JPEG backend should optimize or report no savings, got $result")
        }
        assertArrayEquals("JPEG source must remain unchanged", sourceHash, sha256(jpegFile))
    }

    @Test
    fun testJpegNativeBackendPreservesPixelsExifAndOrientationWhenAvailable() {
        assumeTrue(
            "Skipped until the libjpeg-turbo native backend is configured",
            JpegTranNative.isAvailable()
        )
        val inputFile = createBitmapEncodedFile(
            prefix = "jpeg-exif-input-", suffix = ".jpg", format = Bitmap.CompressFormat.JPEG,
            width = 256, height = 192
        )
        ExifInterface(inputFile.absolutePath).apply {
            setAttribute(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_ROTATE_90.toString()
            )
            setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "JPEG lossless fixture metadata")
            saveAttributes()
        }
        val outputFile = createTempFile("jpeg-exif-output-", ".jpg")
        val sourceHash = sha256(inputFile)

        assertEquals(
            "Native JPEG optimizer should succeed",
            JpegTranNative.STATUS_OK,
            JpegTranNative.optimizeFile(inputFile.absolutePath, outputFile.absolutePath, true)
        )
        assertDecodedRgbPixelsEqual(inputFile, outputFile)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, readExifOrientation(outputFile))
        assertEquals(
            "JPEG lossless optimizer should preserve EXIF description",
            "JPEG lossless fixture metadata",
            ExifInterface(outputFile.absolutePath).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        )
        assertArrayEquals(
            "Native JPEG optimization must not change the source",
            sourceHash,
            sha256(inputFile)
        )
    }

    @Test
    fun testJpegNativeBackendCanStripOptionalExifWhenOrientationIsNormal() {
        assumeTrue(
            "Skipped until the libjpeg-turbo native backend is configured",
            JpegTranNative.isAvailable()
        )
        val inputFile = createBitmapEncodedFile(
            prefix = "jpeg-strip-input-", suffix = ".jpg", format = Bitmap.CompressFormat.JPEG,
            width = 256, height = 192
        )
        ExifInterface(inputFile.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            setAttribute(
                ExifInterface.TAG_IMAGE_DESCRIPTION,
                "optional metadata that should be stripped"
            )
            saveAttributes()
        }
        val outputFile = createTempFile("jpeg-strip-output-", ".jpg")
        val sourceHash = sha256(inputFile)

        assertEquals(
            "Native JPEG optimizer should succeed",
            JpegTranNative.STATUS_OK,
            JpegTranNative.optimizeFile(inputFile.absolutePath, outputFile.absolutePath, false)
        )
        assertDecodedRgbPixelsEqual(inputFile, outputFile)
        assertNull(
            "Optional EXIF description should be stripped",
            ExifInterface(outputFile.absolutePath).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        )
        assertArrayEquals(
            "Native JPEG optimization must not change the source",
            sourceHash,
            sha256(inputFile)
        )
    }

    @Test
    fun testWebpLosslessOptimizationPreservesPixelsOrReportsNoSavings() = runBlocking {
        val webpFile = createValidWebpFile()
        val outputFile = createTempFile("output-", ".webp")
        outputFile.writeBytes(ByteArray(0))
        val sourceHash = sha256(webpFile)
        val originalSize = webpFile.length()

        val result = optimizer.optimize(
            Uri.fromFile(webpFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions(preserveMetadata = true)
        )

        when (result) {
            is ImageOptimizationResult.Optimized -> {
                assertTrue("WebP output must be smaller", outputFile.length() < originalSize)
                assertEquals(outputFile.length(), result.outputSizeBytes)
                assertDecodedRgbPixelsEqual(webpFile, outputFile)
            }

            is ImageOptimizationResult.Unchanged -> assertFileIsEmpty(outputFile)
            is ImageOptimizationResult.Unsupported -> {
                // Some Android encoders attach ICCP even to an sRGB bitmap. This remains protected metadata.
                val bytes = webpFile.readBytes()
                var offset = 12
                var hasIcc = false
                while (offset + 8 <= bytes.size) {
                    if (String(bytes, offset, 4, Charsets.US_ASCII) == "ICCP") { hasIcc = true; break }
                    val size = (0..3).fold(0L) { value, i -> value or ((bytes[offset + 4 + i].toLong() and 255) shl (8 * i)) }
                    val next = offset.toLong() + 8 + size + (size and 1)
                    if (next > bytes.size) break
                    offset = next.toInt()
                }
                assertTrue("Only the fixture's actual ICC profile may explain unsupported output", hasIcc && result.reason.contains("ICC"))
                assertFileIsEmpty(outputFile)
            }
            else -> fail("Valid static WebP should optimize, report no savings, or fail validation explicitly; got $result")
        }
        assertArrayEquals("WebP source must remain unchanged", sourceHash, sha256(webpFile))
    }

    @Test
    fun testTruncatedPngFailsWithoutWritingOutput() = runBlocking {
        val inputFile = createPoorlyCompressedPngFile(16, 16)
        RandomAccessFile(inputFile, "rw").use { it.setLength(it.length() - 5L) }
        val sourceHash = sha256(inputFile)
        val outputFile = createTempFile("truncated-output-", ".png")
        outputFile.writeBytes(ByteArray(0))

        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions()
        )

        assertTrue(
            "Truncated PNG should fail, got $result",
            result is ImageOptimizationResult.Failed
        )
        assertArrayEquals("Malformed input must not be modified", sourceHash, sha256(inputFile))
        assertFileIsEmpty(outputFile)
    }

    @Test
    fun testInvalidPngChunkCrcFailsWithoutWritingOutput() = runBlocking {
        val inputFile = createPoorlyCompressedPngFile(16, 16)
        corruptChunkCrc(inputFile, "IDAT")
        val sourceHash = sha256(inputFile)
        val outputFile = createTempFile("bad-crc-output-", ".png")
        outputFile.writeBytes(ByteArray(0))

        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions()
        )

        assertTrue(
            "PNG with an invalid IDAT CRC should fail, got $result",
            result is ImageOptimizationResult.Failed
        )
        assertArrayEquals("Malformed input must not be modified", sourceHash, sha256(inputFile))
        assertFileIsEmpty(outputFile)
    }

    @Test
    fun testPngWithAnimationControlChunkIsUnsupported() = runBlocking {
        val width = 16
        val height = 16
        val inputFile = createPoorlyCompressedPngFile(
            width,
            height,
            chunksBeforeIdat = listOf(
                "acTL" to animationControlChunk(),
                "fcTL" to firstFrameControlChunk(width, height)
            )
        )
        val sourceHash = sha256(inputFile)
        val outputFile = createTempFile("apng-output-", ".png")
        outputFile.writeBytes(ByteArray(0))

        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions()
        )

        assertTrue(
            "APNG should be unsupported rather than flattened, got $result",
            result is ImageOptimizationResult.Unsupported
        )
        assertArrayEquals("Animated source must remain unchanged", sourceHash, sha256(inputFile))
        assertFileIsEmpty(outputFile)
    }

    @Test
    fun testFailedSameInputOutputUriLeavesSourceUnchanged() = runBlocking {
        val file = createPoorlyCompressedPngFile(8, 8)
        val uri = Uri.fromFile(file)
        val sourceHash = sha256(file)

        val result = optimizer.optimize(uri, uri, ImageProcessingOptions())

        assertTrue(
            "Same input/output URI should fail, got $result",
            result is ImageOptimizationResult.Failed
        )
        assertArrayEquals("Source must remain unchanged", sourceHash, sha256(file))
    }

    @Test
    fun testFailedNonEmptyOutputDestinationLeavesExistingDataUnchanged() = runBlocking {
        val inputFile = createPoorlyCompressedPngFile(16, 16)
        val outputFile = createTempFile("nonempty-", ".png")
        val existingBytes = byteArrayOf(1, 2, 3, 4)
        outputFile.writeBytes(existingBytes)
        val destinationHash = sha256(outputFile)
        val sourceHash = sha256(inputFile)

        val result = optimizer.optimize(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            ImageProcessingOptions()
        )

        assertTrue(
            "Non-empty output destination should fail, got $result",
            result is ImageOptimizationResult.Failed
        )
        assertArrayEquals(
            "Existing destination content must not be modified",
            destinationHash,
            sha256(outputFile)
        )
        assertArrayEquals("Source must remain unchanged", sourceHash, sha256(inputFile))
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
    }
}
