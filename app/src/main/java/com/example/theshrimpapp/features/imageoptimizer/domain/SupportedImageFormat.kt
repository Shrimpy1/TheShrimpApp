package com.example.theshrimpapp.features.imageoptimizer.domain

enum class SupportedImageFormat(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val mimeTypes: List<String>
) {
    JPEG("JPEG", "JPEG (.jpg, .jpeg)", listOf("jpg", "jpeg"), listOf("image/jpeg", "image/jpg")),
    PNG("PNG", "PNG (.png)", listOf("png"), listOf("image/png")),
    WEBP("WEBP", "WebP (.webp)", listOf("webp"), listOf("image/webp")),
    GIF("GIF", "GIF (.gif)", listOf("gif"), listOf("image/gif")),
    BMP("BMP", "BMP (.bmp)", listOf("bmp"), listOf("image/bmp"));

    fun matches(name: String?, mimeType: String?): Boolean {
        val lowerName = name?.lowercase() ?: ""
        val lowerMime = mimeType?.lowercase() ?: ""
        val extMatch = extensions.any { lowerName.endsWith(".$it") }
        val mimeMatch = mimeTypes.any { lowerMime == it || lowerMime.startsWith(it) }
        return extMatch || mimeMatch
    }
}

enum class FolderImageSelectionMode(val displayName: String) {
    ALL_SUPPORTED("All supported image types"),
    CUSTOM("Customized")
}
