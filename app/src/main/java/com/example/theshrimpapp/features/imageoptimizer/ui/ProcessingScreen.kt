package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchStatus

@Composable
fun ProcessingScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val batch = uiState.batch

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (uiState.isProcessing) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OutlinedButton(
                                onClick = viewModel::cancelBatch,
                                enabled = batch.status != BatchStatus.CANCELLING,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (batch.status == BatchStatus.CANCELLING) "Cancelling..." else "Cancel batch"
                                )
                            }
                            Text(
                                text = "Cancellation finishes active images and stops the remaining queue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Button(
                            onClick = onBackClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Done")
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row with Back Arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
                Text(
                    text = "Batch Processing",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Status Summary Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.titleMedium
                    )

                    if (batch.totalCount > 0) {
                        val progress = batch.completedCount.toFloat() / batch.totalCount

                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = "${batch.completedCount} of ${batch.totalCount} processed",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    batch.activeItems.forEach { active ->
                        Text(
                            text = "Processing: ${active.name ?: "Image"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    val saved = batch.results.count { it.result is SaveImageResult.Saved }
                    val failed = batch.results.count { it.result is SaveImageResult.Failed }
                    val unchanged = batch.results.count { it.result is SaveImageResult.Unchanged }

                    Text(
                        text = "$saved saved · $unchanged unchanged · $failed failed",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Per-item Results List
            if (batch.results.isNotEmpty()) {
                Text(
                    text = "Results",
                    style = MaterialTheme.typography.titleMedium
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(batch.results, key = { it.item.uri.toString() }) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = entry.item.name ?: "Image",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                val statusText = when (val result = entry.result) {
                                    is SaveImageResult.Saved -> "Saved as ${result.name}"
                                    is SaveImageResult.Unchanged -> "Unchanged: ${result.reason}"
                                    is SaveImageResult.Failed -> "Failed: ${result.error}"
                                }
                                val color = when (entry.result) {
                                    is SaveImageResult.Saved -> MaterialTheme.colorScheme.primary
                                    is SaveImageResult.Unchanged -> MaterialTheme.colorScheme.secondary
                                    is SaveImageResult.Failed -> MaterialTheme.colorScheme.error
                                }
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
