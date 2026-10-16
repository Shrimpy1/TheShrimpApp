package com.example.theshrimpapp.features.imageoptimizer.data

import kotlinx.coroutines.CancellationException

/** Optional provider/metadata operations must never turn coroutine cancellation into a fallback. */
inline fun <T> cancellationAwareRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
