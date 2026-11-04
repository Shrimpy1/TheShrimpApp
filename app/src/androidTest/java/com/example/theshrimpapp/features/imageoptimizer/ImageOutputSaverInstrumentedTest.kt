package com.example.theshrimpapp.features.imageoptimizer

import android.database.Cursor
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.theshrimpapp.features.imageoptimizer.data.ImageOutputSaver
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.example.theshrimpapp.features.imageoptimizer.ui.ImageOptimizerViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

@RunWith(AndroidJUnit4::class)
class ImageOutputSaverInstrumentedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: ContextWrapper
    private lateinit var resolver: ContentResolver
    private val root = File(targetContext.cacheDir, "save-provider")
    private val tree = DocumentsContract.buildTreeDocumentUri("com.example.theshrimpapp.test.save", "root")
    private lateinit var input: File
    private val payload = byteArrayOf(1, 2, 3, 4)

    @Before fun reset() {
        context = object : ContextWrapper(targetContext) {
            override fun getContentResolver() = resolver
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) =
                if (uri.authority == tree.authority) PackageManager.PERMISSION_GRANTED
                else super.checkCallingOrSelfUriPermission(uri, modeFlags)
        }
        val provider = SaveTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = tree.authority
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        resolver = ContentResolver.wrap(provider)
        root.deleteRecursively()
        root.mkdirs()
        input = File(root, "source.png").apply { writeBytes(ByteArray(20) { 9 }) }
    }

    private fun saver(unchanged: Boolean = false, originalSize: Long = 20) = ImageOutputSaver(context, context.contentResolver,
        object : ImageProcessor {
            override suspend fun process(inputUri: Uri, outputUri: Uri, options: ImageProcessingOptions): ImageProcessingResult {
                if (unchanged) return ImageProcessingResult.Unchanged("Already optimized")
                File(requireNotNull(outputUri.path)).writeBytes(payload)
                return ImageProcessingResult.Success(originalSize, 4, OutputFormat.WEBP_LOSSLESS, 1, 1, 16, 80f, options.processingMode)
            }
        })

    @Test fun savesAndResolvesCollisionWithoutChangingOriginal() = runBlocking {
        File(root, "source_optimized.webp").writeBytes(byteArrayOf(7))
        val result = saver().save(Uri.fromFile(input), input.name, tree, null, ImageProcessingOptions())
        assertTrue(result.toString(), result is SaveImageResult.Saved)
        result as SaveImageResult.Saved
        assertEquals("source_optimized_1.webp", result.name)
        assertEquals(4L, result.details.outputSizeBytes)
        assertArrayEquals(payload, File(root, result.name).readBytes())
        assertArrayEquals(byteArrayOf(7), File(root, "source_optimized.webp").readBytes())
        assertArrayEquals(ByteArray(20) { 9 }, input.readBytes())
    }

    @Test fun defaultsToOptimizedSubfolder() = runBlocking {
        val result = saver().save(Uri.fromFile(input), input.name, null, tree, ImageProcessingOptions())
        assertTrue(result.toString(), result is SaveImageResult.Saved)
        assertArrayEquals(payload, File(root, "Optimized/source_optimized.webp").readBytes())
    }

    @Test fun unchangedDoesNotCreateOutputOrFolder() = runBlocking {
        val result = saver(true).save(Uri.fromFile(input), input.name, null, tree, ImageProcessingOptions())
        assertTrue(result is SaveImageResult.Unchanged)
        assertEquals(listOf("source.png"), root.listFiles()!!.map { it.name })
    }

    @Test fun failedWriteCleansUpAndKeepsSource() = runBlocking {
        File(root, "deny-write").createNewFile()
        val result = saver().save(Uri.fromFile(input), input.name, tree, null, ImageProcessingOptions())
        assertTrue(result is SaveImageResult.Failed)
        assertFalse(File(root, "source_optimized.webp").exists())
        assertArrayEquals(ByteArray(20) { 9 }, input.readBytes())
    }

    @Test fun detectsCorruptSavedContentAndRemovesIt() = runBlocking {
        File(root, "corrupt-read").createNewFile()
        val result = saver().save(Uri.fromFile(input), input.name, tree, null, ImageProcessingOptions())
        assertTrue(result is SaveImageResult.Failed)
        assertFalse(File(root, "source_optimized.webp").exists())
        assertArrayEquals(ByteArray(20) { 9 }, input.readBytes())
    }

    @Test fun rejectsProviderReturningOriginalDocumentWithDifferentUri() = runBlocking {
        File(root, "return-source").createNewFile()
        val source = DocumentsContract.buildDocumentUri("com.example.theshrimpapp.test.save", "source.png")
        val result = saver().save(source, input.name, tree, null, ImageProcessingOptions())
        assertTrue(result is SaveImageResult.Failed)
        assertArrayEquals(ByteArray(20) { 9 }, input.readBytes())
    }

    @Test fun rejectsNonSmallerLosslessCandidateBeforeCreatingOutput() = runBlocking {
        val result = saver(originalSize = 4).save(Uri.fromFile(input), input.name, null, tree, ImageProcessingOptions())
        assertTrue(result is SaveImageResult.Failed)
        assertFalse(File(root, "Optimized").exists())
        assertEquals(listOf("source.png"), root.listFiles()!!.map { it.name })
    }

}
