package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Image Optimizer",
            style = MaterialTheme.typography.headlineMedium,
        )

        // Action Buttons Row
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
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(text = "Select Images")
            }

            if (uiState.selectedImages.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.clearImages() }
                ) {
                    Text(text = "Clear All")
                }
            }
        }

        OutlinedCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Status: ${uiState.statusMessage}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Selected Count: ${uiState.selectedImagesCount}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Max Dimension: ${uiState.maxDimension} px",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Output Format: ${uiState.outputFormat}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Quality: ${uiState.quality}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Selected Images Queue List
        if (uiState.selectedImages.isNotEmpty()) {
            Text(
                text = "Selected Queue (${uiState.selectedImages.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.selectedImages, key = { it.uri.toString() }) { item ->
                    ImageQueueItemCard(
                        item = item,
                        onRemove = { viewModel.removeImage(item.uri) }
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "No images selected yet.\nTap 'Select Images' to begin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Button(
            onClick = {
                viewModel.updateMaxDimension(if (uiState.maxDimension == 1920) 2560 else 1920)
                viewModel.setStatusMessage("Updated max dimension to ${if (uiState.maxDimension == 1920) 2560 else 1920}px")
            }
        ) {
            Text(text = "Toggle Max Dimension")
        }
    }
}

@Composable
fun ImageQueueItemCard(
    item: ImageItem,
    onRemove: () -> Unit
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

            IconButton(onClick = onRemove) {
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
