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
}
