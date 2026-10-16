package com.example.theshrimpapp.features.imageoptimizer.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class BatchInput(val uri: Uri, val name: String?, val sizeBytes: Long? = null)

@Singleton
class BatchRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val dao: BatchDao,
    private val work: WorkManager
) {
    private val submission = Mutex()
    val execution = Mutex()
    val latest = dao.latest()
    fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid batch ID" }
        return File(context.filesDir, "batch-staging/$id").apply { mkdirs() }
    }
    fun candidate(id: String, position: Int) = File(directory(id), "candidate-$position")

    suspend fun restore() = withContext(Dispatchers.IO) {
        dao.unfinished().forEach {
            val info = work.getWorkInfosForUniqueWork("image-batch-${it.id}").get()
            if (it.state == "PROCESSING" && info.none { workInfo -> workInfo.state == WorkInfo.State.RUNNING })
                dao.status(it.id, "INTERRUPTED", "Waiting for Android to resume background processing.")
            enqueue(it.id)
        }
    }
    private suspend fun enqueue(id: String) = withContext(Dispatchers.IO) {
        work.enqueueUniqueWork("image-batch-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ImageBatchWorker>()
                .setInputData(workDataOf("jobId" to id))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                .addTag("image-batch").build()).result.get()
        Unit
    }

    private fun retain(uri: Uri, write: Boolean): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        val tree = if (uri.pathSegments.firstOrNull() == "tree")
            android.provider.DocumentsContract.buildTreeDocumentUri(uri.authority!!,
                android.provider.DocumentsContract.getTreeDocumentId(uri)) else uri
        try { context.contentResolver.takePersistableUriPermission(tree, flags) }
        catch (_: SecurityException) { return false }
        catch (_: UnsupportedOperationException) { return false }
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && (!write || it.isWritePermission)
        }
    }

    suspend fun submit(inputs: List<BatchInput>, output: Uri?, source: Uri?, options: ImageProcessingOptions,
        concurrency: Int, sourceFolderName: String? = null, outputFolderName: String? = null): String = withContext(Dispatchers.IO) {
        submission.withLock {
            require(inputs.isNotEmpty() && options.isValid())
            require(concurrency in 1..MAX_BATCH_CONCURRENCY)
            check(dao.unfinished().isEmpty()) { "An existing batch is still active." }
            val destination = output ?: source ?: throw IOException("Choose an output folder.")
            check(retain(destination, true)) { "This output provider did not retain read/write access. Select a SAF folder with persistent access." }
            val id = UUID.randomUUID().toString()
            val job = BatchJob(id = id, outputTree = output?.toString(), sourceTree = source?.toString(),
                sourceFolderName = sourceFolderName, outputFolderName = outputFolderName,
                concurrency = concurrency, mode = options.processingMode.name, dimension = options.maxDimension,
                keepOriginal = options.keepOriginalSize, format = options.outputFormat.name, quality = options.quality,
                background = options.backgroundColor.value.toLong(), preserveMetadata = options.preserveMetadata)
            var stored = false
            try {
                val items = inputs.mapIndexed { index, input ->
                    coroutineContext.ensureActive()
                    val origBytes = input.sizeBytes ?: 0L
                    if (retain(input.uri, false)) BatchItem(id, index, input.uri.toString(), input.name, originalBytes = origBytes)
                    else {
                        val staged = File(directory(id), "input-$index")
                        try {
                            context.contentResolver.openInputStream(input.uri)?.use { stream ->
                                staged.outputStream().use { out ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val count = stream.read(buffer)
                                        if (count < 0) break
                                        out.write(buffer, 0, count)
                                    }
                                }
                            } ?: throw IOException("Input cannot be opened; select it again.")
                            BatchItem(id, index, Uri.fromFile(staged).toString(), input.name, originalUri = input.uri.toString(), originalBytes = origBytes)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            staged.delete()
                            BatchItem(id, index, input.uri.toString(), input.name, state = "FAILED",
                                reason = "Input access failed: ${e.localizedMessage}", originalBytes = origBytes)
                        }
                    }
                }
                withContext(NonCancellable) {
                    dao.create(job, items)
                    stored = true
                    enqueue(id)
                }
                id
            } finally {
                if (!stored) directory(id).deleteRecursively()
            }
        }
    }
    suspend fun cancel(id: String) { dao.cancel(id); enqueue(id) }
    fun cleanup(id: String) { directory(id).deleteRecursively() }
    fun cleanupStaleCache() {
        val prefixes = listOf("reduce-size-", "post-opt-", "shrimp-input-", "shrimp-png-", "shrimp-jpeg-", "shrimp-webp-", "save-candidate-")
        val cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)
        context.cacheDir.listFiles()?.filter { file -> file.isFile && file.lastModified() < cutoff && prefixes.any(file.name::startsWith) }
            ?.forEach { it.delete() }
    }
}
