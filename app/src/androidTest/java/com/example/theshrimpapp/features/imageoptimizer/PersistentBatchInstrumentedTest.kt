package com.example.theshrimpapp.features.imageoptimizer

import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.theshrimpapp.features.imageoptimizer.background.*
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PersistentBatchInstrumentedTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: ContextWrapper
    private lateinit var resolver: ContentResolver
    private lateinit var database: BatchDatabase
    private lateinit var repository: BatchRepository
    private val dbName = "batch-test-${UUID.randomUUID()}.db"
    private val root get() = File(target.cacheDir, "save-provider")
    private val tree = Uri.parse("content://com.example.theshrimpapp.test.save/tree/root")
    private val id = UUID.randomUUID().toString()
    private val calls = AtomicInteger()
    private val payload = byteArrayOf(1, 2, 3, 4)

    @Before fun setup() {
        context = object : ContextWrapper(target) {
            override fun getContentResolver() = resolver
            override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int) = PackageManager.PERMISSION_GRANTED
        }
        val provider = SaveTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = tree.authority; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        })
        resolver = ContentResolver.wrap(provider)
        root.deleteRecursively(); root.mkdirs()
        database = Room.databaseBuilder(target, BatchDatabase::class.java, dbName).build()
        repository = BatchRepository(context, database.batches(), WorkManager.getInstance(target))
    }
    @After fun cleanup() {
        database.close(); target.deleteDatabase(dbName)
        repository.cleanup(id); root.deleteRecursively()
    }

    private val processor = object : ImageProcessor {
        override suspend fun process(inputUri: Uri, outputUri: Uri, options: ImageProcessingOptions): ImageProcessingResult {
            calls.incrementAndGet()
            if (inputUri.lastPathSegment == "bad") return ImageProcessingResult.Failed("Bad input")
            File(requireNotNull(outputUri.path)).writeBytes(payload)
            return ImageProcessingResult.Success(20, 4, OutputFormat.WEBP_LOSSLESS, 1, 1, 16, 80f, options.processingMode)
        }
    }
    private fun worker(process: ImageProcessor = processor): ImageBatchWorker =
        TestListenableWorkerBuilder<ImageBatchWorker>(target)
            .setInputData(workDataOf("jobId" to id))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    ImageBatchWorker(appContext, workerParameters, repository, process, PersistentImagePublisher(context, repository.dao))
            }).build()

    @Test fun retriesSkipSavedItemsAndRestoreOrderedProgressFromDisk() = runBlocking {
        val job = BatchJob(id, outputTree = tree.toString())
        repository.dao.create(job, listOf(BatchItem(id, 0, "file:///first", "first.png"),
            BatchItem(id, 1, "file:///bad", "bad.png"), BatchItem(id, 2, "file:///last", "last.png")))
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(3, calls.get())
        val outputs = root.listFiles()!!.associate { it.name to it.readBytes().toList() }
        database.close()
        database = Room.databaseBuilder(target, BatchDatabase::class.java, dbName).build()
        repository = BatchRepository(context, database.batches(), WorkManager.getInstance(target))
        val restored = repository.latest.first()!!
        assertEquals("COMPLETED", restored.job.state)
        assertEquals(listOf("SAVED", "FAILED", "SAVED"), restored.items.sortedBy { it.position }.map { it.state })
        repository.dao.status(id, "INTERRUPTED")
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(3, calls.get())
        assertEquals(outputs, root.listFiles()!!.associate { it.name to it.readBytes().toList() })
    }

    @Test fun preparedOutputIsRecoveredWithoutReencodingOrDuplicateFile() = runBlocking {
        val job = BatchJob(id, outputTree = tree.toString())
        val item = BatchItem(id, 0, "file:///source", "source.png", state = "PREPARED",
            originalBytes = 20, outputBytes = 4, width = 1, height = 1, format = OutputFormat.WEBP_LOSSLESS.name)
        repository.dao.create(job, listOf(item))
        repository.candidate(id, 0).writeBytes(payload)
        val publisher = PersistentImagePublisher(context, repository.dao)
        publisher.publish(job, item, repository.candidate(id, 0))
        // Simulate death between provider validation and an external observer recording completion.
        repository.dao.updateItem(item)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, calls.get())
        assertEquals(1, root.listFiles()!!.size)
        assertEquals("SAVED", repository.dao.items(id).single().state)
    }

    @Test fun cancellationAfterRestartStillRecordsPreviouslyWrittenOutput() = runBlocking {
        val job = BatchJob(id, outputTree = tree.toString())
        val item = BatchItem(id, 0, "file:///source", "source.png", state = "PREPARED",
            originalBytes = 20, outputBytes = 4, width = 1, height = 1, format = OutputFormat.WEBP_LOSSLESS.name)
        repository.dao.create(job, listOf(item, BatchItem(id, 1, "file:///next", "next.png")))
        repository.candidate(id, 0).writeBytes(payload)
        PersistentImagePublisher(context, repository.dao).publish(job, item, repository.candidate(id, 0))
        repository.dao.updateItem(item)
        repository.dao.cancel(id)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals(0, calls.get())
        assertEquals(listOf("SAVED", "CANCELLED"), repository.dao.items(id).map { it.state })
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun cancellationPersistsFinalStatesAndDoesNotClaimAnotherItem() = runBlocking {
        repository.dao.create(BatchJob(id, outputTree = tree.toString(), concurrency = 2),
            (0..5).map { BatchItem(id, it, "file:///input-$it", "input-$it.png") })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gated = object : ImageProcessor {
            override suspend fun process(inputUri: Uri, outputUri: Uri, options: ImageProcessingOptions): ImageProcessingResult {
                if (calls.incrementAndGet() == 2) entered.complete(Unit)
                release.await()
                File(requireNotNull(outputUri.path)).writeBytes(payload)
                return ImageProcessingResult.Success(20, 4, OutputFormat.WEBP_LOSSLESS, 1, 1, 16, 80f, options.processingMode)
            }
        }
        val task = async { worker(gated).doWork() }
        withTimeout(5000) { entered.await() }
        repository.dao.cancel(id)
        release.complete(Unit)
        assertEquals(ListenableWorker.Result.success(), task.await())
        val items = repository.dao.items(id)
        assertEquals(2, calls.get())
        assertEquals(2, items.count { it.state == "SAVED" })
        assertEquals(4, items.count { it.state == "CANCELLED" })
        assertEquals("CANCELLED", repository.dao.job(id)!!.state)
    }

    @Test fun systemInterruptionRetainsPendingWorkAndIsNotUserCancellation() = runBlocking {
        repository.dao.create(BatchJob(id, outputTree = tree.toString()), listOf(BatchItem(id, 0, "file:///source", "source.png")))
        val stopped = object : ImageProcessor {
            override suspend fun process(inputUri: Uri, outputUri: Uri, options: ImageProcessingOptions): ImageProcessingResult =
                throw CancellationException("Stopped by Android")
        }
        try { worker(stopped).doWork(); fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        assertFalse(repository.dao.job(id)!!.cancelRequested)
        assertEquals("INTERRUPTED", repository.dao.job(id)!!.state)
        assertEquals(ListenableWorker.Result.success(), worker().doWork())
        assertEquals("SAVED", repository.dao.items(id).single().state)
    }

    @Test fun replaceOriginalInNestedFolderPutsNewFileInNestedFolder() = runBlocking {
        val nestedFolder = File(root, "subfolder").apply { mkdirs() }
        val nestedInput = File(nestedFolder, "photo.png").apply { writeBytes(ByteArray(20) { 9 }) }
        val nestedFolderUri = "content://com.example.theshrimpapp.test.save/tree/root/document/subfolder"
        val nestedFileUri = "content://com.example.theshrimpapp.test.save/tree/root/document/subfolder/photo.png"

        val job = BatchJob(
            id,
            sourceTree = tree.toString(),
            destinationMode = OutputDestinationMode.REPLACE_ORIGINAL.name,
            format = OutputFormat.WEBP_LOSSLESS.name
        )
        val item = BatchItem(
            id,
            0,
            nestedFileUri,
            "photo.png",
            sourceTreeUri = nestedFolderUri,
            originalBytes = 20
        )
        repository.dao.create(job, listOf(item))

        assertEquals(ListenableWorker.Result.success(), worker().doWork())

        assertFalse("Original png in nested folder should be removed when converted to webp", nestedInput.exists())
        val outputInNested = File(nestedFolder, "photo.webp")
        assertTrue("Output webp should be created in nested folder", outputInNested.exists())
        assertArrayEquals(payload, outputInNested.readBytes())

        val rootOutput = File(root, "photo.webp")
        assertFalse("Output webp must not be placed in parent root folder", rootOutput.exists())
    }
}
