# Image optimizer performance execution plan

Status: planned; no implementation tasks completed by writing this document. Code inspection date:
2026-11-05. Paths below are relative to the repository root.

## 1. Purpose and mini specification

Improve sustained batch throughput, memory use, allocation rate, provider traffic, and temporary
storage for batches of up to 4,000 images, approximately 5 MB compressed per input. The primary
acceptance workload is **Replace Original + Resize & Convert (`REDUCE_SIZE`) + lossy output at
quality 85 + Preserve Metadata enabled**. Exercise both JPEG and WebP lossy output; the user has not
selected one exclusive output codec. Quality 85 is the encoder setting, not a promise of 85%
perceptual quality or a particular compression ratio.

Keep the existing dimension choice and Keep Original Size option. The current default longest side
is **3840**, not 2560: `DIMENSION_PRESETS[2]` selects 3840. Do not silently lower dimensions, change
quality, or disable EXIF preservation to achieve a benchmark result.

### 1.1 Authority and verified current behavior

Use this plan for the changes explicitly specified here. Use current source and regression tests to
establish existing behavior. Use [SPEC.md](SPEC.md) and [ROADMAP.md](ROADMAP.md) only for the
corresponding principles listed below; their checked boxes, old measurements, and future-work lists
are not evidence that the present implementation satisfies a requirement.

| Topic              | Verified current implementation                                                                                                                    | Instruction for this plan                                                                                                                                                                                    |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Batch execution    | `ImageOptimizerViewModel.processBatch()` submits through `BatchRepository`; `ImageBatchWorker` uses a bounded `SequentialBatchProcessor` pool.     | Keep Room/WorkManager execution and restart support. The scheduler name does not imply sequential execution.                                                                                                 |
| Batch defaults     | Limit 4000; concurrency default 4, maximum 8; quality 85; dimension presets 1920/2560/3840.                                                        | Change new-job concurrency to 2 in OPT-09; retain manual range 1–8 and existing saved job settings. Correct the stale dimension comment without changing 3840.                                               |
| Modes              | `LOSSLESS_OPTIMIZE` and `REDUCE_SIZE`; the latter is displayed as Resize & Convert.                                                                | “Lossy convert” in this plan means REDUCE_SIZE with JPEG or WEBP_LOSSY. Keep LOSSLESS_OPTIMIZE as the existing initial processing mode.                                                                      |
| Destinations       | REPLACE_ORIGINAL, SUBFOLDER, CUSTOM_FOLDER all exist. SUBFOLDER is the initial destination.                                                        | Optimize replacement first. Do not change the initial destination or remove the other modes. Selection of Replace Original supplies authorization for that batch; do not add per-image confirmation dialogs. |
| Final optimization | REDUCE_SIZE always attempts another lossless optimization after encoding.                                                                          | OPT-03 explicitly supersedes SPEC §6.3's mandatory automatic post-pass for new REDUCE_SIZE jobs. Persist a speed/size policy and preserve legacy behavior on existing jobs.                                  |
| Metadata           | REDUCE_SIZE invokes EXIF copying unconditionally, potentially twice; lossless backends use `preserveMetadata`.                                     | Correct the missing flag check and define supported preservation precisely in OPT-02. Do not describe current behavior as preserving all possible metadata.                                                  |
| Persistence        | Room schema version 5; `BatchModule` enables destructive migration.                                                                                | Add explicit incremental migrations for changes in this plan. Never erase current jobs to install an optimization.                                                                                           |
| Preferences        | `ImageOptimizerPreferences` uses SharedPreferences for folder-selection preferences.                                                               | Keep it; a DataStore migration is outside this plan. SPEC's future DataStore guidance is not an instruction to replace it.                                                                                   |
| Selection          | Multiple folders, nested scans, format selection, file-size filtering, and individual inputs exist.                                                | Preserve them despite ROADMAP §7 deferring recursive selection.                                                                                                                                              |
| Native processing  | JPEG uses JNI/libjpeg-turbo; PNG streams container/scanline data through temporary files; WebP uses bitmap lossless encoding and pixel validation. | Do not implement speculative cross-format lossless alternatives from older roadmap text.                                                                                                                     |
| UI                 | Selection, output selection, processing, and results are separate screens.                                                                         | Keep navigation and previous-batch dismissal behavior; do not restore the initial single-screen design.                                                                                                      |
| Build              | `../app/build.gradle.kts`: minSdk 30, compileSdk 37, targetSdk 36.                                                                                 | Do not change SDKs, Gradle, native dependencies, or unrelated features for this work.                                                                                                                        |

Relevant surviving references: SPEC §§4–5 for boundaries/DI; §§6.2–6.5 for geometry, explicit lossy
behavior, cancellation, and accurate totals; §7 for authorized storage; §8.4 for persisted
background execution; §§11–12 for errors/testing. ROADMAP US-004, US-007 through US-010, EN-004, and
EN-005 are contextual cross-references, not new implementation backlogs.

### 1.2 Non-negotiable behavior

1. Process images locally. Do not add telemetry, network transfers, bitmap payloads in Room, or
   image bytes in WorkManager Data. Pass only the job ID to WorkManager.
2. Freeze options and selected input ordering at submission. Preserve per-item failure isolation,
   completed outputs, and ordered results. Do not drop unsupported files silently.
3. Keep quality, dimensions, format, background color, and metadata semantics explicit. A speed
   policy may skip an optional optimization, but must not introduce a second lossy encode or
   silently change the requested output format.
4. LOSSLESS_OPTIMIZE must never resize or encode lossily. Retain its strict-smaller rule and
   existing unsupported-format limitations. REDUCE_SIZE may produce larger output; report a negative
   saving accurately.
5. Normalize all eight EXIF orientations correctly when re-encoding. Preserve the supported EXIF
   attributes when requested, update dimensions, and reset orientation consistently with transformed
   pixels.
6. User cancellation stops new admissions; already-active items finish and are recorded. System
   interruption retains recoverable work and is not user cancellation. Durable publication/recovery
   work takes precedence over cancelling pending work.
7. An output is SAVED only after its closed destination stream has been reread and its byte count
   and SHA-256 match the finalized candidate. Hashing bytes supplied to an output stream alone is
   not destination verification.
8. Never delete or overwrite a document based only on a matching name. Use recorded URIs/document
   IDs and ownership checks. A cached directory index is an optimization, not authority to overwrite
   a collision.
9. Keep candidates/checkpoints needed for recovery until publication is committed. A provider and
   Room do not share an atomic transaction. `NonCancellable` does not protect against process death;
   journal each destructive boundary.
10. Use `Long` for file sizes, sums, and checked dimension arithmetic. Unknown size is unknown, not
    zero; empty files and unknown sizes are distinct.

### 1.3 Clean-code and execution protocol

1. Before each story, inspect the named source, call sites, tests, and current Git diff. Preserve
   unrelated user edits. Reconcile renamed symbols by following call sites; do not recreate an
   obsolete path merely to match this document.
2. Execute stories in the listed order. Every story below is in scope. Dependencies are mandatory.
   Do not start implementation by globally rewriting architecture or adding a generic framework.
3. Keep orchestration/recovery in `background`, codec/storage implementations in `data`, immutable
   options/results and testable policies in `domain`, and rendering/event handling in `ui`. Use
   constructor injection for shared dependencies. Avoid adding new Android UI dependencies to domain
   policies.
4. Use small named types for candidate ownership, progress summaries, and publication phases. Prefer
   enums/sealed types internally; retain compatible persisted representations and explicit
   conversion/error handling. Do not scatter state strings, magic timing values, or duplicate
   defaults across layers.
