package com.example.theshrimpapp.features.imageoptimizer.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class CancelBatchReceiver : BroadcastReceiver() {
    @Inject lateinit var repository: BatchRepository
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("jobId") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { repository.cancel(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ImageBatch", "Could not request cancellation", e) }
            finally { pending.finish() }
        }
    }
}
