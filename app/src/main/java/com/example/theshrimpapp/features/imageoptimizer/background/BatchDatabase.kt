package com.example.theshrimpapp.features.imageoptimizer.background

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import androidx.compose.ui.graphics.Color

@Entity(tableName = "batch_jobs")
data class BatchJob(
    @PrimaryKey val id: String,
    val createdAt: Long = System.currentTimeMillis(),
    val state: String = "QUEUED",
    val cancelRequested: Boolean = false,
    val concurrency: Int = DEFAULT_BATCH_CONCURRENCY,
    val outputTree: String? = null,
    val sourceTree: String? = null,
    val sourceFolderName: String? = null,
    val outputFolderName: String? = null,
    val mode: String = ProcessingMode.LOSSLESS_OPTIMIZE.name,
    val dimension: Int = 1920,
    val keepOriginal: Boolean = false,
    val format: String = OutputFormat.WEBP_LOSSY.name,
    val quality: Int = 85,
    val background: Long = Color.White.value.toLong(),
    val preserveMetadata: Boolean = true,
    val error: String? = null
) {
    fun options() = ImageProcessingOptions(ProcessingMode.valueOf(mode), dimension, keepOriginal,
        OutputFormat.valueOf(format), quality, Color(background.toULong()), preserveMetadata)
}

@Entity(tableName = "batch_items", primaryKeys = ["jobId", "position"],
    foreignKeys = [ForeignKey(entity = BatchJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)])
data class BatchItem(
    val jobId: String, val position: Int,
    val inputUri: String, val name: String?,
    val originalUri: String = inputUri,
    val state: String = "PENDING",
    val outputUri: String? = null,
    val outputName: String? = null,
    val originalBytes: Long = 0,
    val outputBytes: Long = 0,
    val width: Int = 0, val height: Int = 0,
    val format: String? = null,
    val reason: String? = null
) {
    val terminal: Boolean get() = state in listOf("SAVED", "UNCHANGED", "FAILED", "CANCELLED")
    fun details(mode: ProcessingMode) = ImageProcessingResult.Success(originalBytes, outputBytes,
        OutputFormat.valueOf(requireNotNull(format)), width, height, originalBytes - outputBytes,
        if (originalBytes > 0) 100f * (originalBytes - outputBytes) / originalBytes else 0f, mode)
    fun prepared(details: ImageProcessingResult.Success) = copy(state = "PREPARED",
        originalBytes = details.originalSizeBytes, outputBytes = details.outputSizeBytes,
        width = details.width, height = details.height, format = details.format.name)
}

data class StoredBatch(@Embedded val job: BatchJob,
    @Relation(parentColumn = "id", entityColumn = "jobId") val items: List<BatchItem>)

@Dao
interface BatchDao {
    @Insert suspend fun insertJob(job: BatchJob)
    @Insert suspend fun insertItems(items: List<BatchItem>)
    @Update suspend fun updateItem(item: BatchItem)
    @Query("SELECT * FROM batch_jobs WHERE id = :id") suspend fun job(id: String): BatchJob?
    @Query("SELECT * FROM batch_items WHERE jobId = :id ORDER BY position") suspend fun items(id: String): List<BatchItem>
    @Transaction @Query("SELECT * FROM batch_jobs ORDER BY createdAt DESC LIMIT 1") fun latest(): Flow<StoredBatch?>
    @Query("SELECT * FROM batch_jobs WHERE state IN ('QUEUED', 'PROCESSING', 'CANCELLING', 'INTERRUPTED')") suspend fun unfinished(): List<BatchJob>
    @Query("UPDATE batch_jobs SET state = :state, error = :error WHERE id = :id") suspend fun status(id: String, state: String, error: String? = null)
    @Query("UPDATE batch_jobs SET cancelRequested = 1, state = 'CANCELLING' WHERE id = :id AND state NOT IN ('COMPLETED', 'CANCELLED', 'FAILED')") suspend fun cancel(id: String)
    @Query("UPDATE batch_items SET state = 'CANCELLED', reason = COALESCE(reason, 'Not processed: batch cancelled') WHERE jobId = :id AND state NOT IN ('SAVED', 'UNCHANGED', 'FAILED')") suspend fun cancelRemaining(id: String)
    @Query("DELETE FROM batch_jobs WHERE id = :id") suspend fun deleteJob(id: String)
    @Transaction suspend fun create(job: BatchJob, items: List<BatchItem>) { insertJob(job); insertItems(items) }
}

@Database(entities = [BatchJob::class, BatchItem::class], version = 2, exportSchema = false)
abstract class BatchDatabase : RoomDatabase() { abstract fun batches(): BatchDao }
