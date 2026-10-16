# US-009 background batch implementation

The existing processor now uses a pool of four images by default, configurable from one to eight.
Progress snapshots serialize updates under a mutex, track all active items, and preserve original
item/result ordering even when images finish out of order. Individual failures remain isolated.

Room stores jobs, options, cancellation requests, and item checkpoints. WorkManager receives only
the job ID. Hilt creates the CoroutineWorker; foreground work has a progress notification with a
cancellation action. The Application reconciles queued jobs at startup, and the ViewModel observes
the latest Room job to restore progress and settings.

Destination read/write grants must be persistent. Inputs without persistent grants are copied into
private staging before scheduling; inaccessible inputs receive individual failures. Prepared
candidates survive interruption. Outputs use reserved job/item filenames, recorded document URIs,
and byte-level verification. Retries skip terminal items and recover already-written matching
outputs without re-encoding. Unknown collisions or changed existing content fail safely.

User cancellation stops new claims and drains active items. System interruption retains unfinished
checkpoints for retries. Queued cancellation does not require foreground-service startup, and
restart cancellation reconciles already-written outputs. Sources are never overwritten or deleted.

## Changed files

- `.gitignore`: ignore generated Kotlin cache.
- `app/build.gradle.kts`, `gradle/libs.versions.toml`: Room 2.8.4, WorkManager 2.11.1,
  AndroidX Hilt Work/compiler 1.3.0, and WorkManager test dependencies.
- `app/src/main/AndroidManifest.xml`: foreground/notification permissions, foreground service type,
  custom WorkManager initialization, cancellation receiver.
- `app/src/main/java/com/example/theshrimpapp/TheShrimpApp.kt`: Hilt worker factory and startup reconciliation.
- `features/imageoptimizer/domain/SequentialBatchProcessor.kt`: bounded pool and ordered progress.
- `features/imageoptimizer/background/BatchDatabase.kt`: Room entities, DAO, and database.
- `features/imageoptimizer/background/BatchModule.kt`: database and WorkManager DI.
- `features/imageoptimizer/background/BatchRepository.kt`: durable submission, URI retention/staging,
  scheduling, cancellation, and staging cleanup.
- `features/imageoptimizer/background/PersistentImagePublisher.kt`: verified, recoverable SAF publication.
- `features/imageoptimizer/background/ImageBatchWorker.kt`: checkpointed processing and notifications.
- `features/imageoptimizer/background/CancelBatchReceiver.kt`: notification cancellation persistence.
- `features/imageoptimizer/ui/ImageOptimizerViewModel.kt`, `ImageOptimizerUiState.kt`,
  `ImageOptimizerScreen.kt`, `OutputSelectionScreen.kt`: submission, restored state, multiple active
  items, concurrency control, notification permission, and cancellation.
- `features/imageoptimizer/data/CancellationAwareResult.kt`, `DefaultImageProcessor.kt`,
  `DefaultLosslessImageOptimizer.kt`, `ImageOutputSaver.kt`, `JpegLosslessOptimizer.kt`,
  `PngLosslessOptimizer.kt`, `WebpLosslessOptimizer.kt`: preserve CancellationException propagation
  and release processing resources on failures.
- `app/src/test/.../SequentialBatchProcessorTest.kt`: nine batch tests.
- `app/src/androidTest/.../PersistentBatchInstrumentedTest.kt`: five persistence/recovery/worker tests.
- `app/src/androidTest/.../WorkManagerBatchInstrumentedTest.kt`: real Hilt worker scheduling and completion.
- `app/src/androidTest/.../ImageOutputSaverInstrumentedTest.kt`: isolated provider fixture; removed
  the obsolete instrumentation provider manifest and replaced the prior foreground-only ViewModel test.
- `app/src/androidTest/.../LosslessImageOptimizerInstrumentedTest.kt`: verify safe rejection when the
  phone's WebP encoder includes an ICC profile.
- `docs/SPEC.md`, `docs/ROADMAP.md`: expanded US-009 requirements and executed verification.

Feature paths above are under `app/src/main/java/com/example/theshrimpapp/`; test paths use the
same package under their respective source sets. This report is also added as documentation.

## Executed verification

Final command:

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest --console=plain
```

- Debug APK build: passed.
- JVM tests: 15 passed, zero failures; includes nine batch tests.
- Instrumentation: 33 passed, zero failures on the connected SM-A556E running Android 16.
- Lint: passed; warnings remain.
- `git diff --check`: passed.

Device tests include real WorkManager/Hilt foreground execution, Room reopening, terminal-item
retry skipping, prepared-output recovery, concurrent cancellation, and distinction between user
cancellation and system interruption. Older image-processing and lossless tests also pass.

## Remaining limits

- Android scheduling quotas, foreground-service time limits, and force-stop restrictions still apply.
- Actual forced process death, reboot, and arbitrary SAF-provider crash windows have not been tested;
  restart recovery was simulated by reopening Room and recreating workers.
- SAF creation and Room updates cannot form a shared transaction. Unknown reserved-name collisions
  are preserved and reported, rather than overwritten. Provider failures can leave an incomplete
  document when deletion is unsupported. Private staging also requires available storage.
- Cancellation waits for active processing/publication; native operations are not instantly stoppable.
  Large original-size images can still exceed available memory even with bounded concurrency.
- The project's existing KSP/Kotlin version warning remains. The latest KSP release crashed during
  annotation processing, so the existing setup was retained. A crashing AndroidX opt-in lint detector
  is suppressed only on the Application class; the rest of lint remains enabled.
