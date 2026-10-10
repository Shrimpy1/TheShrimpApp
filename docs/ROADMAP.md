# The Shrimp App — Implementation Roadmap & Backlog

**Source:** `SPEC.md`  
**Purpose:** Working implementation plan for personal development, not a formal SDD.  
**Status convention:** `[ ]` not started · `[x]` done · `[-]` intentionally deferred

This document turns the app specification into an ordered roadmap and actionable backlog. Keep
`SPEC.md` as the source of truth for product requirements; use this file to track implementation. If
scope changes, update the spec first and then adjust this backlog.

## 1. Terms used in this plan

- **Roadmap / milestone:** A stage of work that leaves the app in a coherent, testable state.
- **Epic:** A large capability, such as image processing or file management.
- **User story:** A user-facing outcome, commonly written as “As a user, I want … so that …”.
- **Acceptance criteria (AC):** Observable conditions that determine whether a story is complete.
- **Tasks / subtasks:** Concrete implementation work needed to deliver a story.
- **Enabler:** Technical setup or refactoring needed to support later stories, even if it has no
  direct user-facing outcome.
- **MVP:** The first usable local version covering the core requirements in `SPEC.md`.

This is a sequential backlog, not a commitment to use Scrum sprints. Complete stories in dependency
order; change the order only when it makes implementation simpler.

---

## 2. Roadmap at a glance

| Stage                                     | Outcome                                                                                              | Depends on |
|-------------------------------------------|------------------------------------------------------------------------------------------------------|------------|
| **M0 — Project foundation**               | App builds, runs on the API 36 phone, and is tracked in Git                                          | —          |
| **M1 — App shell and state architecture** | Single Activity, Compose Material 3, navigation, Hilt, and ViewModel/state patterns work             | M0         |
| **M2 — Image and folder input**           | User can select images or authorize a folder; the app can inspect and list supported files           | M1         |
| **M3 — Single-image processing**          | One image can be resized, converted, compressed, and saved without modifying the original            | M2         |
| **M4 — Batch workflow**                   | Multiple images process sequentially with progress, cancellation, per-file results, and size savings | M3         |
| **M5 — File management**                  | User can browse an authorized folder and perform supported create, rename, and delete operations     | M2         |
| **M6 — Preferences and UX refinement**    | Useful defaults persist; empty, error, and completion states are polished                            | M3–M5      |
| **M7 — Verification and local release**   | Core acceptance criteria pass on the target phone and a local APK can be installed                   | M0–M6      |

M4 and M5 can be worked on independently after M2/M3 foundations are ready. M6 can be done
incrementally rather than held until the end.

---

## 3. Definition of done

A story is done when all applicable conditions are met:

- [ ] Acceptance criteria are verified on the target Android phone or with a suitable automated
  test.
- [ ] Loading, empty, failure, and permission-denied states are handled where relevant.
- [ ] No long-running decoding, encoding, or file I/O blocks the main thread.
- [ ] Original files remain unchanged unless the user explicitly requests a destructive operation.
- [ ] New code follows the existing package boundaries and uses constructor injection where
  appropriate.
- [ ] The app builds successfully and no known regression is introduced.
- [ ] The checklist for the story is updated here.

---

# 4. Prioritized product backlog

## EPIC E0 — Project foundation

### EN-001 — Create and configure the Android project

**Priority:** P0 · **Type:** Enabler · **Milestone:** M0

Tasks:

- [x] Create the Android Studio project using Kotlin and the Jetpack Compose template.
- [x] Set `minSdk = 30`; initially use `compileSdk = 36` and `targetSdk = 36`, subject to the
  installed stable toolchain and template.
- [x] Keep the project in a single application Gradle module.
- [x] Verify the Gradle wrapper and Kotlin DSL build files are committed.
- [x] Keep dependency versions in the generated version catalog where available.
- [x] Build and launch the template app on the API 36 phone.

Acceptance criteria:

- The project syncs and builds without errors.
- The app installs and launches on the intended phone.
- SDK values and module layout match `SPEC.md`.

### EN-002 — Set up Git

**Priority:** P0 · **Type:** Enabler · **Milestone:** M0

Tasks:

- [x] Initialize Git from the project root (the directory containing `settings.gradle.kts`) or use
  Android Studio's Git integration.
- [x] Review `.gitignore`; exclude build outputs and machine-specific files such as
  `local.properties`.
- [x] Make the initial commit.
- [x] Create an empty remote repository and push the initial branch, if using a remote.
- [x] Commit `SPEC.md` and `ROADMAP.md` as project documentation.

