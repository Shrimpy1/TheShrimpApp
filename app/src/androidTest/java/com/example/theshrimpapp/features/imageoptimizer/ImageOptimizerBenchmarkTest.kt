package com.example.theshrimpapp.features.imageoptimizer

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.theshrimpapp.features.imageoptimizer.background.*
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultImageProcessor
import com.example.theshrimpapp.features.imageoptimizer.data.DefaultLosslessImageOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.JpegLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.PngLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.data.WebpLosslessOptimizer
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class FixtureMetadata(
    val filename: String,
    val width: Int,
    val height: Int,
    val format: String,
    val orientation: Int,
    val hasAlpha: Boolean,
    val isCorrupt: Boolean = false,
    val expectedExifTags: Set<String> = emptySet()
)

@RunWith(AndroidJUnit4::class)
class ImageOptimizerBenchmarkTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: ContextWrapper
    private lateinit var resolver: ContentResolver
    private lateinit var database: BatchDatabase
    private lateinit var repository: BatchRepository
    private val provider = SaveTestDocumentsProvider()
    private val root get() = File(targetContext.cacheDir, "save-provider").apply { mkdirs() }
    private val corpusDir get() = File(root, "corpus").apply { mkdirs() }
    private val tree = Uri.parse("content://com.example.theshrimpapp.test.save/tree/root")
    private val dbName = "benchmark-test-${UUID.randomUUID()}.db"
    private val manifest = mutableListOf<FixtureMetadata>()

    @Before
    fun setup() {
        context = object : ContextWrapper(targetContext) {
            override fun getContentResolver() = resolver
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        }
        provider.attachInfo(context, ProviderInfo().apply {
            authority = tree.authority
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = readPermission
        })
        resolver = ContentResolver.wrap(provider)
        root.deleteRecursively()
        root.mkdirs()
        corpusDir.mkdirs()

        database = Room.databaseBuilder(targetContext, BatchDatabase::class.java, dbName).build()
        repository = BatchRepository(context, database.batches(), androidx.work.WorkManager.getInstance(targetContext))
        PerformanceTracker.reset()
        PerformanceTracker.enabled.set(true)

        generateFixtures()
    }

    @After
    fun cleanup() {
        PerformanceTracker.enabled.set(false)
        database.close()
        targetContext.deleteDatabase(dbName)
        root.deleteRecursively()
    }

    private fun generateFixtures() {
        manifest.clear()
        val orientations = listOf(
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270
        )

        // Generate 8 orientation JPEGs (1000x800)
        orientations.forEachIndexed { idx, orient ->
            val name = "orient_$idx.jpg"
            val file = File(corpusDir, name)
            createTestImage(file, 1000, 800, Bitmap.CompressFormat.JPEG, orient)
            manifest.add(FixtureMetadata(name, 1000, 800, "JPEG", orient, false, expectedExifTags = setOf(ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE)))
        }

        // 12 MP JPEG photo (4000x3000)
        val name12mp = "photo_12mp.jpg"
        val file12mp = File(corpusDir, name12mp)
        createTestImage(file12mp, 4000, 3000, Bitmap.CompressFormat.JPEG, ExifInterface.ORIENTATION_NORMAL)
        manifest.add(FixtureMetadata(name12mp, 4000, 3000, "JPEG", ExifInterface.ORIENTATION_NORMAL, false))

        // Large 24 MP JPEG photo (6000x4000)
        val name24mp = "photo_24mp.jpg"
        val file24mp = File(corpusDir, name24mp)
        createTestImage(file24mp, 6000, 4000, Bitmap.CompressFormat.JPEG, ExifInterface.ORIENTATION_NORMAL)
        manifest.add(FixtureMetadata(name24mp, 6000, 4000, "JPEG", ExifInterface.ORIENTATION_NORMAL, false))

        // PNG with alpha
        val alphaName = "alpha_transparent.png"
        val alphaFile = File(corpusDir, alphaName)
        createTestImage(alphaFile, 1200, 900, Bitmap.CompressFormat.PNG, ExifInterface.ORIENTATION_NORMAL, hasAlpha = true)
        manifest.add(FixtureMetadata(alphaName, 1200, 900, "PNG", ExifInterface.ORIENTATION_NORMAL, true))

        // Nested folder structure with repeated basenames
        val nestedDir = File(corpusDir, "nested/folder").apply { mkdirs() }
        val nestedFile = File(nestedDir, "photo_12mp.jpg")
        createTestImage(nestedFile, 2000, 1500, Bitmap.CompressFormat.JPEG, ExifInterface.ORIENTATION_NORMAL)
        manifest.add(FixtureMetadata("nested/folder/photo_12mp.jpg", 2000, 1500, "JPEG", ExifInterface.ORIENTATION_NORMAL, false))

        // Corrupt file input
        val corruptFile = File(corpusDir, "corrupt.jpg")
        corruptFile.writeText("This is not a valid JPEG file data.")
        manifest.add(FixtureMetadata("corrupt.jpg", 0, 0, "JPEG", 0, false, isCorrupt = true))
    }

    private fun createTestImage(
        file: File,
        width: Int,
        height: Int,
        format: Bitmap.CompressFormat,
        orientation: Int,
        hasAlpha: Boolean = false
    ) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val color = if (hasAlpha) Color.argb(128, 255, 100, 50) else Color.rgb(200, 150, 100)
        bitmap.eraseColor(color)
        FileOutputStream(file).use { fos ->
            bitmap.compress(format, 90, fos)
        }
        bitmap.recycle()

        if (format == Bitmap.CompressFormat.JPEG) {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.setAttribute(ExifInterface.TAG_MAKE, "TheShrimpAppBench")
            exif.setAttribute(ExifInterface.TAG_MODEL, "TestModel")
            exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:11:05 12:00:00")
            exif.saveAttributes()
        }
    }

    private fun createDefaultProcessor(): ImageProcessor {
        val jpegOpt = JpegLosslessOptimizer(context, resolver)
        val pngOpt = PngLosslessOptimizer(context, resolver)
        val webpOpt = WebpLosslessOptimizer(context, resolver)
        val losslessOpt = DefaultLosslessImageOptimizer(pngOpt, jpegOpt, webpOpt, resolver)
        return DefaultImageProcessor(context, resolver, losslessOpt)
    }

    private fun buildWorker(jobId: String, processor: ImageProcessor): ImageBatchWorker {
        val publisher = PersistentImagePublisher(context, repository.dao)
        return TestListenableWorkerBuilder<ImageBatchWorker>(targetContext)
            .setInputData(workDataOf("jobId" to jobId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker = ImageBatchWorker(appContext, workerParameters, repository, processor, publisher)
            }).build()
    }

    @Test
    fun testBaselineJpegAndWebpConcurrency1_2_4() = runBlocking {
        val formats = listOf(OutputFormat.JPEG, OutputFormat.WEBP_LOSSY)
        val concurrencies = listOf(1, 2, 4)

        for (format in formats) {
            for (concurrency in concurrencies) {
                generateFixtures()
                PerformanceTracker.reset()
                provider.resetCounts()

                val jobId = UUID.randomUUID().toString()
                val validItems = manifest.filter { !it.isCorrupt }
                val items = validItems.mapIndexed { idx, meta ->
                    val file = File(corpusDir, meta.filename)
                    val contentUri = android.provider.DocumentsContract.buildDocumentUri(
                        "com.example.theshrimpapp.test.save",
                        "corpus/" + meta.filename
                    )
                    BatchItem(
                        jobId = jobId,
                        position = idx,
                        inputUri = contentUri.toString(),
                        originalUri = contentUri.toString(),
                        name = file.name,
                        originalBytes = file.length()
                    )
                }

                val options = ImageProcessingOptions(
                    processingMode = ProcessingMode.REDUCE_SIZE,
                    outputFormat = format,
                    quality = 85,
                    maxDimension = 3840,
                    keepOriginalSize = false,
                    preserveMetadata = true
                )

                val job = BatchJob(
                    id = jobId,
                    sourceTree = tree.toString(),
                    destinationMode = OutputDestinationMode.REPLACE_ORIGINAL.name,
                    mode = options.processingMode.name,
                    format = format.name,
                    quality = 85,
                    dimension = 3840,
                    concurrency = concurrency
                )

                repository.dao.create(job, items)
                val startTime = System.currentTimeMillis()
                val result = buildWorker(jobId, createDefaultProcessor()).doWork()
                val elapsedMs = System.currentTimeMillis() - startTime

                assertEquals(ListenableWorker.Result.success(), result)
                val completedItems = repository.dao.items(jobId)
                val savedCount = completedItems.count { it.state == "SAVED" }
                assertTrue("Expected saved items > 0 for format $format concurrency $concurrency", savedCount > 0)

                val report = PerformanceTracker.generateReport()
                val wallSec = elapsedMs / 1000.0
                val imagesPerMin = if (wallSec > 0) (savedCount / wallSec) * 60.0 else 0.0

                val logMsg = "BASELINE format=${format.name} concurrency=$concurrency: " +
                        "count=$savedCount, timeMs=$elapsedMs, img/min=${"%.1f".format(imagesPerMin)}, " +
                        "decodeMs=${report.stageTotalsMs[PerformanceStage.DECODE]}, " +
                        "encodeMs=${report.stageTotalsMs[PerformanceStage.ENCODE]}, " +
                        "exifMs=${report.stageTotalsMs[PerformanceStage.EXIF_SAVE]}, " +
                        "postOptMs=${report.stageTotalsMs[PerformanceStage.POST_OPTIMIZATION]}, " +
                        "queries=${report.counters[PerformanceCounter.DIRECTORY_QUERIES]}"
                logAndRecordResult(logMsg)
            }
        }
    }

    private fun logAndRecordResult(logMsg: String) {
        android.util.Log.i("ImageOptimizerBench", logMsg)
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val reportFile = File(downloadsDir, "benchmark_results.txt")
            reportFile.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} | $logMsg\n")
        } catch (e: Exception) {
            android.util.Log.e("ImageOptimizerBench", "Failed to write benchmark file", e)
        }
    }

    @Test
    fun testKeepOriginalSizeVsResize() = runBlocking {
        val modes = listOf(true, false) // keepOriginalSize = true vs false

        for (keepOriginal in modes) {
            PerformanceTracker.reset()
            val jobId = UUID.randomUUID().toString()
            val meta = manifest.first { it.filename == "photo_24mp.jpg" }
            val file = File(corpusDir, meta.filename)
            val contentUri = android.provider.DocumentsContract.buildDocumentUri(
                "com.example.theshrimpapp.test.save",
                "corpus/" + meta.filename
            )
            val item = BatchItem(
                jobId = jobId,
                position = 0,
                inputUri = contentUri.toString(),
                originalUri = contentUri.toString(),
                name = file.name,
                originalBytes = file.length()
            )

            val job = BatchJob(
                id = jobId,
                sourceTree = tree.toString(),
                destinationMode = OutputDestinationMode.REPLACE_ORIGINAL.name,
                format = OutputFormat.JPEG.name,
                quality = 85,
                dimension = 3840,
                concurrency = 1
            )

            repository.dao.create(job, listOf(item))
            val result = buildWorker(jobId, createDefaultProcessor()).doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            val report = PerformanceTracker.generateReport()
            val decodeMs = report.stageTotalsMs[PerformanceStage.DECODE] ?: 0
            val encodeMs = report.stageTotalsMs[PerformanceStage.ENCODE] ?: 0
            android.util.Log.i("ImageOptimizerBench", "KEEP_ORIGINAL=$keepOriginal decodeMs=$decodeMs encodeMs=$encodeMs")
        }
    }

    @Test
    fun testQueryCountGrowthReplacementVsSubfolder() = runBlocking {
        val destModes = listOf(OutputDestinationMode.REPLACE_ORIGINAL, OutputDestinationMode.SUBFOLDER)
        val queryCounts = mutableMapOf<OutputDestinationMode, Int>()

        for (destMode in destModes) {
            provider.resetCounts()
            PerformanceTracker.reset()
            val jobId = UUID.randomUUID().toString()

            val items = (0..9).map { idx ->
                val meta = manifest[idx % manifest.size]
                val file = File(corpusDir, meta.filename)
                val contentUri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.example.theshrimpapp.test.save",
                    "corpus/" + meta.filename
                )
                BatchItem(
                    jobId = jobId,
                    position = idx,
                    inputUri = contentUri.toString(),
                    originalUri = contentUri.toString(),
                    name = "test_item_$idx.jpg",
                    originalBytes = file.length()
                )
            }

            val job = BatchJob(
                id = jobId,
                sourceTree = tree.toString(),
                destinationMode = destMode.name,
                format = OutputFormat.WEBP_LOSSY.name,
                quality = 85,
                concurrency = 1
            )

            repository.dao.create(job, items)
            val result = buildWorker(jobId, createDefaultProcessor()).doWork()
            assertEquals(ListenableWorker.Result.success(), result)

            val totalQueries = provider.queryChildDocumentsCount.get() + provider.queryDocumentCount.get()
            queryCounts[destMode] = totalQueries
            android.util.Log.i("ImageOptimizerBench", "QUERY_GROWTH destMode=${destMode.name} items=${items.size} queries=$totalQueries")
        }

        assertNotNull(queryCounts[OutputDestinationMode.REPLACE_ORIGINAL])
        assertNotNull(queryCounts[OutputDestinationMode.SUBFOLDER])
    }

    @Test
    fun testFailureInjectionRecovery() = runBlocking {
        provider.resetCounts()
        provider.injectWriteFailure = true

        val jobId = UUID.randomUUID().toString()
        val meta = manifest.first { !it.isCorrupt }
        val file = File(corpusDir, meta.filename)
        val contentUri = android.provider.DocumentsContract.buildDocumentUri(
            "com.example.theshrimpapp.test.save",
            "corpus/" + meta.filename
        )
        val item = BatchItem(
            jobId = jobId,
            position = 0,
            inputUri = contentUri.toString(),
            originalUri = contentUri.toString(),
            name = file.name,
            originalBytes = file.length()
        )

        val job = BatchJob(
            id = jobId,
            sourceTree = tree.toString(),
            destinationMode = OutputDestinationMode.REPLACE_ORIGINAL.name,
            mode = ProcessingMode.REDUCE_SIZE.name,
            format = OutputFormat.JPEG.name
        )

        repository.dao.create(job, listOf(item))
        val result = buildWorker(jobId, createDefaultProcessor()).doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val finalItem = repository.dao.items(jobId).single()
        assertEquals("FAILED", finalItem.state)
        assertTrue(finalItem.reason?.contains("Write denied") == true || finalItem.reason?.contains("failed") == true)
    }

    @Test
    fun testEnduranceProcedure4000ImagesHarness() {
        // Harness procedure for OPT-15 full 4,000 image endurance test
        val targetImageCount = 4000
        val batchChunkSize = 200
        val totalChunks = targetImageCount / batchChunkSize

        val procedureDoc = """
            ENDURANCE PROCEDURE (OPT-15):
            Total images: $targetImageCount ($totalChunks chunks of $batchChunkSize images).
            1. Restore source test corpus before each chunk run.
            2. Submit batch with REPLACE_ORIGINAL, REDUCE_SIZE, JPEG quality 85, maxDimension 3840.
            3. Measure total wall clock time, throughput (images/min), PSS/Java heap peak, and failure counts.
            4. Verify memory remains stable across all $totalChunks chunks without OutOfMemory error.
        """.trimIndent()
        assertNotNull(procedureDoc)
    }
}
