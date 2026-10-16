package com.example.theshrimpapp

import com.example.theshrimpapp.features.imageoptimizer.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SequentialBatchProcessorTest {
    @Test fun defaultPoolBoundsConcurrencyAndPreservesResultOrder() = runBlocking {
        val runner = SequentialBatchProcessor()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val active = java.util.concurrent.atomic.AtomicInteger()
        val peak = java.util.concurrent.atomic.AtomicInteger()
        val updates = mutableListOf<BatchProgress<Int, String>>()
        val task = launch {
            runner.run((0..11).toList(), process = { index ->
                withContext(Dispatchers.Default) {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    if (count == 4) started.complete(Unit)
                    try {
                        release.await()
                        delay((12 - index).toLong())
                        if (index == 3) throw java.io.IOException("Bad image")
                        "saved-$index"
                    } finally { active.decrementAndGet() }
                }
            }, failure = { "failed" }, onProgress = { updates.add(it) })
        }
        withTimeout(5000) { started.await() }
        assertEquals(4, active.get())
        release.complete(Unit)
        task.join()
        assertEquals(4, peak.get())
        assertTrue(updates.any { it.activeItems.size == 4 })
        assertEquals((0..11).toList(), updates.last().results.map { it.item })
        assertEquals(12, updates.last().completedCount)
        assertEquals("failed", updates.last().results[3].result)
        assertTrue(updates.zipWithNext().all { (a, b) -> a.completedCount <= b.completedCount })
    }

    @Test fun parallelCancellationDrainsActivePoolWithoutStartingMore() = runBlocking {
        val runner = SequentialBatchProcessor()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        var latest = BatchProgress<Int, String>()
        val task = launch {
            runner.run((0..9).toList(), concurrency = 2, process = {
                visited.add(it)
                if (visited.size == 2) started.complete(Unit)
                release.await()
                "saved-$it"
            }, failure = { "failed" }, onProgress = { latest = it })
        }
        withTimeout(5000) { started.await() }
        runner.cancel()
        release.complete(Unit)
        task.join()
        assertEquals(listOf(0, 1), visited)
        assertEquals(2, latest.completedCount)
        assertEquals(8, latest.remainingCount)
        assertEquals(BatchStatus.CANCELLED, latest.status)
    }

    @Test fun rejectsUnsafeConcurrency() = runBlocking {
        for (limit in listOf(0, 9)) {
            try {
                SequentialBatchProcessor().run(listOf(1), concurrency = limit,
                    process = { "saved" }, failure = { "failed" }, onProgress = {})
                fail("Unsafe pool size accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun usesQueueSnapshotEvenWhenOriginalListChanges() = runBlocking {
        val runner = SequentialBatchProcessor()
        val queue = mutableListOf(1, 2, 3)
        val visited = mutableListOf<Int>()
        runner.run(queue, process = {
            visited.add(it)
            queue.clear()
            "saved"
        }, failure = { "failed" }, onProgress = {})
        assertEquals(listOf(1, 2, 3), visited)
    }

    @Test fun runsSequentiallyAndContinuesAfterFailure() = runBlocking {
        val runner = SequentialBatchProcessor()
        val visited = mutableListOf<Int>()
        val updates = mutableListOf<BatchProgress<Int, String>>()
        var active = 0
        runner.run(listOf(1, 2, 3), concurrency = 1, process = {
            assertEquals(0, active++)
            try {
                visited.add(it)
                yield()
                if (it == 2) throw java.io.IOException("Unreadable image")
                "saved-$it"
            } finally { active-- }
        }, failure = { "failed: ${it.message}" }, onProgress = updates::add)
        assertEquals(listOf(1, 2, 3), visited)
        val final = updates.last()
        assertEquals(BatchStatus.COMPLETED, final.status)
        assertEquals(listOf("saved-1", "failed: Unreadable image", "saved-3"), final.results.map { it.result })
        assertEquals(3, final.completedCount)
        assertNull(final.currentItem)
        assertEquals(listOf(0, 1, 2, 3), updates.map { it.completedCount }.distinct())
    }

    @Test fun cancellationFinishesCurrentFileAndKeepsItsResult() = runBlocking {
        val runner = SequentialBatchProcessor()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val visited = mutableListOf<Int>()
        var latest = BatchProgress<Int, String>()
        val job = launch {
            runner.run(listOf(1, 2, 3), concurrency = 1, process = {
                visited.add(it)
                if (it == 2) { started.complete(Unit); finish.await() }
                "saved-$it"
            }, failure = { "failed" }, onProgress = { latest = it })
        }
        started.await()
        assertEquals(1, latest.completedCount)
        assertEquals(2, latest.currentItem)
        runner.cancel()
        assertTrue(job.isActive)
        finish.complete(Unit)
        job.join()
        assertEquals(listOf(1, 2), visited)
        assertEquals(BatchStatus.CANCELLED, latest.status)
        assertEquals(listOf("saved-1", "saved-2"), latest.results.map { it.result })
        assertEquals(1, latest.remainingCount)
    }

    @Test fun guardsConcurrentLaunchesAndAllowsFreshRunAfterCancellation() = runBlocking {
        val runner = SequentialBatchProcessor()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            runner.run(listOf(1, 2), concurrency = 1, process = { started.complete(Unit); finish.await(); "saved" },
                failure = { "failed" }, onProgress = {})
        }
        started.await()
        assertFalse(runner.run(listOf(9), process = { error("Concurrent processing") },
            failure = { "failed" }, onProgress = {}))
        runner.cancel()
        finish.complete(Unit)
        job.join()
        var latest = BatchProgress<Int, String>()
        assertTrue(runner.run(listOf(3), process = { "saved-$it" }, failure = { "failed" }, onProgress = { latest = it }))
        assertEquals(BatchStatus.COMPLETED, latest.status)
        assertEquals("saved-3", latest.results.single().result)
    }

    @Test fun cancellationBeforeFirstFileDoesNotStartWork() = runBlocking {
        val runner = SequentialBatchProcessor()
        var latest = BatchProgress<Int, String>()
        runner.run(listOf(1, 2), concurrency = 1, process = { error("Should not start") }, failure = { "failed" }, onProgress = {
            latest = it
            if (it.status == BatchStatus.PROCESSING) runner.cancel()
        })
        assertEquals(BatchStatus.CANCELLED, latest.status)
        assertEquals(0, latest.completedCount)
        assertEquals(2, latest.remainingCount)
    }

    @Test fun coroutineCancellationIsNotConvertedIntoFileFailure() = runBlocking {
        val runner = SequentialBatchProcessor()
        var latest = BatchProgress<Int, String>()
        val job = launch {
            runner.run<Int, String>(listOf(1, 2), process = { throw CancellationException("Screen closed") },
                failure = { error("Cancellation treated as failure") }, onProgress = { latest = it })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(BatchStatus.INTERRUPTED, latest.status)
        assertEquals(0, latest.completedCount)
        assertTrue(runner.run(listOf(3), process = { "saved" }, failure = { "failed" }, onProgress = {}))
    }
}