5. Keep provider/file I/O and bitmap/native work off Main. Dispatchers alone do not limit
   concurrency: use the common scheduler/admission controller. Nested processors must not launch
   additional unbounded image jobs.
6. No database calls, provider calls, notification work, or suspending callbacks while holding the
   queue bookkeeping mutex. Per-item publication locks and per-folder name reservation locks must
   not become one global encoding lock.
7. Scope caches to a job or item. Immutable EXIF tag definitions may be process-wide. Clear job
   caches on finish; rebuild from durable state on restart. Never cache a bitmap or full input byte
   array across items.
8. Propagate `CancellationException`. Close streams with `use`; release bitmap/native resources in
   `finally`. Do not catch errors and return fake success. Keep side effects outside
   `MutableStateFlow.update` lambdas, which may be retried.
9. For each schema change, increment from the actual current version, register a non-destructive
   migration, and test old rows including QUEUED/PREPARED/SAVED items. Export schemas going forward.
   If an older schema cannot be reconstructed, fail the upgrade clearly instead of deleting its
   database; record the unsupported migration explicitly.
10. Persist state changes required for recovery immediately. Throttle presentation, not durability.
    Progress counters must be updated atomically with the corresponding item transition and must be
    idempotent on retries.
11. Add tests for changed correctness boundaries and observable complexity, not trivial getters or
    code-shaped mocks. Use the existing test DocumentsProvider and extend its query/I/O/failure
    controls. Never run replacement tests against the user's actual photos.
12. Run focused tests during each story. At integration, run
    `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`; run
    `./gradlew.bat :app:connectedDebugAndroidTest` with a disposable test corpus on a connected
    device. On non-Windows use `../gradlew`. Record environment/tool failures separately from
    application failures.
13. Record each story's implementation, tests, measured effects, and limitations in the execution
    log at the end. Mark complete only after its acceptance checks pass. Without a device,
    device-dependent acceptance stays pending; do not copy old ROADMAP test counts as new evidence.
14. Do not add production logging of filenames, URIs, EXIF values, or image contents for performance
    work. Debug metrics identify items by job-local position and measure stages/counters only.

### 1.4 Measurement and compatibility rules

- Treat the earlier cost ranking as a hypothesis. The quadratic output enumeration primarily affects
  SUBFOLDER/CUSTOM_FOLDER. Replacement has a different path, including a redundant source-name
  search; measure it separately.
- Separate startup selection/staging, encoding/post-processing, publication, and completion time.
  Report logical bytes read/written separately from physical disk traffic; OS caches can hide
  physical reads.
- Existing queued jobs retain their persisted concurrency and legacy post-optimization policy after
  migration. New settings affect new jobs only. Default changes must be centralized.
- Do not remove destination readback to advertise speed. Report the cost of any newly required
  replacement backup separately; safety improvements may add I/O even while other stages become
  faster.
- Keep output format and geometry constant when comparing algorithms. For policies/compression
  levels that intentionally affect encoded size, report the size difference alongside throughput.

## 2. Ordered user stories

| Order | ID     | User outcome                                                    | Priority            |
|-------|--------|-----------------------------------------------------------------|---------------------|
| 1     | OPT-01 | Reproducible measurements of the current primary workflow       | P0                  |
| 2     | OPT-02 | Preserve EXIF correctly with fewer reads and rewrites           | P0                  |
| 3     | OPT-03 | Avoid the expensive optional second optimization in speed mode  | P0                  |
| 4     | OPT-04 | Reuse finalized candidate files and their validation evidence   | P0                  |
| 5     | OPT-05 | Replace originals efficiently with recoverable publication      | P0                  |
| 6     | OPT-06 | Workers continue without waiting for progress reporting         | P0                  |
| 7     | OPT-07 | Responsive UI and cheap, accurate size/progress summaries       | P0                  |
| 8     | OPT-08 | Reduce bitmap allocations without changing image geometry       | P0                  |
| 9     | OPT-09 | Use bounded, memory-aware concurrency with default 2            | P0                  |
| 10    | OPT-10 | Load folders and retain permissions with fewer provider calls   | P1                  |
| 11    | OPT-11 | Bound temporary storage and release completed staged inputs     | P1                  |
| 12    | OPT-12 | Scale subfolder/custom-folder publishing without repeated scans | P1                  |
| 13    | OPT-13 | Validate WebP pixels in blocks and avoid duplicate validation   | P1                  |
| 14    | OPT-14 | Stream PNG recompression/validation without raw temporary files | P1                  |
| 15    | OPT-15 | Verify full-batch improvements and document actual limits       | P0 integration gate |

### OPT-01 — Establish a reproducible baseline

**Story:** As the user, I want evidence of what slows my real conversion workflow so improvements
are measurable.

**Problem:** Static analysis identifies expensive work but does not establish elapsed time, memory
peaks, thermal behavior, or the relative costs of JPEG versus WebP output.

**Scope:** Worker/scheduler, processor, publisher, test DocumentsProvider, and a new
`docs/performance-image-optimizer.md`. Context: ROADMAP EN-005.

**Dependencies:** None.

**Steps:**

1. Record the current revision plus dirty-file status, SDK/build configuration, device model/API,
   RAM/memory class, provider, available storage, and benchmark build variant. Do not modify
   unrelated dirty files.
2. Add debug/test-only stage timing and counters for selection, grant retention, staging,
   bounds/EXIF, decode, transform, encode, EXIF save, post-optimization, candidate adoption,
   publication copy, destination verification, Room transitions, progress callbacks, directory
   queries, and lock wait. Use monotonic time. Keep instrumentation disabled for normal production
   runs.
3. Extend the existing test DocumentsProvider with child-query/document-query counts, bytes
   read/written, and injected failures at create/write/reopen/delete boundaries. Retain its existing
   corruption and source-alias tests.
4. Prepare reproducible fixtures with a manifest recording dimensions, codec, compressed bytes,
   orientation, alpha, and expected supported EXIF tags. Include 12 MP photos, larger photos, all
   eight orientation values, nested folders, repeated basenames, opaque images, alpha images, and
   corrupt input. Use copies for replacement.
5. Baseline both JPEG quality 85 and WEBP_LOSSY quality 85, preserveMetadata=true,
   maxDimension=3840, keepOriginalSize=false, REPLACE_ORIGINAL. Separately exercise
   keepOriginalSize=true. Record current mandatory post-optimization. Test concurrency 1, 2, and 4;
   reserve 8 for later only if memory estimates permit.
6. Use small fixtures for correctness, then repeated fresh 200-image corpora for performance
   screening. Restore source copies before every run. Record wall time, images/minute, per-stage
   totals/p50/p95, peak Java/native/PSS memory, GC time, logical I/O, temporary disk peak, failure
   count, and output-byte totals. Separate cold/warm runs.
7. Establish the 4,000-image endurance procedure used in OPT-15; do not require a slow full run
   after every small edit. If no device exists, complete the harness and record device measurements
   as pending.

**Expected result / acceptance:** A reproducible report template and counters exist; the baseline
has no invented timings. Query-count tests demonstrate current growth separately for replacement and
new-output destinations. Benchmark data never comes from destructive runs on the user's original
library.

### OPT-02 — Parse and preserve EXIF once per image

**Story:** As a user preserving EXIF, I want correct metadata without reopening and rewriting the
same image unnecessarily.

**Problem:** `DefaultImageProcessor.readImageInfo()` parses orientation, `copyExifMetadata()` parses
the source again and reflects over tag constants for each copy, and a winning post-optimized
candidate may trigger a second copy. REDUCE_SIZE ignores preserveMetadata when calling this
function.

