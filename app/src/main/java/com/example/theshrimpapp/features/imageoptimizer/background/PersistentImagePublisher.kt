package com.example.theshrimpapp.features.imageoptimizer.background

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode
import com.example.theshrimpapp.features.imageoptimizer.domain.optimizedOutputName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A deterministic reserved filename and a durable candidate close the SAF/Room commit gap. */
@Singleton
class PersistentImagePublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: BatchDao
) {
    private val creation = Mutex()
    private val resolver get() = context.contentResolver

    /** On user cancellation after a process restart, record already-written outputs without creating new ones. */
    suspend fun recover(job: BatchJob, item: BatchItem, candidate: File): BatchItem? =
        withContext(Dispatchers.IO) {
            if (item.state != "PREPARED" || !candidate.exists() || candidate.length() != item.outputBytes) return@withContext null
            try {
                if (job.destinationMode == OutputDestinationMode.REPLACE_ORIGINAL.name) {
                    val targetUri = item.originalUri.toUri()
                    val expected = candidate.inputStream().use { fingerprint(it) }
                    val actual = resolver.openInputStream(targetUri)?.use { fingerprint(it) }
                        ?: return@withContext null
                    if (actual.first == expected.first && actual.second.contentEquals(expected.second)) {
                        val saved = item.copy(
                            state = "SAVED",
                            outputUri = targetUri.toString(),
                            outputName = item.name ?: "original",
                            outputBytes = actual.first
                        )
                        withContext(NonCancellable) { dao.updateItem(saved) }
                        return@withContext saved
                    }
                    return@withContext null
                }
                val known =
                    item.outputUri?.let { DocumentFile.fromSingleUri(context, it.toUri()) }
                val treeUriStr = item.sourceTreeUri ?: job.outputTree ?: job.sourceTree ?: return@withContext null
                var directory = DocumentFile.fromTreeUri(context, treeUriStr.toUri())
                if (job.destinationMode == OutputDestinationMode.SUBFOLDER.name && job.outputTree == null) {
                    directory = directory?.findFile("Optimized")
                }
                val document =
                    known?.takeIf { it.exists() } ?: directory?.findFile(item.outputName ?: reservedName(job, item))
                    ?: return@withContext null
                if (sameDocument(document.uri, item.originalUri.toUri())) return@withContext null
                val expected = candidate.inputStream().use { fingerprint(it) }
                val actual = resolver.openInputStream(document.uri)?.use { fingerprint(it) }
                    ?: return@withContext null
                if (actual.first != expected.first || !actual.second.contentEquals(expected.second)) {
                    if (item.outputUri == document.uri.toString() && actual.first < expected.first && !document.delete())
                        dao.updateItem(item.copy(reason = "Incomplete output could not be removed."))
                    return@withContext null
                }
                val saved = item.copy(
                    state = "SAVED",
                    outputUri = document.uri.toString(),
                    outputName = document.name ?: reservedName(job, item)
                )
                withContext(NonCancellable) { dao.updateItem(saved) }
                saved
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    suspend fun publish(job: BatchJob, item: BatchItem, candidate: File): BatchItem =
        withContext(Dispatchers.IO) {
            check(candidate.exists() && candidate.length() == item.outputBytes && item.outputBytes > 0) { "Prepared candidate is missing or invalid." }
            val expected = candidate.inputStream().use { fingerprint(it) }

            if (job.destinationMode == OutputDestinationMode.REPLACE_ORIGINAL.name) {
                val targetUri = item.originalUri.toUri()
                val ext = item.details(job.options().processingMode).format.extension
                val mime = when (ext) {
                    ".jpg" -> "image/jpeg"
                    ".png" -> "image/png"
                    else -> "image/webp"
                }

                val origName = item.name ?: "image"
                val stem = origName.substringBeforeLast('.')
                val targetName = "$stem$ext"
                val isSameFormat = origName.equals(targetName, ignoreCase = true)

                val checkpoint = item.copy(
                    outputUri = targetUri.toString(),
                    outputName = origName
                )
                withContext(NonCancellable) { dao.updateItem(checkpoint) }

                return@withContext withContext(NonCancellable) {
                    try {
                        val treeUriStr = item.sourceTreeUri ?: job.sourceTree ?: job.outputTree
                        val parentDir = treeUriStr?.let { DocumentFile.fromTreeUri(context, it.toUri()) }
                        val originalDoc = parentDir?.findFile(origName) ?: DocumentFile.fromSingleUri(context, targetUri)

                        val finalDoc: DocumentFile
                        if (parentDir != null && parentDir.canWrite()) {
                            if (!isSameFormat && originalDoc != null && originalDoc.exists()) {
                                originalDoc.delete()
                            }
                            finalDoc = if (!isSameFormat) {
                                parentDir.createFile(mime, targetName)
                                    ?: throw IOException("Could not create $targetName in source folder.")
                            } else {
                                originalDoc ?: throw IOException("Original file unavailable.")
                            }
                        } else {
                            finalDoc = originalDoc ?: DocumentFile.fromSingleUri(context, targetUri)
                                ?: throw IOException("Original file is unavailable.")
                        }

                        val outStream = resolver.openOutputStream(finalDoc.uri, "wt")
                            ?: throw IOException("Original file is read-only or inaccessible.")
                        outStream.use { out ->
                            candidate.inputStream().use { it.copyTo(out) }
                        }
                        val actual = resolver.openInputStream(finalDoc.uri)?.use { fingerprint(it) }
                            ?: throw IOException("Cannot verify updated original file.")
                        check(actual.first == expected.first && actual.second.contentEquals(expected.second)) {
                            "Output verification failed after replacing original file."
                        }

                        checkpoint.copy(
                            state = "SAVED",
                            outputUri = finalDoc.uri.toString(),
                            outputName = finalDoc.name ?: targetName,
                            outputBytes = actual.first
                        ).also { dao.updateItem(it) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw IOException("Could not replace original: ${e.localizedMessage}", e)
                    }
                }
            }

            var checkpoint = item
            val output = creation.withLock {
                val treeUriStr = if (job.destinationMode == OutputDestinationMode.SUBFOLDER.name) {
                    item.sourceTreeUri ?: job.sourceTree
                } else {
                    job.outputTree ?: item.sourceTreeUri ?: job.sourceTree
                }
                val tree = (treeUriStr ?: throw IOException("Output folder missing.")).toUri()
                var directory = DocumentFile.fromTreeUri(context, tree)
                    ?: throw IOException("Output folder unavailable.")
                check(directory.isDirectory && directory.canRead() && directory.canWrite()) { "Output access revoked; select an accessible folder for a new batch." }
                if (job.destinationMode == OutputDestinationMode.SUBFOLDER.name && job.outputTree == null) {
                    directory = directory.findFile("Optimized") ?: directory.createDirectory("Optimized")
                            ?: throw IOException("Cannot create Optimized folder.")
                }
                val extension = item.details(job.options().processingMode).format.extension
                val existingDocs = directory.listFiles()
                val existingNames = existingDocs.mapNotNull { it.name }.toSet()
                val name = optimizedOutputName(item.name, extension, existingNames)
                val mime = when (extension) {
                    ".jpg" -> "image/jpeg"; ".png" -> "image/png"; else -> "image/webp"
                }
                val existing =
                    item.outputUri?.let { DocumentFile.fromSingleUri(context, it.toUri()) }
                        ?.takeIf { it.exists() } ?: directory.findFile(name)
                val before =
                    if (existing == null) existingDocs.map { it.uri } else emptyList()
                val document = existing ?: directory.createFile(mime, name)
                ?: throw IOException("Cannot create output document.")
                check(
                    !sameDocument(document.uri, item.inputUri.toUri()) &&
                            !sameDocument(document.uri, item.originalUri.toUri())
                ) { "Provider returned source document." }
                check(existing != null || before.none {
                    sameDocument(
                        it,
                        document.uri
                    )
                }) { "Provider returned an existing document; nothing was written." }
                if (existing != null) {
                    val actual = resolver.openInputStream(document.uri)?.use { fingerprint(it) }
                    if (actual != null && actual.first == expected.first && actual.second.contentEquals(
                            expected.second
                        )
                    ) {
                        return@withContext item.copy(
                            state = "SAVED",
                            outputUri = document.uri.toString(),
                            outputName = document.name ?: name
                        )
                    }
                    // Only the exact recorded partial document may be replaced. Unknown collisions fail safely.
                    check(item.outputUri == document.uri.toString()) { "Reserved output name already exists; no file was overwritten." }
                    check(actual != null && actual.first < expected.first) { "Existing output content differs; it was preserved." }
                    check(document.delete()) { "Could not remove incomplete output." }
                    val remainingDocuments = directory.listFiles().map { it.uri }
                    val replacement = directory.createFile(mime, name)
                        ?: throw IOException("Could not recreate incomplete output.")
                    check(
                        !sameDocument(replacement.uri, item.originalUri.toUri()) &&
                                remainingDocuments.none { sameDocument(it, replacement.uri) }) {
                        "Provider returned an unsafe replacement document."
                    }
                    checkpoint = item.copy(
                        outputUri = replacement.uri.toString(),
                        outputName = replacement.name ?: name
                    )
                    withContext(NonCancellable) { dao.updateItem(checkpoint) }
                    replacement
                } else {
                    checkpoint = item.copy(
                        outputUri = document.uri.toString(),
                        outputName = name
                    )
                    withContext(NonCancellable) { dao.updateItem(checkpoint) }
                    document
                }
            }
            // Complete write + validation + DB commit as one interruption-safe section.
            withContext(NonCancellable) {
                try {
                    resolver.openOutputStream(output.uri, "wt")
                        ?.use { out -> candidate.inputStream().use { it.copyTo(out) } }
                        ?: throw IOException("Cannot write output.")
                    val actual = resolver.openInputStream(output.uri)?.use { fingerprint(it) }
                        ?: throw IOException("Cannot verify output.")
                    check(actual.first == expected.first && actual.second.contentEquals(expected.second)) { "Output verification failed." }
                    checkpoint.copy(state = "SAVED", outputBytes = actual.first)
                        .also { dao.updateItem(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val removed = try {
                        output.delete()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        false
                    }
                    throw IOException(
                        "${e.localizedMessage}" + if (!removed) " Incomplete output could not be removed." else "",
                        e
                    )
                }
            }
        }

    private fun fingerprint(stream: java.io.InputStream): Pair<Long, ByteArray> {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        var count = 0L
        while (true) {
            val read = stream.read(buffer); if (read < 0) break; hash.update(
                buffer,
                0,
                read
            ); count += read
        }
        return count to hash.digest()
    }

    private fun reservedName(job: BatchJob, item: BatchItem): String {
        val extension = item.details(job.options().processingMode).format.extension
        return optimizedOutputName(item.name, extension, emptySet())
    }

    private fun sameDocument(first: Uri, second: Uri): Boolean {
        if (first == second) return true
        if (first.authority != second.authority) return false
        return try {
            DocumentsContract.getDocumentId(first) == DocumentsContract.getDocumentId(second)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
