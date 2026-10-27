package com.example.theshrimpapp.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.theshrimpapp.features.imageoptimizer.ui.ImageItem
import java.util.Locale

@Composable
fun FileTypePieChart(
    images: List<ImageItem>,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    val typeCounts = images.groupingBy { item ->
        val mime = item.mimeType?.lowercase() ?: ""
        val name = item.name?.lowercase() ?: ""
        when {
            mime.contains("jpeg") || name.endsWith(".jpg") || name.endsWith(".jpeg") -> "JPEG"
            mime.contains("png") || name.endsWith(".png") -> "PNG"
            mime.contains("webp") || name.endsWith(".webp") -> "WebP"
            else -> "Other"
        }
    }.eachCount()

    val total = images.size.toFloat()
    val colors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.outline
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                var startAngle = 0f
                typeCounts.entries.forEachIndexed { index, entry ->
                    val sweepAngle = (entry.value / total) * 360f
                    val color = colors[index % colors.size]
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true
                    )
                    startAngle += sweepAngle
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            typeCounts.entries.forEachIndexed { index, entry ->
                val color = colors[index % colors.size]
                val percentage = (entry.value * 100) / images.size
                Row(
                    modifier = Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, shape = CircleShape)
                    )
                    Text(
                        text = "${entry.key}: ${entry.value} ($percentage%)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