**Scope:** `data/DefaultImageProcessor.kt`, metadata tests in `ImageProcessorInstrumentedTest.kt`,
options contract. Context: SPEC §§6.2–6.3; ROADMAP US-007.

**Dependencies:** OPT-01.

**Steps:**

1. Extract an item-local immutable metadata snapshot containing orientation and present transferable
   EXIF attributes. Parse the source once for metadata; bounds inspection may remain a separate
   header read. Never retain the source stream or an ExifInterface referencing a closed resource for
   later mutation.
2. Compute reflection-derived public TAG names and the existing skip-tag set once as immutable
   definitions. Preserve the current exclusions for thumbnail offsets/data, MakerNote, Xmp, and
   unsafe file-layout tags. Do not claim those excluded values are preserved.
3. For preserveMetadata=true, copy supported present attributes, including supported GPS/date/camera
   tags; normalize orientation and dimension tags to the output pixels. For false, read orientation
   for rendering but do not transplant optional source EXIF. Do not remove color information
   required for correct rendering as a shortcut.
4. Skip EXIF saving when there are no transferable attributes. Do not create an empty block solely
   for bookkeeping. Report unsupported preservation behavior as an explicit limitation, not full
   metadata preservation.
5. Until OPT-03/04 changes candidate ownership, reuse the snapshot for all necessary writes; never
   reparse the original. In the final design, finalize metadata on the baseline candidate once, and
   require post-optimization to retain that finalized metadata. Do not routinely reattach metadata
   to a validated post-optimized output; reject/fall back if its metadata contract fails.
6. Ensure size comparisons and final hashes happen after all metadata edits. No EXIF edit is
   permitted after a candidate becomes PREPARED.

**Expected result / acceptance:** One source metadata parse per item; no optional EXIF copy with
preservation disabled; cached tag definitions; one baseline metadata-save operation when needed.
Tests cover eight orientations, resized dimensions, GPS/date/camera values, no-EXIF input,
preservation off, and post-pass fallback. Preserve=true remains the primary benchmark configuration.

### OPT-03 — Make optional post-optimization a persisted processing policy

**Story:** As a user converting thousands of images, I want a faster path that avoids expensive
recompression when I prioritize runtime.

**Problem:** REDUCE_SIZE unconditionally runs lossless optimization after encoding. For WebP this
can decode and losslessly re-encode an already lossy result, then discard a larger candidate after
expensive validation.

**Scope:** `domain/ImageProcessingOptions.kt`, defaults, BatchJob/options serialization and
migrations, ViewModel/UI options, `DefaultImageProcessor.kt`, tests. Context: ROADMAP US-007;
intentional change to SPEC §6.3 shared-final-optimization policy.

**Dependencies:** OPT-02.

**Steps:**

1. Add `PostOptimizationPolicy` with exactly `SKIP` and `TRY_LOSSLESS`. Expose the choice only in
   REDUCE_SIZE as “Faster processing” and “Try further size reduction”. Explain that the second
   option takes longer and may save no additional bytes. Keep codec internals out of the product UI.
2. Default newly submitted REDUCE_SIZE jobs to SKIP. Keep quality 85, selected format, dimensions,
   and preserveMetadata unchanged. Persist the choice on BatchJob and restore it from that job. Add
   a migration that assigns TRY_LOSSLESS to every existing job so resumed work keeps its original
   policy.
3. In SKIP, encode once, finalize metadata, validate the ordinary output, and proceed to
   publication. Do not invoke a lossless backend. In LOSSLESS_OPTIMIZE, ignore this post-pass
   setting and execute the existing lossless workflow.
4. In TRY_LOSSLESS, optimize the finalized encoded candidate, retain the requested output format,
   and compare fully finalized sizes. Retain the baseline candidate unless the alternate is valid,
   metadata-compatible, and strictly smaller. A WEBP_LOSSY request must not silently become
   WEBP_LOSSLESS; discard a backend candidate that changes the requested format.
5. Propagate cancellation. On an optional post-pass failure, preserve the valid baseline and record
   diagnostic outcome without marking the item failed. Never launch another lossy encode as a
   fallback.
6. Validate the post-policy value at deserialization; an invalid stored value uses legacy
   TRY_LOSSLESS and records a diagnostic. Hide/disable irrelevant controls consistently when options
   are locked during a batch.

**Expected result / acceptance:** SKIP produces zero post-optimizer calls. TRY_LOSSLESS preserves
supported legacy behavior and falls back safely; migrated jobs keep their policy. Tests verify
format fidelity, strict-smaller comparison after metadata, fallback, persistence, and unchanged
primary encoder quality. Measure extra bytes versus time saved rather than promising equal file
sizes.

### OPT-04 — Return owned, finalized candidates instead of copying through URI layers

**Story:** As a user, I want fewer temporary transfers without sacrificing verified outputs or
restart recovery.

**Problem:** Processors stage local inputs again, generate local candidates, copy them into output
URIs, sometimes create readback files, and then the worker/publisher copy or hash them again.
Ownership and validation evidence do not cross these boundaries.

**Scope:** Processor/lossless interfaces and implementations, BatchRepository candidate storage,
worker/publisher, compatibility adapters for `ImageOutputSaver` and tests.

**Dependencies:** OPT-03.

**Steps:**

1. Introduce an internal `PreparedCandidate` result with owned local file, actual original/output
   bytes, format, dimensions, processing mode, finalized metadata status, and SHA-256 of final
   encoded bytes. Represent unchanged/unsupported/failure separately. It must not contain bitmaps or
   full encoded byte arrays.
2. Define ownership explicitly: the producing processor owns scratch files until transfer;
   successful adoption transfers ownership to the batch repository; the publisher borrows the
   candidate and never deletes it; the repository deletes it only after a committed terminal
   transition and no pending recovery. Input files are never owned merely because their URI scheme
   is `file`.
3. Reuse an existing app-owned input file read-only when its provenance is the repository's staging
   area or another owned candidate. For arbitrary content URIs, stage only when the codec needs
   seeking/local paths. Never reinterpret content URIs as filesystem paths.
4. Keep structural/semantic validation at the backend's final candidate boundary. After metadata is
   final, compute encoded SHA-256 during an already-required transfer into durable staging. If
   adoption is a same-filesystem move, compute the hash once by streaming the final file. Do not
   hash pre-EXIF bytes.
5. Adopt into the job's persistent `filesDir` staging directory using a checked same-filesystem move
   where supported. Otherwise copy once with a 64 KiB reusable-per-operation buffer while
   counting/hashing, flush/close and sync the private file before recording PREPARED, then remove
   the prior owned scratch file. Never record PREPARED pointing only to cacheDir.
6. Persist the digest with PREPARED using a migration. For old PREPARED rows without a digest,
   verify candidate existence/length and compute/persist it once during recovery. Missing or invalid
   candidates must not trigger re-encoding from an original that may already have been replaced;
   route through OPT-05 recovery.
7. Retain one closed-destination readback hash in the publisher. Remove intermediate local readback
   copies only when the candidate's semantic validation and verified byte identity cover the same
   invariant. Keep public URI-output adapters for remaining callers and apply destination
   verification there.
8. Search all `ImageProcessor`/`LosslessImageOptimizer`/`ImageOutputSaver` call sites. Adapt them to
   the shared candidate path; do not leave a second inconsistent production algorithm merely to
   satisfy old tests.

**Expected result / acceptance:** No redundant input copy for recognized owned local files;
candidate handoff avoids a copy when a move is valid; normal publication uses its persisted digest
and one destination readback. Interruption tests cover before/after adoption and PREPARED
persistence. Provider corruption remains detected. Local scratch cleanup cannot delete durable
candidates or borrowed inputs.

