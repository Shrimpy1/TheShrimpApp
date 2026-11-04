package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.components.FileTypePieChart
import com.example.theshrimpapp.components.LabeledCheckbox
import com.example.theshrimpapp.components.LabeledRadioButton
import com.example.theshrimpapp.features.imageoptimizer.domain.FolderImageSelectionMode
import com.example.theshrimpapp.features.imageoptimizer.domain.SupportedImageFormat
import java.util.Locale

@Composable
fun ImageOptimizerScreen(
    modifier: Modifier = Modifier,
    onNavigateToOutput: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
    onNavigateToResult: () -> Unit = {},
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
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

    var isFolderSettingsExpanded by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (uiState.selectedImages.isNotEmpty() && !uiState.isProcessing) {
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
                            onClick = onNavigateToOutput,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isLoading
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Proceed to Output Settings (${uiState.selectedImages.size})")
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null
                                )
                            }
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
            // Active Processing Banner
            if (uiState.isProcessing) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp)
                        )

                        Text(
                            text = "Batch Processing in Progress",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        if (uiState.batch.totalCount > 0) {
                            val progress =
                                uiState.batch.completedCount.toFloat() / uiState.batch.totalCount
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "${uiState.batch.completedCount} of ${uiState.batch.totalCount} processed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Button(
                            onClick = onNavigateToProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "View Batch Progress")
                        }
                    }
                }
            } else {
                // Folder Import Settings Card (POSITIONED ON TOP OF SELECT BUTTONS)
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Explicitly styled clickable header button
                        Surface(
                            onClick = { isFolderSettingsExpanded = !isFolderSettingsExpanded },
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "Import Settings",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }

                                Icon(
                                    imageVector = if (isFolderSettingsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null
                                )
                            }
                        }

                        // Collapsible Dropdown Content (hidden by default)
                        AnimatedVisibility(visible = isFolderSettingsExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                // 1. Subfolder scanning toggle
                                LabeledCheckbox(
                                    checked = uiState.readNestedFolders,
                                    onCheckedChange = { viewModel.updateReadNestedFolders(it) },
                                    enabled = !uiState.isLoading
                                ) {
                                    Text(
                                        text = "Include nested folders",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // 2. Supported Image Types
                                Text(
                                    text = "Supported Image Types",
                                    style = MaterialTheme.typography.titleSmall
                                )

                                FolderImageSelectionMode.entries.forEach { mode ->
                                    LabeledRadioButton(
                                        selected = uiState.folderImageSelectionMode == mode,
                                        onClick = {
                                            viewModel.updateFolderImageSelectionMode(mode)
                                        },
                                        enabled = !uiState.isLoading
                                    ) {
                                        Text(
                                            text = mode.displayName,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }

                                if (uiState.folderImageSelectionMode == FolderImageSelectionMode.CUSTOM) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 24.dp, top = 4.dp)
                                    ) {
                                        SupportedImageFormat.entries.forEach { format ->
                                            val isChecked =
                                                uiState.customSupportedFormats.contains(format)
                                            LabeledCheckbox(
                                                checked = isChecked,
                                                onCheckedChange = { enabled ->
                                                    viewModel.toggleCustomSupportedFormat(
                                                        format,
                                                        enabled
                                                    )
                                                },
                                                enabled = !uiState.isLoading
                                            ) {
                                                Text(
                                                    text = format.displayName,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                    }
                                }

                                // 3. File Size Filter
                                Text(
                                    text = "File Size Filter",
                                    style = MaterialTheme.typography.titleSmall
                                )

                                LabeledCheckbox(
                                    checked = uiState.filterByFileSize,
                                    onCheckedChange = { viewModel.updateFilterByFileSize(it) },
                                    enabled = !uiState.isLoading
                                ) {
                                    Text(
                                        text = "Filter by file size (MB)",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                if (uiState.filterByFileSize) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = uiState.minFileSizeMbInput,
                                            onValueChange = { viewModel.updateMinFileSizeInput(it) },
                                            label = {
                                                Text(
                                                    "Min Size (MB)",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.weight(1f),
                                            enabled = !uiState.isLoading,
                                            singleLine = true
                                        )

                                        OutlinedTextField(
                                            value = uiState.maxFileSizeMbInput,
                                            onValueChange = { viewModel.updateMaxFileSizeInput(it) },
                                            label = {
                                                Text(
                                                    "Max Size (MB)",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.weight(1f),
                                            enabled = !uiState.isLoading,
                                            singleLine = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // File & Folder Select Buttons (POSITIONED BELOW IMPORT OPTIONS)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            pickImagesLauncher.launch(arrayOf("image/*"))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isLoading
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null
                            )
                            Text(text = if (uiState.selectedFolders.isNotEmpty()) "Add Folder" else "Select Folder")
                        }
                    }
                }

                if (uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Selected Images Summary Card & Chart
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "Total Files",
                                        style = MaterialTheme.typography.labelMedium
                                    )
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

                            // Distribution Pie Chart
                            FileTypePieChart(images = uiState.selectedImages)
                        }
                    }

                    // Selected Sources Overview (FOLDERS + DIRECT FILES)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Selected Sources",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedButton(
                            onClick = { viewModel.clearImages() },
                            enabled = !uiState.isLoading
                        ) {
                            Text(text = "Clear All")
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Render card for each selected folder
                        uiState.selectedFolders.forEach { folder ->
                            val folderImages =
                                uiState.selectedImages.filter { it.sourceFolderUri == folder.uri }
                            val folderBytes = folderImages.sumOf { it.sizeBytes ?: 0L }

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
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FolderOpen,
                                            contentDescription = null
                                        )
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = folder.name,
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = "${folderImages.size} files • ${
                                                    formatFileSize(
                                                        if (folderBytes > 0) folderBytes else null
                                                    )
                                                }",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { viewModel.removeFolder(folder.uri) },
                                        enabled = !uiState.isLoading
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove folder"
                                        )
                                    }
                                }
                            }
                        }

                        // Render card for direct image selections
                        val directImages =
                            uiState.selectedImages.filter { it.sourceFolderUri == null }
                        if (directImages.isNotEmpty()) {
                            val directBytes = directImages.sumOf { it.sizeBytes ?: 0L }

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
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = null
                                        )
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = "Direct Image Selection",
                                                style = MaterialTheme.typography.bodyLarge
                                            )
                                            Text(
                                                text = "${directImages.size} files • ${
                                                    formatFileSize(
                                                        if (directBytes > 0) directBytes else null
                                                    )
                                                }",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { viewModel.removeDirectImages() },
                                        enabled = !uiState.isLoading
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove direct files"
                                        )
                                    }
                                }
                            }
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
                            text = if (uiState.isLoading) "Loading images..." else "No images selected yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
