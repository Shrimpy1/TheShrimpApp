package com.example.theshrimpapp.features.imageoptimizer.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

const val DEFAULT_BATCH_CONCURRENCY = 4
const val MAX_BATCH_CONCURRENCY = 8

enum class BatchStatus { IDLE, QUEUED, PROCESSING, CANCELLING, INTERRUPTED, COMPLETED, CANCELLED, FAILED }
data class BatchItemResult<T, R>(val item: T, val result: R)
data class BatchProgress<T, R>(
    val status: BatchStatus = BatchStatus.IDLE,
    val totalCount: Int = 0,
    val activeItems: List<T> = emptyList(),
    val results: List<BatchItemResult<T, R>> = emptyList()
) {
    val currentItem: T? get() = activeItems.firstOrNull()
    val completedCount: Int get() = results.size
    val remainingCount: Int get() = totalCount - completedCount
    val isActive: Boolean get() = status in listOf(BatchStatus.QUEUED, BatchStatus.PROCESSING, BatchStatus.CANCELLING, BatchStatus.INTERRUPTED)
}

/** Historical name retained; now runs a bounded pool, publishing ordered snapshots under a mutex. */
class SequentialBatchProcessor @Inject constructor() {
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    fun cancel() { if (running.get()) cancelRequested.set(true) }

    suspend fun <T, R> run(
        items: List<T>,
        process: suspend (T) -> R,
        failure: (Exception) -> R,
        onProgress: suspend (BatchProgress<T, R>) -> Unit,
        concurrency: Int = DEFAULT_BATCH_CONCURRENCY,
        shouldStop: suspend () -> Boolean = { false }
    ): Boolean {
        require(concurrency in 1..MAX_BATCH_CONCURRENCY)
        if (!running.compareAndSet(false, true)) return false
        cancelRequested.set(false)
        val queue = items.toList()
        val gate = Mutex()
        var next = 0
        val active = sortedSetOf<Int>()
        val finished = sortedMapOf<Int, BatchItemResult<T, R>>()
        suspend fun publish(status: BatchStatus) = onProgress(BatchProgress(status, queue.size,
            active.map { queue[it] }, finished.values.toList()))
        try {
            gate.withLock { publish(BatchStatus.PROCESSING) }
            coroutineScope {
                List(minOf(concurrency, queue.size)) {
                    async {
                        while (true) {
                            yield()
                            val index = gate.withLock {
                                if (cancelRequested.get() || shouldStop()) {
                                    cancelRequested.set(true)
                                    null
                                } else if (next >= queue.size) null else next++.also {
                                    active.add(it)
                                    publish(BatchStatus.PROCESSING)
                                }
                            } ?: break
                            val result = try { process(queue[index]) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { failure(e) }
                            gate.withLock {
                                active.remove(index)
                                finished[index] = BatchItemResult(queue[index], result)
                                publish(if (cancelRequested.get()) BatchStatus.CANCELLING else BatchStatus.PROCESSING)
                            }
                        }
                    }
                }.awaitAll()
            }
            gate.withLock { publish(if (finished.size == queue.size) BatchStatus.COMPLETED else BatchStatus.CANCELLED) }
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) { gate.withLock {
                active.clear()
                publish(if (cancelRequested.get()) BatchStatus.CANCELLED else BatchStatus.INTERRUPTED)
            } }
            throw e
        } finally { running.set(false) }
    }
}
