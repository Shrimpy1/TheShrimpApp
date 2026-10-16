package com.example.theshrimpapp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import javax.inject.Inject
import dagger.Lazy
import com.example.theshrimpapp.features.imageoptimizer.background.BatchRepository
import kotlinx.coroutines.*

@HiltAndroidApp
// AndroidX's opt-in detector crashes on Configuration.Provider with this Kotlin/AGP toolchain.
@android.annotation.SuppressLint("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
class TheShrimpApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var batches: Lazy<BatchRepository>
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { batches.get().restore() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ImageBatch", "Could not reconcile queued jobs", e) }
        }
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
