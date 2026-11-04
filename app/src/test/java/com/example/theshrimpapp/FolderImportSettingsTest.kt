package com.example.theshrimpapp

import com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode
import com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderImportSettingsTest {

    @Test
    fun testSupportedImageFormatMatches() {
        // JPEG
        assertTrue(SupportedImageFormat.JPEG.matches("photo.jpg", "image/jpeg"))
        assertTrue(SupportedImageFormat.JPEG.matches("photo.JPEG", null))
        assertFalse(SupportedImageFormat.JPEG.matches("photo.png", "image/png"))

        // PNG
        assertTrue(SupportedImageFormat.PNG.matches("graphic.png", "image/png"))
        assertFalse(SupportedImageFormat.PNG.matches("graphic.jpg", "image/jpeg"))

        // WebP
        assertTrue(SupportedImageFormat.WEBP.matches("image.webp", "image/webp"))
        assertFalse(SupportedImageFormat.WEBP.matches("image.png", "image/png"))

        // GIF
        assertTrue(SupportedImageFormat.GIF.matches("animation.gif", "image/gif"))
        assertFalse(SupportedImageFormat.GIF.matches("animation.webp", "image/webp"))

        // BMP
        assertTrue(SupportedImageFormat.BMP.matches("bitmap.bmp", "image/bmp"))
        assertFalse(SupportedImageFormat.BMP.matches("bitmap.jpg", "image/jpeg"))
    }

    @Test
    fun testFolderImageSelectionModes() {
        val modes = FolderImageSelectionMode.entries
        assertTrue(modes.contains(FolderImageSelectionMode.ALL_SUPPORTED))
        assertTrue(modes.contains(FolderImageSelectionMode.CUSTOM))
    }

    @Test
    fun testFileSizeFilterMatching() {
        fun isFileSizeMatching(
            sizeBytes: Long,
            filterByFileSize: Boolean,
            minFileSizeMb: Double?,
            maxFileSizeMb: Double?
        ): Boolean {
            if (!filterByFileSize) return true
            val minBytes = minFileSizeMb?.let { (it * 1024 * 1024).toLong() }
            val maxBytes = maxFileSizeMb?.let { (it * 1024 * 1024).toLong() }

            if (minBytes != null && sizeBytes < minBytes) return false
            if (maxBytes != null && sizeBytes > maxBytes) return false
            return true
        }

        val oneMbInBytes = (1.0 * 1024 * 1024).toLong()
        val fiveMbInBytes = (5.0 * 1024 * 1024).toLong()
        val tenMbInBytes = (10.0 * 1024 * 1024).toLong()

        // Filter disabled
        assertTrue(isFileSizeMatching(fiveMbInBytes, filterByFileSize = false, minFileSizeMb = 2.0, maxFileSizeMb = 4.0))

        // Filter enabled with min and max
        assertTrue(isFileSizeMatching(fiveMbInBytes, filterByFileSize = true, minFileSizeMb = 1.0, maxFileSizeMb = 10.0))
        assertFalse(isFileSizeMatching(oneMbInBytes, filterByFileSize = true, minFileSizeMb = 2.0, maxFileSizeMb = 10.0))
        assertFalse(isFileSizeMatching(tenMbInBytes, filterByFileSize = true, minFileSizeMb = 1.0, maxFileSizeMb = 5.0))

        // Only min specified
        assertTrue(isFileSizeMatching(fiveMbInBytes, filterByFileSize = true, minFileSizeMb = 1.0, maxFileSizeMb = null))
        assertFalse(isFileSizeMatching(oneMbInBytes, filterByFileSize = true, minFileSizeMb = 2.0, maxFileSizeMb = null))

        // Only max specified
        assertTrue(isFileSizeMatching(fiveMbInBytes, filterByFileSize = true, minFileSizeMb = null, maxFileSizeMb = 10.0))
        assertFalse(isFileSizeMatching(tenMbInBytes, filterByFileSize = true, minFileSizeMb = null, maxFileSizeMb = 5.0))
    }

    @Test
    fun testBatchJobImportSettingsDefaultsAndCustomFormats() {
        val job = com.example.theshrimpapp.features.imageoptimizer.background.BatchJob(
            id = "test-job",
            readNestedFolders = true,
            folderImageSelectionMode = FolderImageSelectionMode.CUSTOM.name,
            customSupportedFormats = "JPEG,PNG",
            filterByFileSize = true,
            minFileSizeMbInput = "1.5",
            maxFileSizeMbInput = "10.0"
        )

        assertTrue(job.readNestedFolders)
        org.junit.Assert.assertEquals(FolderImageSelectionMode.CUSTOM.name, job.folderImageSelectionMode)
        org.junit.Assert.assertEquals(setOf(SupportedImageFormat.JPEG, SupportedImageFormat.PNG), job.customFormatsSet())
        assertTrue(job.filterByFileSize)
        org.junit.Assert.assertEquals("1.5", job.minFileSizeMbInput)
        org.junit.Assert.assertEquals("10.0", job.maxFileSizeMbInput)
    }
}