### OPT-05 — Optimize and journal Replace Original publication

**Story:** As a user primarily replacing originals, I want each item written to the correct existing
location, with efficient lookup and recoverable failure handling.

**Problem:** The replacement branch searches the parent by filename even though `originalUri` is
known. Cross-format replacement currently deletes the original before writing the new file; recovery
checks the old URI, which may no longer be the output. Errors can discard the candidate needed to
recover. These boundaries must be corrected before consolidating verification/copies.

**Scope:** `PersistentImagePublisher`, worker error handling, BatchItem/migrations, BatchRepository
cleanup, replacement tests. Context: SPEC §§7.4 and 8.4; ROADMAP US-008/009. The recovery work here
is a correctness prerequisite, not a claimed performance optimization.

**Dependencies:** OPT-04.

**Steps:**

1. Address a same-format source directly using its recorded original URI/document ID. Determine
   source format by inspected content/MIME, not filename equality alone: `.jpeg` and `.jpg` are not
   distinct image formats. Keep the existing filename/URI for same-format writes. Resolve a parent
   only for cross-format creation. Cache resolved parent identity per job and preserve nested-parent
   information; never substitute the granted tree root for a selected nested directory.
2. Add a persisted publication phase separate from the processing state, with `NONE`, `READY`,
   `TARGET_RESERVED`, `WRITE_STARTED`, `VERIFIED`, and `SOURCE_REMOVED`. Store the actual target
   URI/name, candidate digest, and optional original-backup path/digest. SAVED is the committed
   terminal state, not an intermediate phase. Map legacy PREPARED items to READY and reconcile any
   recorded output before making changes.
3. For same-format overwrite, establish a durable original backup before WRITE_STARTED. Reuse an
   already-staged exact original when available; otherwise stream one private backup and hash it.
   Persist its location before opening the original with truncation. If backup creation fails, fail
   without touching the original. Keep this I/O visible in benchmarks.
4. For cross-format replacement, reserve a new document in the exact source parent while retaining
   the original. Use the original stem with the requested extension; suffix on a collision rather
   than overwriting an unrelated sibling. Record the returned actual URI/name before writing.
   Without permission to create in the actual parent, fail without modifying the original; do not
   write WebP bytes into a JPEG-named file as fallback.
5. Cross-format target creation must reject an alias of the source or a known existing sibling.
   Query siblings once per parent to build an identity/name index, updated on reservations and
   removals; OPT-12 generalizes this index. A resumed item uses its recorded target, not a newly
   generated name. If the process died between create and checkpoint, use the persisted reservation
   identity/name and verify ownership before reuse; unknown collisions remain untouched.
6. Persist WRITE_STARTED, copy the candidate with a bounded buffer, close, reread the target, and
   compare byte count/SHA-256. Persist VERIFIED. For cross-format replacement only, delete the
   original after verification and check deletion success; persist SOURCE_REMOVED, then SAVED.
   Record the actual target URI for results/recovery. The publisher owns this single SAVED
   transaction; remove the worker's duplicate update.
7. If cross-format source deletion fails, retain the verified output and original, leave the item
   PREPARED/VERIFIED, record a recovery error, and return a typed retryable publication outcome. Do
   not claim replacement success, re-encode, or delete the candidate. Unrelated later items may
   finish; the job remains INTERRUPTED until reconciliation succeeds. Limit provider retry to
   WorkManager backoff rather than a tight loop.
8. On same-format write/verification failure, restore the backup and verify restored bytes. If
   restoration succeeds, record FAILED and release recovery files after the commit. If restoration
   is blocked, retain backup/candidate and WRITE_STARTED, record the error, and retry
   reconciliation. Do not promise atomic replacement on arbitrary SAF providers.
9. Recovery first compares the recorded target with the candidate digest. Matching output resumes
   VERIFIED/source deletion/commit without encoding. A mismatching same-format target after
   WRITE_STARTED restores its backup before retry/failure. A mismatching app-owned cross-format
   partial output may be rewritten from the candidate. Missing targets are recreated only when doing
   so cannot overwrite an unrelated document. Legacy ambiguous cases retain artifacts and report an
   explicit recovery error.
10. Handle user cancellation by reconciling publication already begun, then cancelling only work
    with no destructive boundary entered. Preserve active-image-finish semantics. Never pass
    unresolved publication items through bulk `cancelRemaining()` or recursive job cleanup.

**Expected result / acceptance:** Same-format replacement does not enumerate its parent.
Cross-format replacement never deletes the source before verified output. Tests cover nested
parents, `.jpeg`, filename collisions, URI aliases, insufficient backup space, interrupted writes,
delete failure, cancelled recovery, and process recreation at every phase. Retrying never encodes
already-verified output or creates duplicate successful outputs. Unrecoverable provider access
retains recovery artifacts and an honest interrupted/error status.

### OPT-06 — Decouple scheduling from reporting and cancellation observation

**Story:** As a user, I want workers to keep processing while progress notifications update.

**Problem:** The scheduler invokes a suspending progress callback under its mutex on each
start/finish. The worker reloads every item, persists WorkManager progress, and updates foreground
notifications there. It also queries cancellation from Room for each admission and copies an
ever-growing results list that this caller ignores.

**Scope:** `SequentialBatchProcessor.kt`, `ImageBatchWorker.kt`, BatchRepository/DAO cancellation
observation, scheduler tests. Context: ROADMAP US-009.

**Dependencies:** OPT-05.

**Steps:**

1. Replace per-event full snapshots with small start/finish deltas or a compact summary containing
   counts and active item positions. Do not retain/copy all completed results for the worker;
   ordered results already live in Room. Adapt remaining callers explicitly rather than preserving
   unused O (N) snapshot work.
2. Hold the scheduling mutex only while claiming an index and updating in-memory active/counter
   state. Emit to a conflated reporter after releasing it. The reporter consumes the latest state
   independently; it must not block worker admission on slow notifications.
3. Load the cancellation flag once before admission and observe the job's persisted cancellation
   field in a dedicated coroutine. Update the shared admission flag under the queue coordination
   mechanism. Route in-process cancel requests through the same gate so no new item is claimed after
   cancellation is acknowledged. Do not perform a full job query under the gate per image.
4. Create notification channels and stable PendingIntents once per worker run. Retain the initial
   foreground-service setup before work. Update ordinary notifications and WorkManager progress at
   most once per 1000 ms, only when visible values changed. Final/interrupted/cancelling transitions
   flush immediately. Do not debounce indefinitely under continuous activity.
5. Keep only the job ID in WorkManager input. Search consumers of WorkManager progress: if none need
   it independently, remove its per-item writes and use Room as the progress authority; otherwise
   send the same throttled summary. Document the result of that call-site audit.
6. Ensure reporter failures are reported but do not lose persisted item outcomes. Distinguish
   cancellation exceptions from notification service errors. On worker shutdown, flush the latest
   summary, finish reporter children, and release job-local state.

**Expected result / acceptance:** A fake slow reporting callback does not serialize image
processing. No per-event full `dao.items()` reads, growing result-list copies, or provider/Room
callbacks occur under the scheduling mutex. Virtual-time tests verify the 1000 ms ceiling plus
immediate state transitions, bounded worker count, cancellation admission ordering, and cleanup.
Recovery-critical writes remain immediate.

### OPT-07 — Observe lightweight summaries and load results on demand

**Story:** As a user, I want accurate progress and file-size savings without UI stutter as the batch
grows.

**Problem:** `BatchDao.latest()` observes the entire job/items relation. The ViewModel repeatedly
sorts/maps all items on Main and writes preferences during active snapshots. ProcessingScreen
repeatedly counts growing result lists; selection/results totals repeat scans.

