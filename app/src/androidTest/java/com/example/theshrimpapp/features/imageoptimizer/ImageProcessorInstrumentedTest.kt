package com.example.theshrimpapp.features.imageoptimizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultImageProcessor
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultLosslessImageOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.JpegLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.PngLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.WebpLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingOptions
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingResult
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ImageProcessorInstrumentedTest {

    private lateinit var context: Context
    private lateinit var processor: DefaultImageProcessor
    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val resolver = context.contentResolver
        val losslessOptimizer = DefaultLosslessImageOptimizer(
            pngOptimizer = PngLosslessOptimizer(context, resolver),
            jpegOptimizer = JpegLosslessOptimizer(context, resolver),
            webpOptimizer = WebpLosslessOptimizer(context, resolver),
            contentResolver = resolver
        )
        processor = DefaultImageProcessor(
            context = context,
            contentResolver = resolver,
            losslessImageOptimizer = losslessOptimizer
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

    private fun createTestBitmapFile(width: Int, height: Int, hasAlpha: Boolean = false): File {
        val file = createTempFile("test-", ".png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (hasAlpha) {
            // Draw transparent corners, opaque center
            for (x in 0 until width) {
                for (y in 0 until height) {
                    if (x in width / 4 until (3 * width) / 4 && y in height / 4 until (3 * height) / 4) {
                        bitmap.setPixel(x, y, Color.RED)
                    } else {
                        bitmap.setPixel(x, y, Color.TRANSPARENT)
                    }
                }
            }
        } else {
            bitmap.eraseColor(Color.BLUE)
        }
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        bitmap.recycle()
        return file
    }

    @Test
    fun testKeepOriginalSizeStillConvertsFormat() = runBlocking {
        val input = createTestBitmapFile(800, 600, false)
        val output = createTempFile("original-size-", ".webp")
        val result = processor.process(Uri.fromFile(input), Uri.fromFile(output),
            ImageProcessingOptions(
                processingMode = ProcessingMode.REDUCE_SIZE,
                maxDimension = 100,
                keepOriginalSize = true,
                outputFormat = OutputFormat.WEBP_LOSSY
            ))
        assertTrue(result is ImageProcessingResult.Success)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(output.path, bounds)
        assertEquals(800, bounds.outWidth)
        assertEquals(600, bounds.outHeight)
        assertEquals("image/webp", bounds.outMimeType)
    }

    @Test
    fun testReduceSizeResizingAndAspectPreservation() = runBlocking {
        val inputFile = createTestBitmapFile(800, 600, false)
        val outputFile = createTempFile("output-", ".webp")

        val options = ImageProcessingOptions(
            processingMode = ProcessingMode.REDUCE_SIZE,
            maxDimension = 400,
            outputFormat = OutputFormat.WEBP_LOSSY,
            quality = 80
        )

        val result = processor.process(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            options
        )

        assertTrue("Result should be Success", result is ImageProcessingResult.Success)
        val success = result as ImageProcessingResult.Success
        assertEquals(400, success.width)
        assertEquals(300, success.height) // 800x600 scaled down to max 400 maintaining 4:3 aspect ratio
        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }

    @Test
    fun testReduceSizeJpegTransparencyBackground() = runBlocking {
        val inputFile = createTestBitmapFile(200, 200, true)
        val outputFile = createTempFile("output-", ".jpg")

        val options = ImageProcessingOptions(
            processingMode = ProcessingMode.REDUCE_SIZE,
            maxDimension = 200,
            outputFormat = OutputFormat.JPEG,
            quality = 85,
            backgroundColor = androidx.compose.ui.graphics.Color.White
        )

        val result = processor.process(
            Uri.fromFile(inputFile),
            Uri.fromFile(outputFile),
            options
        )

        assertTrue("Result should be Success for JPEG with transparency", result is ImageProcessingResult.Success)
        assertTrue(outputFile.exists() && outputFile.length() > 0)
    }

    @Test
    fun testSameInputAndOutputUriFails() = runBlocking {
        val file = createTestBitmapFile(100, 100, false)
        val uri = Uri.fromFile(file)

        val options = ImageProcessingOptions(
            processingMode = ProcessingMode.REDUCE_SIZE,
            maxDimension = 100,
            outputFormat = OutputFormat.PNG
        )

        val result = processor.process(uri, uri, options)
        assertTrue("Result should be Failed when input == output", result is ImageProcessingResult.Failed)
    }

    @Test
    fun testCorruptedInputFailsGracefully() = runBlocking {
        val corruptedFile = createTempFile("corrupt-", ".png")
        corruptedFile.writeText("Not an image")
        val outputFile = createTempFile("output-", ".png")

        val options = ImageProcessingOptions(
            processingMode = ProcessingMode.REDUCE_SIZE,
            maxDimension = 100,
            outputFormat = OutputFormat.PNG
        )

        val result = processor.process(
            Uri.fromFile(corruptedFile),
            Uri.fromFile(outputFile),
            options
        )

        assertTrue("Result should be Failed on corrupted input", result is ImageProcessingResult.Failed)
    }
}