Acceptance criteria:

- Source and documentation are version-controlled.
- Local SDK paths, generated build output, and secrets are not committed.

### EN-003 — Establish baseline dependencies

**Priority:** P0 · **Type:** Enabler · **Milestone:** M0–M1

Tasks:

- [x] Confirm the Compose Material 3 setup generated by Android Studio.
- [x] Add Navigation Compose.
- [x] Configure Hilt's Gradle plugin, dependencies, and compatible Kotlin/KSP setup according to the
  current official setup instructions.
- [x] Add lifecycle-aware Compose state collection support.
- [ ] Add AndroidX ExifInterface and DocumentFile when their features are implemented.
- [ ] Add Preferences DataStore when persisted preferences are implemented.
- [x] Avoid adding libraries before a concrete use case needs them.

Acceptance criteria:

- Gradle sync succeeds after each dependency change.
- Dependency versions are compatible with the project's Android Gradle Plugin and Kotlin setup.

---

## EPIC E1 — App shell, navigation, and state architecture

### US-001 — Open the app and navigate between tools

**Priority:** P0 · **Milestone:** M1

**Story:** As a user, I want a home screen with available tools so that I can open the utility I
need.

Tasks:

- [ ] Create `TheShrimpApp.kt` and annotate the Application class with `@HiltAndroidApp`.
- [ ] Annotate `MainActivity` with `@AndroidEntryPoint`.
- [ ] Set up the app theme using Material 3.
- [ ] Create the navigation host and initial destinations: Home, Image Optimizer, and Files.
- [ ] Add a simple Home screen with an entry for Image Optimizer and Files.
- [ ] Verify forward navigation and system back behavior.
- [ ] Keep a single Activity; destinations are Compose screens.

Acceptance criteria:

- App opens at Home.
- User can open Image Optimizer and Files, then return using back navigation.
- Navigation does not rely on a destination reaching into another destination's internal UI state.

### US-002 — Manage screen state using MVVM and Hilt

**Priority:** P0 · **Milestone:** M1

**Story:** As a developer, I want screen state and dependencies managed consistently so that
features remain maintainable and testable.

Tasks:

- [ ] Create the Image Optimizer UI-state data class.
- [ ] Create a Hilt-enabled ViewModel using `@HiltViewModel` and constructor injection.
- [ ] Expose immutable screen state through `StateFlow` backed by a private `MutableStateFlow`.
- [ ] Collect state in Compose using `collectAsStateWithLifecycle()`.
- [ ] Use events/callbacks from UI to ViewModel; don't mutate ViewModel state directly from
  composables.
- [ ] Use `remember` for ephemeral component state and `rememberSaveable` for small restorable UI
  inputs where suitable.
- [ ] Keep Android `Context`, Activity, and View references out of the ViewModel.
- [ ] Add a Hilt provider for Android dependencies such as `ContentResolver` only where needed.

Acceptance criteria:

- A UI control can send an event to the ViewModel and render the updated state.
- Hilt constructs the ViewModel and its dependencies successfully.
- State ownership is clear; transient UI state is not unnecessarily moved into the ViewModel.

---

## EPIC E2 — Select image inputs and authorize folders

### US-003 — Select individual images

**Priority:** P0 · **Milestone:** M2

**Story:** As a user, I want to select one or more images through Android's picker so that I can
choose exactly what to optimize.

Tasks:

- [ ] Integrate Android's Photo Picker using the Activity Result APIs.
- [ ] Accept multiple image `Uri` values.
- [ ] Represent queued inputs as lightweight models; do not keep decoded full-size bitmaps in UI
  state.
- [ ] Display selected count and allow removing an item from the queue.
- [ ] Handle an empty picker result and repeated selection without duplicating items unexpectedly.
- [ ] Read content through `ContentResolver`; never infer a real filesystem path from a `Uri`.

Acceptance criteria:

- User can select multiple images and see the resulting queue.
- User can remove an image from the queue.
- Canceling the picker leaves the queue in a valid state.

### US-004 — Select and inspect a source folder

**Priority:** P0 · **Milestone:** M2

**Story:** As a user, I want to authorize a folder so that I can choose images from a directory
rather than selecting every file individually.

Tasks:

- [ ] Integrate `ACTION_OPEN_DOCUMENT_TREE` / the corresponding SAF directory picker flow.
- [ ] Take persistable URI permissions when offered and appropriate.
- [ ] Retain the authorized tree URI and handle missing or revoked permission later.
- [ ] Use `DocumentFile` to list folder contents.
- [ ] Identify supported JPEG, PNG, and WebP inputs using available MIME type/metadata and a safe
  fallback where necessary.
