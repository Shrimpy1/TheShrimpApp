package com.example.theshrimpapp.features.imageoptimizer

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceCounter
import com.example.theshrimpapp.features.imageoptimizer.domain.PerformanceTracker
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Test-only SAF provider with deterministic failure modes and performance counters; never shipped in the app. */
class SaveTestDocumentsProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).cacheDir, "save-provider").apply { mkdirs() }

    val queryChildDocumentsCount = AtomicInteger(0)
    val queryDocumentCount = AtomicInteger(0)
    val bytesReadCount = AtomicLong(0)
    val bytesWrittenCount = AtomicLong(0)

    var injectCreateFailure: Boolean = false
    var injectWriteFailure: Boolean = false
    var injectReopenFailure: Boolean = false
    var injectDeleteFailure: Boolean = false

    fun resetCounts() {
        queryChildDocumentsCount.set(0)
        queryDocumentCount.set(0)
        bytesReadCount.set(0)
        bytesWrittenCount.set(0)
        injectCreateFailure = false
        injectWriteFailure = false
        injectReopenFailure = false
        injectDeleteFailure = false
    }

    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))

    @Synchronized
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queryDocumentCount.incrementAndGet()
        PerformanceTracker.incrementCounter(PerformanceCounter.DOCUMENT_QUERIES)
        PerformanceTracker.incrementCounter(PerformanceCounter.DIRECTORY_QUERIES)
        return rows(listOf(documentId), projection)
    }

    @Synchronized
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        queryChildDocumentsCount.incrementAndGet()
        PerformanceTracker.incrementCounter(PerformanceCounter.CHILD_DIRECTORY_QUERIES)
        PerformanceTracker.incrementCounter(PerformanceCounter.DIRECTORY_QUERIES)
        return rows(file(parentDocumentId).listFiles().orEmpty().map { it.relativeTo(root).invariantSeparatorsPath }, projection)
    }

    private fun file(id: String) = if (id == "root") root else File(root, id)

    @Synchronized
    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE
        )
        val cursor = MatrixCursor(columns)
        ids.forEach { id ->
            val f = file(id)
            val values = mapOf<String, Any>(
                Document.COLUMN_DOCUMENT_ID to id,
                Document.COLUMN_DISPLAY_NAME to f.name,
                Document.COLUMN_MIME_TYPE to if (f.isDirectory) Document.MIME_TYPE_DIR else "image/webp",
                Document.COLUMN_FLAGS to (Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE),
                Document.COLUMN_SIZE to f.length()
            )
            cursor.addRow(columns.map { values[it] }.toTypedArray())
        }
        return cursor
    }

    @Synchronized
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (injectCreateFailure || File(root, "deny-create").exists()) throw IOException("Injected create failure")
        if (File(root, "return-source").exists()) return "source.png"
        val created = File(file(parentDocumentId), displayName)
        if (created.exists()) return created.relativeTo(root).invariantSeparatorsPath
        if (mimeType == Document.MIME_TYPE_DIR) check(created.mkdir()) else check(created.createNewFile())
        return created.relativeTo(root).invariantSeparatorsPath
    }

    @Synchronized
    override fun deleteDocument(documentId: String) {
        if (injectDeleteFailure || File(root, "deny-delete").exists()) throw IOException("Injected delete failure")
        file(documentId).delete()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath + File.separator)

    @Synchronized
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.contains('w')) {
            if (injectWriteFailure || File(root, "deny-write").exists()) throw IOException("Write denied")
        }
        if (mode == "r") {
            if (injectReopenFailure || File(root, "deny-reopen").exists()) throw IOException("Reopen denied")
            // Corruption on reopen simulates a provider accepting an incomplete write.
            if (File(root, "corrupt-read").exists()) file(documentId).writeBytes(byteArrayOf(0))
        }

        val f = file(documentId)
        val initialSize = f.length()
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))

        if (mode == "r") {
            bytesReadCount.addAndGet(initialSize)
            PerformanceTracker.incrementCounter(PerformanceCounter.BYTES_READ, initialSize)
        }

        return pfd
    }
}
