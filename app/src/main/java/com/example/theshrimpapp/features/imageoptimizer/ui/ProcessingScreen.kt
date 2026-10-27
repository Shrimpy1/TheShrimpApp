package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchStatus
import com.example.theshrimpapp.ui.theme.StatusError
import com.example.theshrimpapp.ui.theme.StatusSuccess
import com.example.theshrimpapp.ui.theme.StatusWarning
import java.util.Locale

@Composable
fun ProcessingScreen(
    onNavigateToResult: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val batch = uiState.batch

    // Automatically redirect to ResultScreen when batch completes/cancels/fails while on ProcessingScreen
    LaunchedEffect(uiState.isProcessing, batch.status) {
        if (!uiState.isProcessing && batch.status != BatchStatus.IDLE && batch.status != BatchStatus.QUEUED) {
            onNavigateToResult()
        }
    }

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
                                    if (batch.status == BatchStatus.CANCELLING) "Cancelling..." else "Cancel Batch"
                                )
                            }
                            Text(
                                text = "Cancellation stops active queue after current files finish.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Button(
                            onClick = onNavigateToResult,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Results")
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
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch Processing",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Compact Progress Tracker Card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = uiState.statusMessage,
                        style = MaterialTheme.typography.titleMedium
                    )

                    val total = batch.totalCount
                    val completed = batch.completedCount
                    val progressRatio = if (total > 0) completed.toFloat() / total.toFloat() else 0f
                    val percentageStr = String.format(Locale.US, "%.0f%%", progressRatio * 100f)

                    if (total > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { progressRatio },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$completed of $total processed",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = percentageStr,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    // Source Folder & Parallel Processing Context
                    if (uiState.selectedFolderName != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Working on: ${uiState.selectedFolderName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        text = "${uiState.concurrency} parallel worker threads active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Compact Tally Metrics Row (Vertical layout for each count to fit all screen widths cleanly)
                    val savedCount = batch.results.count { it.result is SaveImageResult.Saved }
                    val unchangedCount = batch.results.count { it.result is SaveImageResult.Unchanged }
                    val failedCount = batch.results.count { it.result is SaveImageResult.Failed }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Saved Metric
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Saved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusSuccess
                                )
                            }
                            Text(
                                text = "$savedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = StatusSuccess
                            )
                        }

                        // Unchanged Metric
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Unchanged",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusWarning
                                )
                            }
                            Text(
                                text = "$unchangedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = StatusWarning
                            )
                        }

                        // Failed Metric
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Failed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusError
                                )
                            }
                            Text(
                                text = "$failedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = StatusError
                            )
                        }
                    }
                }
            }
        }
    }
}