- [ ] Initially define folder processing as direct children only; treat recursive subfolder
  processing as a later decision unless explicitly required.
- [ ] Display available filename, MIME type, size, and dimensions; handle unknown metadata.

Acceptance criteria:

- User can authorize a folder and see its accessible contents.
- Only supported image inputs are queued for image processing.
- A denied or invalid folder permission results in a useful error and recovery path.

---

## EPIC E3 — Image processor and safe output

### US-005 — Configure image processing options

**Priority:** P0 · **Milestone:** M3

**Story:** As a user, I want to configure output dimensions, format, and quality so that the output
fits my needs.

Tasks:

- [ ] Model `ImageProcessingOptions` with maximum dimension, output format, quality, and JPEG
  background color.
- [ ] Offer maximum-dimension presets of 1280, 1920, 2560, and 3840 px, plus custom input.
- [ ] Default lossy quality to 85; constrain accepted values to 1–100.
- [ ] Offer JPEG, WebP lossy, WebP lossless, and PNG output.
- [ ] Make the quality control apply only to formats for which it is meaningful; don't imply it
  controls PNG lossless compression in the same way.
- [ ] Validate custom values and reject invalid options before processing.

Acceptance criteria:

- User can change processing settings.
- The selected options are passed to the processor as a coherent model.
- Invalid settings cannot start a batch.

### US-006 — Resize and convert one image

**Priority:** P0 · **Milestone:** M3

**Story:** As a user, I want to resize and convert an image so that I can reduce its dimensions and
file size.

Tasks:

- [ ] Create an `ImageProcessor` independent of Compose and screen classes.
- [ ] Inspect image bounds before decoding the full bitmap.
- [ ] Use sampled decoding for large inputs where appropriate.
- [ ] Read EXIF orientation and ensure output orientation is visually correct.
- [ ] Calculate scale from the longest side, preserving aspect ratio and never upscaling by default.
- [ ] Implement JPEG, WebP lossy, WebP lossless, and PNG output using Android bitmap APIs.
- [ ] Composite transparent input onto the configured background when outputting JPEG.
- [ ] Make bitmap lifecycle and memory use explicit; don't retain full-resolution bitmaps beyond
  processing.
- [ ] Return structured success/failure information rather than crashing the batch for a single bad
  file.

Acceptance criteria:

- A single valid image produces output with correct dimensions and orientation.
- Aspect ratio is preserved and no-upscaling behavior works.
- JPEG output has a defined background when the input contains transparency.
- Unsupported or corrupted input produces a handled error.

### US-007 — Save output safely

**Priority:** P0 · **Milestone:** M3

**Story:** As a user, I want to choose an output location and save a new optimized file so that the
source remains safe.

Tasks:

- [ ] Implement output writing through `ContentResolver` and/or authorized `DocumentFile`
  destinations.
- [ ] Support choosing an output directory using SAF.
- [ ] Default to an `Optimized` subfolder under the source folder if creation and writes are
  supported.
- [ ] Generate output names such as `photo_optimized.webp` and resolve collisions without silent
  overwrite.
- [ ] Check stream and provider operation results; clean up incomplete outputs when possible.
- [ ] Verify the output was written and obtain actual output size.
- [ ] Keep the original untouched by default.

Acceptance criteria:

- User can save output into an authorized writable location.
- Existing files aren't silently overwritten.
- Failed writes are reported and the original remains intact.

---

## EPIC E4 — Batch processing and results

### US-008 — Process a batch with progress and cancellation

**Priority:** P0 · **Milestone:** M4

**Story:** As a user, I want to process several images in one run and see progress so that I don't
have to repeat the same operation manually.

Tasks:

- [ ] Implement sequential processing of the queue.
- [ ] Run decode, resize, compression, and file I/O off the main thread using appropriate coroutine
  dispatchers.
- [ ] Prevent accidental duplicate concurrent launches.
- [ ] Model batch status, completed count, current filename, per-file result, and cancellation
  status.
- [ ] Update observable ViewModel state as each file finishes.
- [ ] Allow cancellation between files and handle cancellation during an individual operation as far
  as practical.
- [ ] Continue after per-file failures and record those failures.
- [ ] Release references to each processed bitmap before moving to the next item.

Acceptance criteria:

- A batch processes multiple supported images sequentially.
- Progress updates without freezing the UI.
- Canceling stops future work; completed outputs remain and final state accurately reports partial
  completion.