**Scope:** BatchJob/DAO/migrations, worker/publisher transition helper, ViewModel/UiState,
ProcessingScreen, ResultScreen, selection UI, preferences. Context: SPEC §§6.5/8; ROADMAP US-010.

**Dependencies:** OPT-06.

**Steps:**

1. Add a compact `BatchSummary` projection: total, saved, unchanged, failed, cancelled, active
   count, state, saved-original-byte sum, saved-output-byte sum, and unknown-original-size count.
   Persist counters on the job and update them in the same Room transaction as item transitions. A
   compare-old-state/transition check prevents double counting on retries. Reconcile counters from
   items once at resume/migration, not on every UI tick.
2. Define completed as SAVED+UNCHANGED+FAILED, preserving current exclusion of CANCELLED. Define
   remaining as total-completed-cancelled; display cancelled separately. Savings include SAVED items
   only. Exclude unknown-size pairs from both percentage numerator/denominator and show an
   incomplete-total indicator. Use verified written bytes for output totals.
3. Observe latest job summary separately from active item names/positions. Add an index suitable for
   `(jobId, state, position)` active queries. Do not use the full `StoredBatch` relation for active
   progress. Compute summary data off-main and publish ordinary UI progress at most every 250 ms,
   with immediate state changes/final values.
4. Restore job configuration, folder labels, and selection metadata once when job ID changes. Keep
   stable selection lists while processing, rather than recreating all ImageItems for each item
   transition. Restore preferences once outside StateFlow update lambdas, use one editor operation,
   and avoid writes when values are unchanged.
5. Load terminal result rows ordered by position only when ResultScreen is shown. Use fixed pages of
   100 via DAO queries and LazyColumn; no new paging dependency is required. Query aggregate and
   per-folder summaries separately so totals do not depend on loaded pages. For a terminal job,
   reuse loaded pages across harmless recompositions.
6. Remove `results.count`/`sumOf` from ProcessingScreen; use summary fields. Cache selection totals
   when selection changes. Preserve previous-batch dismissal, reopening an active job, output
   open/share actions, folder grouping, and completion navigation exactly once.
7. Make original-size lookup reuse authoritative values from staging/counting where available. Treat
   picker/provider sizes as hints until processing confirms them. Remove the unused initial
   `getFileSize()` call from the lossless dispatch path. Do not reread image contents merely to
   update a displayed counter.

**Expected result / acceptance:** Active UI updates materialize summary rows and at most the
active-item set, not 4,000-item graphs. Totals match a final database aggregation after mixed
success/failure/unchanged/cancelled/retry cases. Recomposition does not reread files or rewrite
preferences. Paging retains position order and full-batch totals. Reopening/recreation does not
double-count or relaunch completion navigation.

### OPT-08 — Reduce peak bitmap memory and transform passes

**Story:** As a user converting large photos, I want fewer temporary bitmaps without changing
orientation, aspect ratio, or transparency handling.

**Problem:** REDUCE_SIZE can hold decoded, oriented, scaled, and composited bitmaps at different
overlapping points. JPEG background compositing allocates another full target bitmap even for opaque
input.

**Scope:** `DefaultImageProcessor.kt`, image geometry policies and instrumentation fixtures.
Context: SPEC §6.2; ROADMAP US-007.

**Dependencies:** OPT-07; retain OPT-02 metadata contract.

**Steps:**

1. Retain bounds-first sampling and the existing mapping of oriented target dimensions back to
   encoded axes. Compute dimension products with checked Long arithmetic before allocations.
2. Replace separate orientation/scale intermediates with one render into a target-sized bitmap using
   a composed matrix for orientation, reflection, translation, and scaling. For no transform and no
   compositing, reuse the decoded bitmap. For JPEG requiring compositing, fill the target with the
   chosen background and draw the transformed source in the same pass.
3. If decoded input is known opaque (`hasAlpha()==false`), omit background compositing. Do not scan
   every pixel solely to prove opacity; `hasAlpha()==true` remains the conservative compositing path
   even if some such images happen to be opaque.
4. Preserve ARGB_8888/color behavior; do not switch to RGB_565 or change color-space handling as a
   memory shortcut. Use the same filtering intent as the existing transform and no upscaling.
5. Centralize bitmap ownership so each allocated bitmap is released once when no longer needed,
   including transform/encode exceptions. Keep the decoded bitmap alive only through the render;
   keep the target only through encode. Do not add a global bitmap pool without independent evidence
   and a separate ownership design.
6. Document that combining resampling operations can produce small pixel differences in REDUCE_SIZE.
   Validate exact orientation/dimensions and visual fidelity with the deterministic fixture checks
   below; do not require encoded-byte identity from lossy codecs.

**Expected result / acceptance:** Transform peak is bounded by the decoded and target bitmaps rather
than decoded+oriented+scaled intermediates. Opaque JPEG conversion does not allocate a bitmap solely
for background fill. Tests use labeled asymmetric images for all eight orientations; assert mapped
corner markers, exact dimensions, no upscaling, no stretching, and alpha/background behavior. For
normal-orientation resize, compare pre-encode output against the existing single-scale reference;
for combined rotations compare against a one-pass reference with the same sampling/filtering. Memory
measurements show the removed intermediates on rotated/resized fixtures.

### OPT-09 — Introduce memory-aware admission with a default of two workers

**Story:** As a user running 4,000 images, I want sustained processing without memory spikes from
several large images starting together.

**Problem:** A configured count of 4 or 8 is independent of decoded dimensions, encoder memory, and
thermal conditions. A 5 MB compressed image can require multiple approximately 46 MiB bitmaps at 12
MP. Dispatchers.IO is not a memory budget.

**Scope:** Defaults/limits, scheduler, processor bounds/admission interface, worker/UI active-count
reporting, memory policy tests. Context: ROADMAP US-009; explicit new-job default change from SPEC
§6.4.

**Dependencies:** OPT-08.

**Steps:**

1. Make 2 the single source of truth for new-job default concurrency. Remove divergent copies of
   default 4 while retaining any compatibility constants as aliases. Preserve the supported
   configured range 1–8 and the concurrency of migrated/resumed jobs. Fix the 3840 comment without
   changing its value.
2. Interpret configured concurrency as an upper limit, not a count of dedicated threads or
   guaranteed simultaneous decodes. Change UI wording to “Up to N concurrent images” and display
   actual active count from the summary. Do not show the configured number as always active.
3. Inspect bounds/format/orientation before memory-heavy admission. Limit concurrent header
   inspections to configured concurrency. Keep queue ordering by position; never decode all selected
   images to estimate memory.
4. Add one shared weighted admission controller for all image backends. Hold the item reservation
   through its processing/post-optimization phase and release in `finally` before publication I/O
   when no bitmap/native encoder resources remain. Publication remains bounded by the configured
   worker pool; do not build an unbounded queue of prepared files.
5. Start with conservative, testable estimates: REDUCE_SIZE uses
   `4 * (sampledDecodePixels + 2 * targetPixels) + 32 MiB`; add a WebP post-pass estimate and
   reserve the maximum of the two phases, not their sum because phases run sequentially. WebP
   lossless uses `4 * 3 * sourcePixels + 32 MiB`; JPEG coefficient optimization uses
   `16 * sourcePixels + 32 MiB`; streaming PNG uses 32 MiB plus explicitly bounded metadata buffers.
   Use checked Long arithmetic. These are admission estimates, not promises of allocator usage;
   calibrate upward from measured native peaks before increasing concurrency.
