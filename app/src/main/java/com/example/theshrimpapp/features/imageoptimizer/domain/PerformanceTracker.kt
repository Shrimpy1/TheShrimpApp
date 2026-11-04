package com.example.theshrimpapp.features.imageoptimizer.domain

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class PerformanceStage {
    SELECTION,
    GRANT_RETENTION,
    STAGING,
    BOUNDS_EXIF,
    DECODE,
    TRANSFORM,
    ENCODE,
    EXIF_SAVE,
    POST_OPTIMIZATION,
    CANDIDATE_ADOPTION,
    PUBLICATION_COPY,
    DESTINATION_VERIFICATION,
    ROOM_TRANSITION,
    PROGRESS_CALLBACK,
    DIRECTORY_QUERY,
    LOCK_WAIT
}

enum class PerformanceCounter {
    DIRECTORY_QUERIES,
    CHILD_DIRECTORY_QUERIES,
    DOCUMENT_QUERIES,
    ROOM_TRANSITIONS,
    PROGRESS_CALLBACKS,
    LOCK_WAITS,
    BYTES_READ,
    BYTES_WRITTEN,
    FAILED_ITEMS
}

object PerformanceTracker {
    val enabled = AtomicBoolean(false)

    private val stageTimesNanos = ConcurrentHashMap<PerformanceStage, MutableList<Long>>()
    private val counterValues = ConcurrentHashMap<PerformanceCounter, AtomicLong>()

    fun reset() {
        stageTimesNanos.clear()
        counterValues.clear()
    }

    inline fun <T> trackStage(stage: PerformanceStage, block: () -> T): T {
        if (!enabled.get()) return block()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val duration = System.nanoTime() - start
            recordStageTime(stage, duration)
        }
    }

    fun recordStageTime(stage: PerformanceStage, durationNanos: Long) {
        if (!enabled.get()) return
        stageTimesNanos.computeIfAbsent(stage) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(durationNanos)
    }

    fun incrementCounter(counter: PerformanceCounter, delta: Long = 1) {
        if (!enabled.get()) return
        counterValues.computeIfAbsent(counter) { AtomicLong(0) }.addAndGet(delta)
    }

    fun getStageTotalNanos(stage: PerformanceStage): Long {
        val list = stageTimesNanos[stage] ?: return 0L
        synchronized(list) {
            return list.sum()
        }
    }

    fun getStagePercentilesNanos(stage: PerformanceStage): Pair<Long, Long> {
        val list = stageTimesNanos[stage] ?: return 0L to 0L
        val sorted = synchronized(list) { list.sorted() }
        if (sorted.isEmpty()) return 0L to 0L
        val p50Index = (sorted.size * 0.50).toInt().coerceIn(0, sorted.size - 1)
        val p95Index = (sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)
        return sorted[p50Index] to sorted[p95Index]
    }

    fun getCounterValue(counter: PerformanceCounter): Long {
        return counterValues[counter]?.get() ?: 0L
    }

    data class Report(
        val stageTotalsMs: Map<PerformanceStage, Long>,
        val stageP50Ms: Map<PerformanceStage, Long>,
        val stageP95Ms: Map<PerformanceStage, Long>,
        val counters: Map<PerformanceCounter, Long>
    )

    fun generateReport(): Report {
        val stageTotals = mutableMapOf<PerformanceStage, Long>()
        val stageP50 = mutableMapOf<PerformanceStage, Long>()
        val stageP95 = mutableMapOf<PerformanceStage, Long>()

        for (stage in PerformanceStage.values()) {
            stageTotals[stage] = getStageTotalNanos(stage) / 1_000_000L
            val (p50, p95) = getStagePercentilesNanos(stage)
            stageP50[stage] = p50 / 1_000_000L
            stageP95[stage] = p95 / 1_000_000L
        }

        val countersMap = mutableMapOf<PerformanceCounter, Long>()
        for (counter in PerformanceCounter.values()) {
            countersMap[counter] = getCounterValue(counter)
        }

        return Report(stageTotals, stageP50, stageP95, countersMap)
    }
}
