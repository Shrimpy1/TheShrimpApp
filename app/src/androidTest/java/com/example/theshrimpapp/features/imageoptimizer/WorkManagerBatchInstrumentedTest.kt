package com.example.theshrimpapp.features.imageoptimizer

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.theshrimpapp.MainActivity
import com.example.theshrimpapp.TheShrimpApp
import com.example.theshrimpapp.features.imageoptimizer.background.BatchItem
import com.example.theshrimpapp.features.imageoptimizer.background.BatchJob
import com.example.theshrimpapp.features.imageoptimizer.background.ImageBatchWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class WorkManagerBatchInstrumentedTest {
    @Test
    fun realSchedulerCreatesHiltWorkerAndPersistsCompletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val repository = (context.applicationContext as TheShrimpApp).batches.get()
        val id = UUID.randomUUID().toString()
        val input = File(context.cacheDir, "work-test-$id").apply { writeText("Unsupported image") }
        try {
            repository.dao.create(
                BatchJob(id, outputTree = "content://inaccessible/tree/test"),
                listOf(BatchItem(id, 0, Uri.fromFile(input).toString(), "test.bin"))
            )
            val request =
                OneTimeWorkRequestBuilder<ImageBatchWorker>().setInputData(workDataOf("jobId" to id))
                    .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork(
                "image-batch-$id",
                ExistingWorkPolicy.KEEP,
                request
            ).result.get()
            val final = withTimeout(30_000.milliseconds) {
                manager.getWorkInfoByIdFlow(request.id)
                    .first { it != null && it.state.isFinished }!!
            }
            assertEquals(WorkInfo.State.SUCCEEDED, final.state)
            assertEquals("COMPLETED", repository.dao.job(id)!!.state)
            assertEquals("UNCHANGED", repository.dao.items(id).single().state)
            assertTrue(input.exists())
        } finally {
            repository.dao.deleteJob(id)
            repository.cleanup(id)
            input.delete()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