6. Define initial total processing budget as
   `min(Runtime.maxMemory()/2, ActivityManager.MemoryInfo.availMem/10)` sampled before admissions;
   keep at least half the app heap ceiling outside processing estimates. Use both Java and
   native/PSS metrics for validation. A lower observed budget blocks new admissions until
   reservations drain; never revoke resources from an active image.
7. Effective parallelism is at most configured concurrency, `max(1, availableProcessors()-1)`, and
   the memory budget. If one item estimate exceeds the total budget, record a per-file memory-limit
   failure before decode; do not silently resize or bypass the budget because only one worker is
   active. Include dimensions and the smaller-dimension recommendation in the user-facing error
   without technical allocator details.
8. Use Android thermal status callbacks on supported API levels. At SEVERE or above admit at most
   one image; at MODERATE admit at most two. Apply lower limits only to future admissions. Restore
   the normal ceiling after thermal status remains below MODERATE for 30 seconds; unregister
   callbacks on completion. Do not use blocking sleeps or change quality to manage heat.
9. Keep an OOM cleanup path as a final safeguard, not the admission strategy. A memory-pressure/OOM
   event lowers subsequent admissions to one and is recorded; do not automatically retry replacement
   or repeat an encode without reconciling its durable state.
10. In OPT-15 compare configured 1/2/4 with the admission estimate and actual active count. Retain
    default 2 unless benchmark evidence meets the selection rule there. Any estimate adjustment must
    be explicit, tested, and documented; never infer a higher budget from compressed file size
    alone.

**Expected result / acceptance:** Reservations never exceed the policy budget; cancellation wakes
queued admissions; all reservations release on success/error/interruption. Tests cover oversized
input, arithmetic overflow, lowered thermal/budget limits, mixed image sizes, and manual limit 1.
Measured memory includes native encoder allocations. A 4,000-image batch does not retain 4,000
bitmaps, and the UI distinguishes configured from active concurrency.

### OPT-10 — Batch folder metadata queries and deduplicate permission retention

**Story:** As a user selecting large nested folders, I want selection and preparation to finish
without thousands of redundant metadata calls.

**Problem:** `scanDirectory()` uses `DocumentFile` property calls for
directory/file/type/name/length on each child. `BatchRepository.retain()` repeats tree-grant
acquisition and scans persisted grants for individual images, often under the same tree.
`addImages()` fetches metadata before trimming to the selection limit.

**Scope:** ViewModel folder/direct selection, feature-local document metadata reader,
BatchRepository retention, folder/import tests. Context: ROADMAP US-003/004.

**Dependencies:** OPT-09.

**Steps:**

1. Query children using `DocumentsContract.buildChildDocumentsUriUsingTree` and one projection of
   document ID, display name, MIME type, size, and flags. Determine directory status from MIME type.
   Construct child document URIs from IDs and preserve the exact parent document URI separately from
   the grant root.
2. Preserve breadth-first nested traversal, selected-folder settings, format matching, maximum
   count, deduplication, and existing image-type acceptance behavior. Stop querying further
   directories once enough unique eligible images fill the remaining selection capacity. Close each
   cursor before advancing.
3. Missing columns/null values remain explicit. If a provider rejects the projection, use a measured
   compatibility fallback for that directory; do not perform a full duplicate scan before deciding
   to fall back. Record fallback counts in debug metrics.
4. Query size with the other metadata even when displaying totals. When size filtering is enabled
   and size is unknown, make one targeted size/stat query; if still unknown, exclude that item from
   the size-filtered selection and report the excluded-unknown count. Without a size filter, include
   it and display unknown size. This replaces misleading zero-size treatment explicitly.
5. Deduplicate direct-picker URIs and apply remaining capacity before per-URI metadata retrieval.
   For arbitrary individual URIs without a shared child query, use one name/size query and the
   minimum necessary MIME lookup. Do not probe 10,000 inputs to keep only 4,000.
6. At submission, normalize grant identity to the tree root and cache retained grant modes per
   distinct grant URI. Reuse an existing sufficient grant; acquire only missing read/write modes
   once. Keep actual item parent URI distinct from permission identity. Inspect persisted
   permissions once initially and refresh after a grant change, not per file.
7. Permission caching does not suppress actual open/write failures. If retaining input access fails,
   use existing durable staging; if destination access is insufficient, fail preparation with the
   relevant folder error. Do not ask for broad filesystem permissions.

**Expected result / acceptance:** Normal folder enumeration uses one child query per visited
directory and no separate per-child metadata queries. Retention work scales with distinct grants,
not item count. Tests cover multiple/nested folders, missing columns, unknown sizes with/without
filtering, duplicate picker results, limit truncation, read-only grants, and revoked permissions.
All current folder-selection preferences survive.

### OPT-11 — Bound temporary storage and clean up by recovery ownership

**Story:** As a user preparing about 20 GB of inputs, I want the app to avoid unnecessary
duplication and release space as files complete.

**Problem:** Non-persistable inputs are all staged before enqueue and retained to batch end.
Existing cleanup is mostly directory-wide, which is unsafe once unresolved replacement recovery
files exist. A batch can exhaust private storage before encoding starts.

**Scope:** BatchRepository submit/staging/cleanup, candidate ownership, worker/recovery, staging
tests. Context: SPEC §8.4.

**Dependencies:** OPT-10 and OPT-05 ownership rules.

**Steps:**

1. Keep the guarantee that transient input access is converted to durable staging before enqueue. Do
   not replace it with a rolling read from transient URIs that may become inaccessible after process
   death. Prefer retained tree grants through OPT-10 to avoid this path.
2. Stream each required input once into a private `.partial` file, count/hash it, close/sync, then
   adopt its final staging name. Record actual original bytes from the completed copy. Honor
   cancellation between buffers and never enqueue a row pointing to a partial input.
3. Before staging known-size inputs, compare their total plus a reserve against
   `StatFs.availableBytes`. Reserve is
   `max(256 MiB, 2 * sum of the largest configured-concurrency known input sizes)`; this is a
   startup guard, not a bound on output/raw image expansion. For unknown sizes, check remaining free
   bytes after each 64 MiB staged and before each new file. If the reserve is crossed, abort
   submission, report insufficient private storage, and remove only this unsubmitted job's owned
   files.
4. On every committed terminal item, remove its staged input and scratch candidate only if no
   publication/rollback state still references them. SAVED cleanup happens after the verified
   commit; UNCHANGED cleanup happens after its durable state; FAILED cleanup requires no unresolved
   destructive work; CANCELLED cleanup applies only to never-started or reconciled items.
5. Recovery backup may reuse a staged original by reference. Do not delete it twice or delete it on
   cancellation while restoration is pending. Reconcile ownership from database state on startup.
6. Replace unconditional end-of-job recursive deletion with a cleanup routine that first confirms
   all artifacts are unreferenced and publication recovery is settled. Stale cache cleanup excludes
   all database-referenced candidates/backups regardless of age. Clean abandoned unsubmitted/partial
   files only under the app-owned staging root after checking they are not referenced by a job.
7. Surface preparation progress as input count/bytes staged at the same throttled UI cadence; do not
   reload the future full batch for each buffer. Keep no full image byte arrays in memory.

**Expected result / acceptance:** Persistent-grant inputs use no full input staging copy; transient
inputs remain restart-safe. Completed items release space progressively. Low-space/cancel/restart
tests never enqueue partial input, remove recovery evidence, or touch files outside the private
staging root. Report both the approximately 20 GB worst-case input-staging requirement and
additional output/backup reserve honestly.

### OPT-12 — Cache destination directories, names, and identities

**Story:** As a user choosing subfolder/custom-folder output, I want the last images in a batch to
publish as efficiently as the first.