- One input failure does not prevent later queued files from being attempted.

### US-009 — Show accurate processing results

**Priority:** P0 · **Milestone:** M4

**Story:** As a user, I want to see the actual size reduction and failures so that I can judge the
result of a batch.

Tasks:

- [ ] Record original bytes and actual output bytes for each successful image.
- [ ] Calculate per-image byte difference and percentage change.
- [ ] Calculate aggregate input/output totals using only successfully processed files.
- [ ] Handle cases where output is larger than input and avoid misleading savings claims.
- [ ] Show successful/failed counts, output dimensions/formats, and actionable error summaries.
- [ ] Provide an Android-supported way to open, share, or locate outputs where available.

Acceptance criteria:

- Reported sizes match the actual output files.
- Larger outputs are shown as larger rather than represented as savings.
- Failed and canceled items are clearly distinguished from successful ones.

---

## EPIC E5 — File management

### US-010 — Browse an authorized directory

**Priority:** P0 · **Milestone:** M5

**Story:** As a user, I want to browse an authorized directory so that I can inspect its files and
subdirectories.

Tasks:

- [ ] Build the Files screen around the selected tree URI and `DocumentFile`.
- [ ] Display the current directory and distinguish directories from files.
- [ ] Provide navigation into child directories and back to parent directories within the granted
  tree.
- [ ] Display available file metadata without assuming every provider supplies it.
- [ ] Show a useful empty state and recover when access is revoked.

Acceptance criteria:

- User can browse the granted directory tree to the extent permitted by the provider.
- Files and folders are distinguishable.
- Invalid or revoked access does not crash the screen.

### US-011 — Create directories and rename files

**Priority:** P0 · **Milestone:** M5

**Story:** As a user, I want to create folders and rename files so that I can organize outputs.

Tasks:

- [ ] Add create-directory UI with name validation.
- [ ] Create directories through `DocumentFile` where supported.
- [ ] Add rename UI with validation and duplicate-name handling where the provider supports it.
- [ ] Refresh or update listing state only after successful operations.
- [ ] Surface unsupported-operation and provider errors.

Acceptance criteria:

- Creation and rename succeed when the provider supports them.
- Failed operations leave the displayed state accurate and provide an error message.

### US-012 — Delete files after confirmation

**Priority:** P0 · **Milestone:** M5

**Story:** As a user, I want to delete a selected file intentionally so that I can clean up storage
without accidental data loss.

Tasks:

- [ ] Add an explicit confirmation step before deletion.
- [ ] Clearly identify the selected file in the confirmation UI.
- [ ] Delete through the provider API and check the returned result.
- [ ] Update the listing only after confirmed success.
- [ ] Report unsupported or failed deletion.

Acceptance criteria:

- No file is deleted merely by selecting it.
- The user must confirm the destructive action.
- Failure is visible and the listing doesn't falsely indicate success.

---

## EPIC E6 — Preferences and UX refinement

### US-013 — Remember default processing options

**Priority:** P1 · **Milestone:** M6

**Story:** As a user, I want my preferred options remembered so that I don't need to set them every
time.

Tasks:

- [ ] Add Preferences DataStore only when the preference requirements are clear.
- [ ] Persist output format, quality, max dimension, and JPEG background color.
- [ ] Load defaults as observable state and handle missing or invalid values safely.
- [ ] Keep sensitive or large image data out of preferences.

Acceptance criteria:

- Saved defaults persist after app restart.
- A fresh install or missing preference uses sensible defaults.

### US-014 — Handle all screen states clearly

**Priority:** P1 · **Milestone:** M6

**Story:** As a user, I want clear feedback for empty, loading, error, and completed states so that
I understand what the app is doing.

Tasks:

- [ ] Design empty states for no selected images and empty directories.
- [ ] Provide progress, completion, and cancellation presentation.
- [ ] Show actionable messages for permission, read, write, and processing errors.
- [ ] Disable or guard conflicting actions while a batch runs.
- [ ] Check layout on different screen sizes and font scaling.
- [ ] Add accessibility labels and sensible touch targets.

Acceptance criteria:

- Every major user flow has a clear next action or explanation when it cannot proceed.
- Processing and file-management errors are visible without log inspection.

---

## EPIC E7 — Testing, hardening, and local release

### EN-004 — Test image-processing behavior

**Priority:** P0 · **Milestone:** M7

Tasks:

