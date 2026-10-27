package com.example.theshrimpapp.features.imageoptimizer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.features.imageoptimizer.data.SaveImageResult
import com.example.theshrimpapp.features.imageoptimizer.domain.BatchStatus
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.ui.theme.OnStatusErrorContainer
import com.example.theshrimpapp.ui.theme.OnStatusSuccessContainer
import com.example.theshrimpapp.ui.theme.OnStatusWarningContainer
import com.example.theshrimpapp.ui.theme.StatusErrorContainer
import com.example.theshrimpapp.ui.theme.StatusSuccessContainer
import com.example.theshrimpapp.ui.theme.StatusWarningContainer
import java.util.Locale
import kotlin.math.abs

@Composable
fun ResultScreen(
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val batch = uiState.batch
    val context = LocalContext.current

    val results = batch.results
    val savedResults = results.mapNotNull { (it.result as? SaveImageResult.Saved) }

    val savedCount = savedResults.size
    val unchangedCount = results.count { it.result is SaveImageResult.Unchanged }
    val failedCount = results.count { it.result is SaveImageResult.Failed }

    val totalOriginalBytes = savedResults.sumOf { it.details.originalSizeBytes }
    val totalOutputBytes = savedResults.sumOf { it.details.outputSizeBytes }
    val netBytesSaved = totalOriginalBytes - totalOutputBytes
    val netPercentage = if (totalOriginalBytes > 0) {
        (netBytesSaved.toDouble() * 100.0) / totalOriginalBytes.toDouble()
    } else 0.0

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
                    Button(
                        onClick = onBackClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Done")
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch Results",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            // Overall Summary Card
            ElevatedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (statusLabel, statusBg, statusFg) = when (batch.status) {
                            BatchStatus.COMPLETED -> Triple("Completed", StatusSuccessContainer, OnStatusSuccessContainer)
                            BatchStatus.CANCELLED -> Triple("Cancelled", StatusWarningContainer, OnStatusWarningContainer)
                            BatchStatus.FAILED -> Triple("Failed", StatusErrorContainer, OnStatusErrorContainer)
                            else -> Triple("Finished", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface)
                        }
                        Text(
                            text = "Overall Summary",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Surface(
                            color = statusBg,
                            shape = CircleShape
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusFg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    // Processing options & output folder
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Mode: ${uiState.processingMode.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.outputFolderName != null) {
                            Text(
                                text = "Output: ${uiState.outputFolderName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Count badges
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${batch.totalCount}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Saved", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "$savedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Unchanged", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "$unchangedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Failed", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "$failedCount",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (failedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // Savings Box
                    if (savedCount > 0) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = StatusSuccessContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Original Total:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnStatusSuccessContainer
                                    )
                                    Text(
                                        text = formatFileSize(totalOriginalBytes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnStatusSuccessContainer
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Output Total:",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnStatusSuccessContainer
                                    )
                                    Text(
                                        text = formatFileSize(totalOutputBytes),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OnStatusSuccessContainer
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (netBytesSaved >= 0) "Net Savings:" else "Net Increase:",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = OnStatusSuccessContainer
                                    )
                                    val savingsText = if (netBytesSaved > 0) {
                                        val pct = String.format(Locale.US, "%.1f", netPercentage)
                                        "${formatFileSize(netBytesSaved)} ($pct%)"
                                    } else if (netBytesSaved < 0) {
                                        val pct = String.format(Locale.US, "%.1f", abs(netPercentage))
                                        "+${formatFileSize(abs(netBytesSaved))} (+$pct%)"
                                    } else {
                                        "0 B (0.0%)"
                                    }
                                    Text(
                                        text = savingsText,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = OnStatusSuccessContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Folder Tracking Section (FOLDER STATS BREAKDOWN)
            if (results.isNotEmpty()) {
                val folderGroups = results.groupBy { entry ->
                    entry.item.sourceFolderName ?: "Direct Image Selection"
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Folder Stats Breakdown",
                        style = MaterialTheme.typography.titleMedium
                    )

                    folderGroups.forEach { (folderName, folderResults) ->
                        val fSavedResults = folderResults.mapNotNull { (it.result as? SaveImageResult.Saved) }
                        val fSavedCount = fSavedResults.size
                        val fUnchangedCount = folderResults.count { it.result is SaveImageResult.Unchanged }
                        val fFailedCount = folderResults.count { it.result is SaveImageResult.Failed }

                        val fOrigBytes = fSavedResults.sumOf { it.details.originalSizeBytes }
                        val fOutBytes = fSavedResults.sumOf { it.details.outputSizeBytes }
                        val fNetSaved = fOrigBytes - fOutBytes
                        val fPct = if (fOrigBytes > 0) (fNetSaved.toDouble() * 100.0) / fOrigBytes.toDouble() else 0.0

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = folderName,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${folderResults.size} files • $fSavedCount saved, $fUnchangedCount unchanged, $fFailedCount failed",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (fSavedCount > 0) {
                                    val savingsSummary = if (fNetSaved > 0) {
                                        "Saved ${formatFileSize(fNetSaved)} (${String.format(Locale.US, "%.1f", fPct)}%)"
                                    } else if (fNetSaved < 0) {
                                        "Increased by ${formatFileSize(-fNetSaved)} (+${String.format(Locale.US, "%.1f", -fPct)}%)"
                                    } else {
                                        "No size change"
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${formatFileSize(fOrigBytes)} → ${formatFileSize(fOutBytes)}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = savingsSummary,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (fNetSaved >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Results List
            if (results.isNotEmpty()) {
                Text(
                    text = "File Details (${results.size})",
                    style = MaterialTheme.typography.titleMedium
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(results, key = { it.item.uri.toString() }) { entry ->
                        val item = entry.item
                        val result = entry.result

                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Top row: name and status
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name ?: "Image",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f)
                                    )

                                    when (result) {
                                        is SaveImageResult.Saved -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null
                                                )
                                                Text(
                                                    text = "Saved",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }

                                        is SaveImageResult.Unchanged -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null
                                                )
                                                Text(
                                                    text = "Unchanged",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                        }

                                        is SaveImageResult.Failed -> {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Error,
                                                    contentDescription = null
                                                )
                                                Text(
                                                    text = "Failed",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }

                                // Body content based on result type
                                when (result) {
                                    is SaveImageResult.Saved -> {
                                        val details = result.details
                                        val dimsStr = "${details.width} × ${details.height}"
                                        val origSizeStr = formatFileSize(details.originalSizeBytes)
                                        val outSizeStr = formatFileSize(details.outputSizeBytes)

                                        Text(
                                            text = "Output: ${result.name} • $dimsStr • ${details.format.displayName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "$origSizeStr → $outSizeStr",
                                                style = MaterialTheme.typography.bodyMedium
                                            )

                                            val deltaStr = if (details.bytesSaved > 0) {
                                                val pct = String.format(
                                                    Locale.US,
                                                    "%.1f",
                                                    details.percentageReduction
                                                )
                                                "-${formatFileSize(details.bytesSaved)} (-$pct%)"
                                            } else if (details.bytesSaved < 0) {
                                                val pct = String.format(
                                                    Locale.US,
                                                    "%.1f",
                                                    abs(details.percentageReduction)
                                                )
                                                "+${formatFileSize(abs(details.bytesSaved))} (+$pct%)"
                                            } else {
                                                "0 B (0.0%)"
                                            }

                                            Text(
                                                text = deltaStr,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (details.bytesSaved >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                            )
                                        }

                                        // Action buttons: View and Share
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    openImage(
                                                        context,
                                                        result.uri,
                                                        mimeForFormat(details.format)
                                                    )
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                                    contentDescription = null
                                                )
                                                Text(
                                                    "View",
                                                    modifier = Modifier.padding(start = 4.dp)
                                                )
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    shareImage(
                                                        context,
                                                        result.uri,
                                                        mimeForFormat(details.format)
                                                    )
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = null
                                                )
                                                Text(
                                                    "Share",
                                                    modifier = Modifier.padding(start = 4.dp)
                                                )
                                            }
                                        }
                                    }

                                    is SaveImageResult.Unchanged -> {
                                        Text(
                                            text = "Reason: ${result.reason}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text = "Original size: ${formatFileSize(item.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    is SaveImageResult.Failed -> {
                                        Text(
                                            text = "Error: ${result.error}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            text = "Original size: ${formatFileSize(item.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openImage(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open image: ${e.localizedMessage}", Toast.LENGTH_SHORT)
            .show()
    }
}

private fun shareImage(context: Context, uri: Uri, mimeType: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share image"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share image: ${e.localizedMessage}", Toast.LENGTH_SHORT)
            .show()
    }
}

private fun mimeForFormat(format: OutputFormat): String {
    return when (format) {
        OutputFormat.JPEG -> "image/jpeg"
        OutputFormat.PNG -> "image/png"
        OutputFormat.WEBP_LOSSY, OutputFormat.WEBP_LOSSLESS -> "image/webp"
    }
}

private fun formatFileSize(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}