**Problem:** Non-replacement publication enumerates every existing output, queries each name, calls
findFile again, and scans identities for each image under one creation mutex.
`optimizedOutputName()` scans the entire Set with `any`, including collision retries. This can
produce quadratic work.

**Scope:** Publisher, shared job-scoped directory index introduced for replacement,
`OutputNaming.kt`, naming/provider tests. Context: ROADMAP US-008.

**Dependencies:** OPT-11; build on OPT-05 parent index and OPT-10 projected queries.

**Steps:**

1. Resolve each SUBFOLDER/CUSTOM_FOLDER destination and create/find its Optimized child once per
   job. Key the cache by provider authority plus actual parent document ID, not only the tree-grant
   URI. Preserve current destination selection precedence and nested-folder behavior.
2. Populate a folder index in one child query: normalized-name to document identity, and a hash set
   of document identities. Use `lowercase(Locale.ROOT)` for normalization and add Unicode/case
   collision tests. Maintain a next-suffix counter per normalized stem/extension so repeated
   basenames do not restart at suffix 1.
3. Reserve a name and durable reservation record before external create; use a per-folder mutex for
   reservation/index mutation. Record returned actual URI/name immediately. Update the index for
   creates/deletes/renames. File content copy and readback run outside this mutex.
4. Replace linear `existingNames.any` and `before.none(sameDocument)` with indexed name and
   document-ID checks. Never create an output for an UNCHANGED lossless result. Maintain the
   established `_optimized` suffix behavior for these destination modes.
5. On resume, rebuild the index once and reconcile persisted reservations/actual target URIs. An
   uncertain create-before-checkpoint orphan is not automatically owned by name; preserve it unless
   durable evidence proves ownership. Choose another reserved name or report collision rather than
   overwriting unknown content.
6. External folder changes can invalidate a cache. On creation conflict, requery that folder once
   and retry one reservation with the refreshed index. If a provider returns a pre-existing URI or a
   suspicious nonempty new target, fail without writing. Preserve existing source-alias protection.
   Record compatibility fallbacks and do not claim collision checks are atomic across unrelated
   apps.
7. If a provider cannot safely establish new-document identity under the cached path, use the
   conservative existing pre-create enumeration for that provider and report it in benchmark
   results. Do not silently drop ownership checks to meet the query target.

**Expected result / acceptance:** On the normal test/local provider, initial indexing scales with
existing entries plus O (1)-average work per created output; there is no per-item whole-folder
enumeration. A 4,000-output counter test proves this without requiring a 4,000-image encode. Tests
cover same-name concurrent items, case variants, nested destinations, external collisions, source
aliases, interruption after create, resumed reservations, and documented conservative fallback.

### OPT-13 — Validate WebP efficiently

**Story:** As a user of lossless WebP or the optional post-pass, I want validation to remain exact
without millions of individual pixel API calls.

**Problem:** `verifyBitmapPixels()` reads two pixels per position through bitmap accessors;
successful candidates are decoded/compared twice. Larger candidates are validated before being
discarded. Bitmap working memory is substantial.

**Scope:** `WebpLosslessOptimizer.kt`, candidate adapter, lossless tests. Context: ROADMAP US-006
and EN-004.

**Dependencies:** OPT-12 and OPT-04 validation contract.

**Steps:**

1. After finalized candidate creation and structural checks, test strict-smaller size before
   allocating a validation bitmap or comparing metadata payloads. An equal/larger candidate can
   return UNCHANGED without semantic validation because it will never be published. Never bypass
   source-format validation or publish an unchecked candidate.
2. Decode a smaller candidate once for semantic validation. Compare using two reusable `IntArray`
   row buffers and `getPixels`, one row at a time; stop at the first mismatch. Preserve current
   integer pixel equality semantics, including alpha. Do not replace equality with a perceptual
   threshold.
3. Validate required metadata/container constraints once on that candidate. Retain
   animation/ICC/unknown-chunk rejection and the existing 16,000,000-pixel source limit. Do not
   expand format support as part of this optimization.
4. Release original/oriented/validation bitmaps as soon as the comparison/encoding phase no longer
   needs them. Charge all overlapping allocations to OPT-09 admission.
5. With OPT-04, return the validated artifact and encoded digest. A later transfer is verified by
   encoded byte identity and one destination readback, not a second decode/pixel comparison. Public
   URI-output entry points use the same validated-artifact adapter rather than omitting write
   verification.

**Expected result / acceptance:** No nested per-pixel bitmap getter loop remains. Each accepted
candidate has one pixel comparison; equal/larger candidates skip it. Tests deliberately alter one
pixel/alpha value and metadata, cover orientation and unsupported containers, and corrupt the
destination after write. All are detected by the appropriate remaining validation boundary. Report
memory and runtime changes separately from encoding cost.

### OPT-14 — Stream PNG scanlines and evaluate compression effort

**Story:** As a user processing PNGs, I want bounded memory and less scratch-disk traffic while
retaining exact scanline data.

**Problem:** PNG optimization concatenates IDAT into a file, inflates raw scanlines to another file,
rereads them for deflation, and repeats temporary raw-file creation during verification.
Deflater.BEST_COMPRESSION can cost extra CPU for small savings.

**Scope:** `PngLosslessOptimizer.kt`, PNG fixtures/tests, performance report. Context: ROADMAP
US-006 and EN-004.

**Dependencies:** OPT-13 and OPT-04 validation contract.

**Steps:**

1. Implement a bounded input stream over the indexed IDAT payload ranges in the source file,
   preserving source chunk CRC checks. Feed it to InflaterInputStream without first concatenating a
   complete compressed-data file.
2. Stream inflated bytes through one 64 KiB buffer, updating SHA-256 and a Long byte count, directly
   into DeflaterOutputStream for the replacement IDAT stream. Keep a compressed candidate-zlib file
   only where the existing container writer needs its final length. Never materialize the full raw
   scanline stream in memory or on disk.
3. Derive the exact expected raw byte count for ordinary and Adam7 images before inflation using
   checked arithmetic and existing legal bit-depth/color-type checks. Abort if decoded bytes exceed
   that bound, or if EOF yields fewer bytes. This prevents a malformed stream from expanding
   indefinitely.
4. Rewrite candidate validation as streaming inflation plus digest/count comparison, with no raw
   temporary file. Validate the candidate once; subsequent local/publication transfer checks use
   encoded identity under OPT-04. Retain PNG chunk CRC, dimensions, metadata, unsupported-animation,
   and strict-smaller requirements.
5. Release Inflater/Deflater/native resources on success, exceptions, and cancellation; close all
   range streams. Reuse per-operation buffers. Keep cancellation checkpoints between blocks without
   interrupting an already-destructive publication path.
6. Benchmark level 6 versus current level 9 on the same fixtures, without changing filters, pixel
   data, or metadata. Keep level 9 unless three screening repetitions show median PNG stage time at
   least 15% lower with level 6 and aggregate candidate bytes no more than 2% larger, with no
   correctness failures. If those thresholds pass, centralize level 6 as the new compression effort
   and document the ratio tradeoff. This decision does not change lossy quality 85.
7. If no target-device measurements are available, ship the streaming refactor with level 9 and
   leave only the level-selection acceptance pending; do not guess that level 6 is faster enough.

**Expected result / acceptance:** No temporary raw scanline file is created by recompression or
validation. Buffer/metadata memory is bounded independently of decompressed image size. Tests cover
multiple IDAT chunks, tiny images, palette/grayscale/RGB/RGBA, valid bit depths, Adam7, CRC errors,
truncated/excess data, and metadata preservation. Compression-level selection follows recorded
measurements and the explicit rule above.

### OPT-15 — Verify the complete workload and reconcile documentation

