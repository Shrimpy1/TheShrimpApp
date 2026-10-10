package com.example.theshrimpapp

import com.example.theshrimpapp.features.imageoptimizer.domain.optimizedOutputName
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNamingTest {
    @Test fun usesActualFormatAndPreservesMultiDotStem() {
        assertEquals("photo.edit_optimized.webp", optimizedOutputName("photo.edit.png", ".webp", emptySet()))
    }

    @Test fun resolvesCollisionsIncludingCaseAndGaps() {
        assertEquals("photo_optimized_3.jpg", optimizedOutputName("photo.png", ".jpg",
            setOf("PHOTO_OPTIMIZED.JPG", "photo_optimized_1.jpg", "photo_optimized_2.jpg", "photo_optimized_4.jpg")))
    }

    @Test fun handlesMissingNamesAndRemovesPathSeparators() {
        assertEquals("image_optimized.png", optimizedOutputName(null, ".png", emptySet()))
        assertEquals("photo_optimized.png", optimizedOutputName("dir\\other/photo", ".png", emptySet()))
    }
}
