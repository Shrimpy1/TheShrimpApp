package com.example.theshrimpapp.features.imageoptimizer.domain

/** Names are generated from the actual candidate format, including cross-format optimization. */
fun optimizedOutputName(sourceName: String?, extension: String, existingNames: Set<String>): String {
    val leaf = sourceName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
    val stem = leaf.substringBeforeLast('.', leaf).trim().ifEmpty { "image" }
    val base = "${stem}_optimized"
    var name = base + extension
    var suffix = 1
    while (existingNames.any { it.equals(name, ignoreCase = true) }) {
        name = "${base}_${suffix++}$extension"
    }
    return name
}
