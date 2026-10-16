package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun ImageOptimizerScreen(
    onNavigateToOutput: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImages(uris)
        }
    }

    val openFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.selectFolder(uri)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (uiState.selectedImages.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (uiState.isProcessing) {
                                    onNavigateToProcessing()
                                } else {
                                    onNavigateToOutput()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isLoading
                        ) {
                            Text(text = if (uiState.isProcessing) "View Batch Progress" else "Proceed to Output Settings")
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (uiState.isProcessing) {
                // Active Processing Banner when a batch is currently running
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Text(
                            text = "Batch Processing in Progress",
                            style = MaterialTheme.typography.titleLarge
                        )

                        if (uiState.batch.totalCount > 0) {
                            val progress = uiState.batch.completedCount.toFloat() / uiState.batch.totalCount
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "${uiState.batch.completedCount} of ${uiState.batch.totalCount} processed",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onNavigateToProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "View Batch Progress")
                        }

                        Text(
                            text = "Queue modification and new batch creation are locked until processing completes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Normal Selection Mode
                // Action Buttons Row (Select Images & Select Folder)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            pickImagesLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isLoading
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null
                            )
                            Text(text = "Select Images")
                        }
                    }

                    Button(
                        onClick = {
                            openFolderLauncher.launch(null)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isLoading
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null
                            )
                            Text(text = "Select Folder")
                        }
                    }
                }

                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Overall Selection Summary Card & Pie Chart (displayed when images selected)
                if (uiState.selectedImages.isNotEmpty()) {
                    val totalBytes = uiState.selectedImages.sumOf { it.sizeBytes ?: 0L }
                    val totalSizeStr = formatFileSize(if (totalBytes > 0) totalBytes else null)

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (uiState.selectedFolderName != null) {
                                Text(
                                    text = "Folder: ${uiState.selectedFolderName}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = "Count", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        text = "${uiState.selectedImages.size}",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Total Size",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = totalSizeStr,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }

                            // File Types Pie Chart
                            FileTypePieChart(images = uiState.selectedImages)
                        }
                    }
                }

                // Header and Queue List or Empty State
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Selected Queue (${uiState.selectedImages.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (uiState.selectedImages.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { viewModel.clearImages() },
                            enabled = !uiState.isLoading
                        ) {
                            Text(text = "Clear All")
                        }
                    }
                }

                if (uiState.selectedImages.isNotEmpty()) {
                    // Queue list bounded to max ~10 items long and scrollable
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.selectedImages, key = { it.uri.toString() }) { item ->
                            ImageQueueItemCard(
                                item = item,
                                onRemove = { viewModel.removeImage(item.uri) },
                                enabled = true
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (uiState.isLoading) "Loading selection..." else "No images selected yet.\nTap 'Select Images' or 'Select Folder' to begin.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

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
                .size(100.dp)
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

@Composable
fun ImageQueueItemCard(
    item: ImageItem,
    onRemove: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.name ?: "Image",
                    style = MaterialTheme.typography.bodyLarge
                )
                val sizeStr = formatFileSize(item.sizeBytes)
                val typeStr = item.mimeType ?: "Unknown type"
                Text(
                    text = "$typeStr • $sizeStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove image"
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long?): String {
    if (bytes == null) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes bytes"
    }
}
