package com.example.theshrimpapp.features.imageoptimizer.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.theshrimpapp.features.imageoptimizer.domain.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class OutputFolder(val uri: Uri, val name: String)

sealed interface SaveImageResult {
    data class Saved(val uri: Uri, val name: String, val details: ImageProcessingResult.Success) : SaveImageResult
    data class Unchanged(val reason: String) : SaveImageResult
    data class Failed(val error: String) : SaveImageResult
}

class ImageOutputSaver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val processor: ImageProcessor
) {
    suspend fun selectFolder(uri: Uri): OutputFolder = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The current grant can still be usable for providers without persistence.
        } catch (_: UnsupportedOperationException) {
            // Persistence is optional; access below is checked independently.
        }
        folder(uri).let { OutputFolder(it.uri, it.name ?: "Selected output folder") }
    }

    private fun folder(uri: Uri): DocumentFile {
        val directory = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("Cannot access output folder. Choose another location.")
        if (!directory.isDirectory || !directory.canRead() || !directory.canWrite()) {
            throw IOException("Output folder is unavailable or not writable. Choose another location.")
        }
        return directory
    }

    /** Stage first: unchanged lossless results never create a destination document. */
    suspend fun save(
        input: Uri,
        sourceName: String?,
        outputTree: Uri?,
        sourceTree: Uri?,
        options: ImageProcessingOptions
    ): SaveImageResult = withContext(Dispatchers.IO) {
        var candidate: File? = null
        var created: DocumentFile? = null
        var committed = false
        var cleanupWarning = ""
        var result: SaveImageResult
        try {
            require(options.isValid()) { "Invalid processing settings." }
            candidate = File.createTempFile("save-candidate-", ".tmp", context.cacheDir)
            result = when (val processed = processor.process(input, Uri.fromFile(candidate), options)) {
                is ImageProcessingResult.Unchanged -> SaveImageResult.Unchanged(processed.reason)
                is ImageProcessingResult.Failed -> SaveImageResult.Failed(processed.error)
                is ImageProcessingResult.Success -> {
                    coroutineContext.ensureActive()
                    val size = candidate.length()
                    check(size > 0 && size == processed.outputSizeBytes) { "Invalid processed output." }
                    check(options.processingMode != ProcessingMode.LOSSLESS_OPTIMIZE ||
                        size < processed.originalSizeBytes) { "No lossless savings. Output was not saved." }
                    val directory = if (outputTree != null) folder(outputTree) else {
                        val source = folder(sourceTree ?: throw IOException("Choose an output folder first."))
                        val child = source.findFile("Optimized") ?: source.createDirectory("Optimized")
                            ?: throw IOException("Cannot create Optimized folder. Choose another output location.")
                        if (!child.isDirectory || !child.canWrite() || !child.canRead()) {
                            throw IOException("Optimized folder is not writable. Choose another output location.")
                        }
                        child
                    }
                    val existing = directory.listFiles()
                    val name = optimizedOutputName(sourceName, processed.format.extension,
                        existing.mapNotNull { it.name }.toSet())
                    val mime = when (processed.format) {
                        OutputFormat.JPEG -> "image/jpeg"
                        OutputFormat.PNG -> "image/png"
                        else -> "image/webp"
                    }
                    val output = directory.createFile(mime, name)
                        ?: throw IOException("Provider could not create output. Choose another location.")
                    // Never open a provider-returned source or existing document for writing.
                    check(!sameDocument(output.uri, input) && existing.none { sameDocument(it.uri, output.uri) }) {
                        "Provider returned an existing document. Nothing was written."
                    }
                    created = output
                    resolver.openOutputStream(output.uri, "wt")?.use { stream ->
                        candidate.inputStream().use { it.copyTo(stream) }
                    } ?: throw IOException("Provider could not open output for writing.")
                    coroutineContext.ensureActive()
                    val expectedDigest = candidate.inputStream().use { stream -> digest(stream) }
                    val actual = resolver.openInputStream(output.uri)?.use { stream -> digest(stream) }
                        ?: throw IOException("Cannot verify saved output.")
                    check(actual.first == size && actual.second.contentEquals(expectedDigest.second)) {
                        "Saved output did not match the processed image."
                    }
                    committed = true
                    SaveImageResult.Saved(output.uri, output.name ?: name, processed.copy(
                        outputSizeBytes = actual.first,
                        bytesSaved = processed.originalSizeBytes - actual.first,
                        percentageReduction = if (processed.originalSizeBytes > 0)
                            100f * (processed.originalSizeBytes - actual.first) / processed.originalSizeBytes else 0f
                    ))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result = SaveImageResult.Failed(e.localizedMessage ?: "Could not save output. Choose another location.")
        } finally {
            if (!committed && created != null && !runCatching { created.delete() }.getOrDefault(false)) {
                cleanupWarning = " An incomplete output could not be removed from the output folder."
            }
            candidate?.delete()
        }
        if (result is SaveImageResult.Failed) result.copy(error = result.error + cleanupWarning) else result
    }

    private fun digest(stream: java.io.InputStream): Pair<Long, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
            size += count
        }
        return size to hash.digest()
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        if (first.authority != second.authority) return false
        return runCatching {
            DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
        }.getOrDefault(false)
    }
}