- [ ] Test scaling calculations, aspect-ratio preservation, and no-upscaling rules.
- [ ] Test output naming and duplicate-name policy.
- [ ] Test size-difference calculations when output is smaller, equal, or larger.
- [ ] Test corrupt/unsupported inputs and per-file failure handling.
- [ ] Test ViewModel state transitions and cancellation behavior.

Acceptance criteria:

- Core processing rules have repeatable tests.
- Failed processing is returned as a result/error instead of unexpectedly crashing the app.

### EN-005 — Verify Android integration on the target phone

**Priority:** P0 · **Milestone:** M7

Tasks:

- [ ] Test Photo Picker selection and cancellation.
- [ ] Test SAF tree selection and retained permissions.
- [ ] Test read/write, folder creation, rename, and delete with supported providers.
- [ ] Test JPEG, PNG transparency, WebP lossy/lossless, and EXIF orientation.
- [ ] Test large images and a multi-image batch for UI responsiveness and memory use.
- [ ] Test revoked permission, unwritable folder, duplicate names, insufficient storage, and
  cancellation.
- [ ] Confirm the originals remain unchanged throughout normal processing.

Acceptance criteria:

- Core scenarios pass on the intended API 36 device.
- Storage-provider limitations and errors are handled rather than assumed away.

### EN-006 — Build a local release APK

**Priority:** P0 · **Milestone:** M7

Tasks:

- [ ] Confirm the release build configuration.
- [ ] Build a release APK using Android Studio or Gradle.
- [ ] Install the APK on the target phone and run the core acceptance checklist.
- [ ] Keep signing credentials private and out of Git.

Acceptance criteria:

- A locally installable APK is produced and runs the core workflow.
- No public Play Store release is required.

---

# 5. Recommended implementation order

Use this as the practical step-by-step sequence. Finish the indicated stories before moving forward
unless a dependency clearly allows parallel work.

1. [ ] **Project bootstrapping:** EN-001, EN-002, EN-003.
2. [ ] **Shell:** US-001 — Home and navigation destinations.
3. [ ] **Architecture:** US-002 — Hilt, ViewModel, `StateFlow`, lifecycle-aware collection.
4. [ ] **Input:** US-003 — Photo Picker and queue.
5. [ ] **Folder authorization:** US-004 — SAF tree selection and metadata.
6. [ ] **Options model/UI:** US-005 — dimensions, format, quality, transparency behavior.
7. [ ] **Processor:** US-006 — first get one image correctly processed.
8. [ ] **Output:** US-007 — choose destination and safely write one output.
9. [ ] **Batch engine:** US-008 — sequential processing, progress, cancellation, per-file status.
10. [ ] **Results:** US-009 — accurate file sizes and aggregate summary.
11. [ ] **Files screen:** US-010, then US-011 and US-012.
12. [ ] **Preferences and polish:** US-013, US-014.
13. [ ] **Hardening and local release:** EN-004, EN-005, EN-006.

A useful early vertical slice is: **select one image → resize/convert it → save a new output →
verify the resulting file**. This validates the most technically uncertain path before building the
batch UI around it.

---

# 6. MVP release checklist

The MVP is ready when all P0 stories needed for the core workflow are complete and the following are
verified:

- [ ] App builds and runs on the API 36 phone with `minSdk = 30`.
- [ ] Home, Image Optimizer, and Files are navigable within one Activity.
- [ ] Hilt constructs ViewModels and their dependencies.
- [ ] User can select image files or a SAF-authorized folder.
- [ ] Multiple JPEG, PNG, and WebP images can be processed in a batch.
- [ ] Output dimensions preserve aspect ratio and avoid upscaling by default.
- [ ] JPEG and WebP output work with the chosen settings; PNG transparency behavior is defined.
- [ ] Output is written to an authorized writable location and originals are preserved by default.
- [ ] Progress, cancellation, per-file errors, and partial success are reported accurately.
- [ ] Actual file sizes and total reduction are calculated from written outputs.
- [ ] Files can be listed, and create/rename/delete work where supported by the selected provider.
- [ ] Core offline workflow is tested on the target phone.
- [ ] A local APK can be installed and launched.

## 7. Explicitly deferred

Do not implement these as part of the first pass unless the scope changes:

- [ ] Recursive processing through all subfolders (first decide whether it is needed).
- [ ] Processing history or a database of prior runs.
- [ ] WorkManager/background processing that must survive process termination.
- [ ] Video conversion, including WebM output.
- [ ] Cloud sync, accounts, telemetry, or backend services.
- [ ] Advanced image editing or additional utility features.
- [ ] Separate Gradle modules for individual features.
