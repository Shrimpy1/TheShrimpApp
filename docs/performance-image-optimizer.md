# Performance Baseline & Measurement Report (OPT-01)

Date: 2026-11-05
Status: Infrastructure Established & Verified on Connected Device

---

## 1. Environment & Build Configuration

| Property | Value / Configuration |
|---|---|
| **Repository Revision** | Baseline commit before OPT-02 execution |
| **Connected Test Device** | Physical/Emulator ADB Device (`adb-R5CXC2GJJRK-rejRS0._adb-tls-connect._tcp`, API Level 34) |
| **SDK Configuration** | `compileSdk 37`, `minSdk 30`, `targetSdk 36` |
| **Build Variant** | `:app:connectedDebugAndroidTest` |
| **Benchmark Class** | `com.example.theshrimpapp.features.imageoptimizer.ImageOptimizerBenchmarkTest` |

---

## 2. Purpose of OPT-01

1. **Measurement Infrastructure**: Provides `PerformanceTracker` to track monotonic timing for key processing stages (bounds read, decode, transform, encode, EXIF write, post-optimization, publication copy, SAF queries, DB transitions).
2. **Benchmark Test Harness**: Provides `ImageOptimizerBenchmarkTest` and `SaveTestDocumentsProvider` to run reproducible workload benchmarks on connected devices or emulators.
3. **Comparison Baseline**: Establishes the starting metrics before applying OPT-02 through OPT-14 optimizations.

---

## 3. How to Run & View Evaluations in Later Tasks

### Command to Run On-Device Benchmark:
```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.theshrimpapp.features.imageoptimizer.ImageOptimizerBenchmarkTest
```

### Viewing Results in Logcat:
In Android Studio Logcat or ADB shell, filter by tag `ImageOptimizerBench`:
```bash
adb logcat -s ImageOptimizerBench
```

Logcat output format:
```text
BASELINE format=JPEG concurrency=1: count=12, timeMs=..., img/min=..., decodeMs=..., encodeMs=..., exifMs=..., postOptMs=..., queries=...
QUERY_GROWTH destMode=REPLACE_ORIGINAL items=10 queries=...
QUERY_GROWTH destMode=SUBFOLDER items=10 queries=...
```

---

## 4. On-Device Verification Results (OPT-01)

All 5 benchmark test cases ran and passed on the connected device (`adb-R5CXC2GJJRK-rejRS0._adb-tls-connect._tcp`):

- `testBaselineJpegAndWebpConcurrency1_2_4` — **PASSED**
- `testKeepOriginalSizeVsResize` — **PASSED**
- `testQueryCountGrowthReplacementVsSubfolder` — **PASSED**
- `testFailureInjectionRecovery` — **PASSED**
- `testEnduranceProcedure4000ImagesHarness` — **PASSED**
