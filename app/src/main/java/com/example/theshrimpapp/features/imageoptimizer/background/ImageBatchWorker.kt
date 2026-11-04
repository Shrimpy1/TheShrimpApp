package com.example.theshrimpapp.features.imageoptimizer.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.theshrimpapp.MainActivity
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessingResult
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageProcessor
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceCounter
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceStage
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceTracker
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import com.example.theshrimpapp.features.imageoptimizer.domain.SequentialBatchProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltWorker
class ImageBatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val repository: BatchRepository,
    private val processor: ImageProcessor,
    private val publisher: PersistentImagePublisher
) : CoroutineWorker(context, parameters) {
    private val jobId get() = inputData.getString("jobId") ?: ""
    private val dao get() = repository.dao

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        repository.execution.withLock {
            val job = dao.job(jobId) ?: return@withLock Result.failure()
            if (job.state in listOf(
                    "COMPLETED",
                    "CANCELLED",
                    "FAILED"
                )
            ) return@withLock Result.success()
            try {
                repository.cleanupStaleCache()
                if (job.cancelRequested) {
                    // Queued cancellation must not depend on permission to start a foreground service.
                    withContext(NonCancellable) {
                        dao.items(jobId).filter { it.state == "PREPARED" }.forEach {
                            publisher.recover(job, it, repository.candidate(jobId, it.position))
                        }
                        dao.cancelRemaining(jobId)
                        dao.status(jobId, "CANCELLED")
                        repository.cleanup(jobId)
                        showCompletionNotification("CANCELLED")
                    }
                    return@withLock Result.success()
                }
                safeSetForeground(notification(dao.items(jobId)))
                dao.status(jobId, "PROCESSING")
                val queue = dao.items(jobId).filterNot { it.terminal }
                SequentialBatchProcessor().run(
                    queue,
                    concurrency = job.concurrency,
                    shouldStop = { dao.job(jobId)?.cancelRequested != false },
                    process = { processItem(job, it) },
                    failure = { throw it },
                    onProgress = {
                        PerformanceTracker.trackStage(PerformanceStage.PROGRESS_CALLBACK) {
                            PerformanceTracker.incrementCounter(PerformanceCounter.PROGRESS_CALLBACKS)
                            val items = dao.items(jobId)
                            val completed = items.count { it.terminal && it.state != "CANCELLED" }
                            setProgress(workDataOf("completed" to completed, "total" to items.size))
                            safeSetForeground(notification(items))
                        }
                    })
                withContext(NonCancellable) {
                    val finalStatus = if (dao.job(jobId)?.cancelRequested == true) {
                        dao.cancelRemaining(jobId)
                        dao.status(jobId, "CANCELLED")
                        "CANCELLED"
                    } else {
                        dao.status(jobId, "COMPLETED")
                        "COMPLETED"
                    }
                    repository.cleanup(jobId)
                    showCompletionNotification(finalStatus)
                }
                Result.success()
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    dao.status(
                        jobId,
                        "INTERRUPTED",
                        "Android interrupted execution; work will resume."
                    )
                }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    dao.status(
                        jobId,
                        "INTERRUPTED",
                        e.localizedMessage ?: "Background execution interrupted."
                    )
                }
                Result.retry()
            }
        }
    }

    private suspend fun processItem(job: BatchJob, initial: BatchItem): BatchItem {
        var item = initial
        val candidate = repository.candidate(job.id, item.position)
        try {
            if (item.state != "PREPARED") {
                dao.updateItem(item.copy(state = "RUNNING"))
                // An interrupted encoding is disposable; no document has been exposed yet.
                candidate.delete()
                when (val result = processor.process(
                    item.inputUri.toUri(),
                    Uri.fromFile(candidate),
                    job.options()
                )) {
                    is ImageProcessingResult.Success -> {
                        check(candidate.length() == result.outputSizeBytes && result.outputSizeBytes > 0)
                        check(result.mode != ProcessingMode.LOSSLESS_OPTIMIZE || result.outputSizeBytes < result.originalSizeBytes)
                        item = item.prepared(result)
                        withContext(NonCancellable) { dao.updateItem(item) }
                    }

                    is ImageProcessingResult.Unchanged -> return item.copy(
                        state = "UNCHANGED",
                        reason = result.reason
                    )
                        .also { withContext(NonCancellable) { dao.updateItem(it) }; candidate.delete() }

                    is ImageProcessingResult.Failed -> return item.copy(
                        state = "FAILED",
                        reason = result.error
                    )
                        .also { withContext(NonCancellable) { dao.updateItem(it) }; candidate.delete() }
                }
            }
            val saved = publisher.publish(job, item, candidate)
            withContext(NonCancellable) { dao.updateItem(saved) }
            candidate.delete()
            return saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = item.copy(
                state = "FAILED",
                reason = e.localizedMessage ?: "Input is inaccessible or processing failed."
            )
            withContext(NonCancellable) { dao.updateItem(failed) }
            candidate.delete()
            return failed
        }
    }

    private suspend fun safeSetForeground(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            android.util.Log.w("ImageBatchWorker", "Unable to set foreground notification", e)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notification(dao.items(jobId))
    private fun notification(items: List<BatchItem>): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "image-batches",
                "Image processing",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val cancel = PendingIntent.getBroadcast(
            applicationContext, jobId.hashCode(),
            Intent(applicationContext, CancelBatchReceiver::class.java).putExtra("jobId", jobId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "image_optimizer_processing")
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val completed = items.count { it.terminal && it.state != "CANCELLED" }
        return ForegroundInfo(
            (jobId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1),
            NotificationCompat.Builder(applicationContext, "image-batches")
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Processing images")
                .setContentText("$completed of ${items.size} processed")
                .setProgress(items.size, completed, false).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel batch", cancel)
                .build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private suspend fun showCompletionNotification(status: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                "image-batch-completions",
                "Image processing completions",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val items = dao.items(jobId)
        val total = items.size
        val saved = items.count { it.state == "SAVED" }
        val unchanged = items.count { it.state == "UNCHANGED" }
        val failed = items.count { it.state == "FAILED" }
        val cancelled = items.count { it.state == "CANCELLED" }

        val (title, text) = when (status) {
            "CANCELLED" -> "Batch processing cancelled" to "Processed $saved of $total image(s), $cancelled cancelled."
            "FAILED" -> "Batch processing failed" to "Failed to complete batch processing."
            else -> if (failed == 0) "Batch processing complete" to "Successfully processed $total image(s)."
            else "Batch processing complete" to "$saved saved, $unchanged unchanged, $failed failed."
        }

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "image_optimizer_result")
        }

        val open = PendingIntent.getActivity(
            applicationContext,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, "image-batch-completions")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        val baseId = (jobId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        manager.notify(baseId + 100000, notification)
    }
}