**Story:** As the user, I want a proven improvement for my 4,000-image replacement workflow and a
justified concurrency recommendation.

**Problem:** Faster microbenchmarks can hide larger outputs, lost EXIF, UI stalls, increasing folder
costs, memory pressure, and late-batch thermal throttling. Prior roadmap acceptance notes do not
validate this implementation.

**Scope:** All affected tests; `docs/performance-image-optimizer.md`; narrow updates to
SPEC/ROADMAP; execution log below.

**Dependencies:** OPT-01 through OPT-14, with device-dependent items explicitly pending until
measured.

**Steps:**

1. Run focused regression suites and then the integration commands in §1.3. Add no unrelated test
   rewrites. Exercise current selection settings, all destinations, both modes, saved job
   restoration, EXIF on/off, paging, cancellation, and per-file failures.
2. Use disposable source copies for all replacement tests. Compare baseline and optimized code with
   identical original fixture bytes and geometry/quality settings; do not run the second benchmark
   against the first run's already-converted output. Include both same-format overwrite and
   cross-format replacement.
3. Run primary JPEG/WebP lossy quality-85, preserve=true screening at configured 1/2/4 with SKIP and
   TRY_LOSSLESS separated in results. Compare TRY_LOSSLESS against legacy baseline for like-policy
   behavior, and SKIP against baseline as an explicit speed/size tradeoff. Record actual admitted
   concurrency as well as requested concurrency.
4. For three fresh 200-image repetitions per configuration, use median throughput. Keep default 2.
   Recommend 4 for this device/workload only if it improves median throughput by at least 10% over
   2, has zero OOM/corruption/recovery failures, stays within the measured memory envelope, and does
   not lose that throughput advantage in the endurance run. Recommend 1 if 2 violates those
   conditions; do not increase the global default merely because one JPEG-only device benefits from
   4.
5. Run a full 4,000-image corpus near 5 MB compressed per file on the selected configuration for
   both primary lossy output choices. Record actual total bytes/dimensions rather than claiming
   synthetic fixtures are exactly 5 MB. Record first/middle/last 500-image throughput,
   memory/GC/thermal trends, provider query counts, peak scratch/staging space, saved bytes, EXIF
   checks, and recovery errors. Run 8 only if admission estimates and 4-worker measurements
   establish headroom; 8 is not a required default candidate.
6. Exercise a 4,000-item mocked-processing/provider-counter run for each destination to isolate
   scheduler/database/name-index scaling from codec costs. Prove the removed full-batch reloads and
   normal-path directory scans do not return late in the batch.
7. Inject process termination/recreation and provider failures before/after each publication
   checkpoint, including cross-format verified-output/source-deletion gaps and same-format rollback.
   Distinguish simulated restart from real process death in the report. Do not use Android
   force-stop as proof of automatic WorkManager resume, because force-stop has different scheduling
   semantics.
8. Check OOM cleanup, full storage, revoked grants, deletion failure, unknown sizes, and
   cancellation during admission/processing/publication. No test may claim successful replacement
   when only output creation succeeded but the required source deletion is unresolved.
9. Record gains/regressions by stage. The expected structural gains are: zero optional pass in SKIP;
   one source EXIF parse; no duplicate metadata rewrite of a valid alternate; one semantic candidate
   validation; one final destination readback; no full-batch queries in progress callbacks; bounded
   summary cadence; O (folder count) initial directory queries on the normal cached path; no PNG raw
   scratch files. Do not set an invented universal wall-time speedup percentage.
10. Update only the affected SPEC/ROADMAP statements: new-job default 2, persisted processing
    policy, metadata contract, summary/reporting behavior, current recovery phases, and completed
    performance work. Explicitly qualify older automatic-post-pass and historical test-count claims.
    Leave unrelated Files-screen, new-codec, SDK, and app-wide preference work out of scope.
11. In the performance report, state the tested device/provider, final recommended concurrency,
    output-size tradeoff, limitations of arbitrary SAF replacement, and any unmeasured scenarios. If
    device access or capacity prevents endurance testing, mark that gate pending and preserve the
    conservative default; do not declare full workload acceptance complete.

**Expected result / acceptance:** Builds and relevant tests pass; the primary workflow preserves
supported EXIF and quality/geometry settings; performance evidence separates algorithmic
improvements from policy changes. The final recommendation is based on sustained measurements, and
no plan checkbox claims unperformed device validation.

## 3. Coverage of the original recommendations

| Recommendation                                                                 | Implemented by                 |
|--------------------------------------------------------------------------------|--------------------------------|
| Destination resolution cache; avoid repeated enumeration/findFile              | OPT-05, OPT-10, OPT-12         |
| Hash-set names/identities and efficient collision suffixes                     | OPT-12                         |
| Move progress work out of scheduling lock; remove unused growing snapshots     | OPT-06                         |
| Throttle notifications/UI; cache notification setup                            | OPT-06, OPT-07                 |
| Avoid per-item cancellation queries while preserving durable cancellation      | OPT-06                         |
| Remove duplicate SAVED write; retain publication checkpoints                   | OPT-05, OPT-07                 |
| Lightweight Room summaries, incremental byte/count totals, lazy results        | OPT-07                         |
| Avoid repeated preference restoration and list creation on Main                | OPT-07                         |
| Cached EXIF definitions, one metadata read, preservation flag, fewer saves     | OPT-02                         |
| Optional final optimization with measured speed/size tradeoff                  | OPT-03                         |
| Owned file artifacts, fewer local copies, hash finalized bytes once            | OPT-04                         |
| Exact candidate validation plus final destination readback                     | OPT-04, OPT-05, OPT-13, OPT-14 |
| WebP bulk pixel comparison and earlier no-savings rejection                    | OPT-13                         |
| PNG streaming and compression-effort comparison                                | OPT-14                         |
| Opaque JPEG compositing fast path and combined bitmap transform                | OPT-08                         |
| Default 2, memory-aware worker admission, native memory and thermal checks     | OPT-09, OPT-15                 |
| Bulk selection metadata, grant deduplication, cap before direct-picker queries | OPT-10                         |
| Reuse authoritative file sizes instead of extra reads                          | OPT-04, OPT-07, OPT-10, OPT-11 |
| Avoid unnecessary full-input staging and release completed staged inputs       | OPT-10, OPT-11                 |
| Early/middle/late batch benchmarks and safety regression evidence              | OPT-01, OPT-15                 |

## 4. Execution log and completion checklist

Update this log during implementation. For each story, append: changed files; observable behavior;
migrations; exact test commands/outcomes; measured counters/timings; and remaining acceptance
checks. Preserve failed measurements as evidence with their cause. A code-complete story with
pending required device checks stays unchecked.

- [ ] OPT-01 — Baseline and metrics
- [ ] OPT-02 — EXIF
- [ ] OPT-03 — Post-optimization policy
- [ ] OPT-04 — Owned candidates
- [ ] OPT-05 — Replacement publication/recovery
- [ ] OPT-06 — Scheduling/reporting
- [ ] OPT-07 — Summary/UI/size tracking
- [ ] OPT-08 — Bitmap transforms
- [ ] OPT-09 — Concurrency/admission
- [ ] OPT-10 — Selection/grants
- [ ] OPT-11 — Staging/cleanup
- [ ] OPT-12 — Destination indexes
- [ ] OPT-13 — WebP validation
- [ ] OPT-14 — PNG streaming
- [ ] OPT-15 — Full verification and documentation

Initial entry (2026-11-05): Plan created against the current working tree, including existing
uncommitted changes. Source and historical documents reviewed. No optimization implementation,
build, device benchmark, or replacement operation was performed as part of writing this plan.
