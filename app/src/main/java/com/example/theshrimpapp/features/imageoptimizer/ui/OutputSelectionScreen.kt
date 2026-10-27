package com.example.theshrimpapp.features.imageoptimizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.MAX_BATCH_CONCURRENCY
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSelectionScreen(
    onBackClick: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.processBatch()
        onNavigateToProcessing()
    }

    val outputPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let(viewModel::selectOutputFolder)
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
                        Button(
                            onClick = onNavigateToProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View Batch Progress")
                        }
                    } else {
                        val isDestinationValid = when (uiState.destinationMode) {
                            OutputDestinationMode.REPLACE_ORIGINAL, OutputDestinationMode.SUBFOLDER -> uiState.selectedFolders.isNotEmpty() || uiState.selectedFolderUri != null
                            OutputDestinationMode.CUSTOM_FOLDER -> uiState.outputFolderUri != null
                        }
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.processBatch()
                                    onNavigateToProcessing()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isLoading &&
                                    uiState.optionsError == null &&
                                    uiState.selectedImages.isNotEmpty() &&
                                    isDestinationValid
                        ) {
                            Text("Process ${uiState.selectedImages.size} image(s)")
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
                    text = "Output Settings",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (uiState.optionsError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.optionsError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Save Output Location Card with 3 radio choices
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Save Output Location", style = MaterialTheme.typography.titleMedium)

                    OutputDestinationMode.entries.forEach { mode ->
                        val isSelected = uiState.destinationMode == mode
                        val hasSourceFolder = uiState.selectedFolders.isNotEmpty() || uiState.selectedFolderUri != null
                        val isModeEnabled = !uiState.isProcessing && !uiState.isLoading && when (mode) {
                            OutputDestinationMode.REPLACE_ORIGINAL, OutputDestinationMode.SUBFOLDER -> hasSourceFolder
                            else -> true
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    enabled = isModeEnabled,
                                    onClick = { viewModel.updateDestinationMode(mode) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val description = when (mode) {
                                        OutputDestinationMode.REPLACE_ORIGINAL ->
                                            if (uiState.selectedFolders.size > 1)
                                                "Replaces original images directly in their respective folders (${uiState.selectedFolders.size} folders)."
                                            else if (hasSourceFolder)
                                                "Replaces original images directly in ${uiState.selectedFolderName ?: "source folder"}."
                                            else
                                                "Requires source folder selection (Photo Picker files are read-only)."
                                        OutputDestinationMode.SUBFOLDER ->
                                            if (uiState.selectedFolders.size > 1)
                                                "Creates an 'Optimized' subfolder in each of the ${uiState.selectedFolders.size} selected folders."
                                            else if (hasSourceFolder)
                                                "Saves to 'Optimized' subfolder in ${uiState.selectedFolderName ?: "source folder"}."
                                            else
                                                "Requires source folder selection."
                                        OutputDestinationMode.CUSTOM_FOLDER ->
                                            uiState.outputFolderName?.let { "Selected: $it" }
                                                ?: "Choose a custom output folder."
                                    }
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            if (mode == OutputDestinationMode.CUSTOM_FOLDER && isSelected) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 40.dp, top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { outputPicker.launch(uiState.outputFolderUri) },
                                        enabled = !uiState.isProcessing && !uiState.isLoading
                                    ) {
                                        Text(if (uiState.outputFolderUri != null) "Change custom folder" else "Select custom folder")
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.statusMessage.isNotEmpty()) {
                        Text(
                            text = uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (uiState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            // Concurrency Slider Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Parallel Images: ${uiState.concurrency} (max $MAX_BATCH_CONCURRENCY)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Slider(
                        value = uiState.concurrency.toFloat(),
                        onValueChange = { viewModel.updateConcurrency(it.toInt()) },
                        valueRange = 1f..MAX_BATCH_CONCURRENCY.toFloat(),
                        steps = MAX_BATCH_CONCURRENCY - 2,
                        enabled = !uiState.isProcessing && !uiState.isLoading
                    )
                }
            }

            // Processing Mode Selection (Lossless Optimize vs Reduce Size)
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Processing Mode",
                        style = MaterialTheme.typography.titleMedium
                    )
                    ProcessingMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RadioButton(
                                enabled = !uiState.isProcessing,
                                selected = uiState.processingMode == mode,
                                onClick = { viewModel.updateProcessingMode(mode) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (mode) {
                                        ProcessingMode.LOSSLESS_OPTIMIZE -> "Lossless Optimize (Default)"
                                        ProcessingMode.REDUCE_SIZE -> mode.displayName
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (mode) {
                                        ProcessingMode.LOSSLESS_OPTIMIZE -> "Reduces file size without changing decoded pixels. No resizing or lossy compression."
                                        ProcessingMode.REDUCE_SIZE -> "Resize or keep original dimensions, convert formats, and adjust compression."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // If Reduce Size mode is selected, show dimension, format, and quality controls
            if (uiState.processingMode == ProcessingMode.REDUCE_SIZE) {
                // 1. Output Format Selection
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Output Format",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutputFormat.entries.forEach { format ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    enabled = !uiState.isProcessing,
                                    selected = uiState.outputFormat == format,
                                    onClick = { viewModel.updateOutputFormat(format) }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = format.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    val desc = when (format) {
                                        OutputFormat.JPEG -> "Lossy compression, universal compatibility, no transparency."
                                        OutputFormat.WEBP_LOSSY -> "Modern lossy WebP, smaller file sizes, good quality."
                                        OutputFormat.WEBP_LOSSLESS -> "Lossless WebP, preserves exact pixel data."
                                        OutputFormat.PNG -> "Lossless PNG, preserves transparency."
                                    }
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Maximum Dimension Selection & Presets
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Maximum Dimension",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Resizes the longest side to fit within this limit, preserving aspect ratio without upscaling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Presets row
                        FilterChip(
                            enabled = !uiState.isProcessing,
                            selected = uiState.keepOriginalSize,
                            onClick = { viewModel.updateKeepOriginalSize(!uiState.keepOriginalSize) },
                            label = { Text("Keep original size") }
                        )
                        val presets = listOf(1280, 1920, 2560, 3840)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { preset ->
                                FilterChip(
                                    enabled = !uiState.isProcessing,
                                    selected = !uiState.keepOriginalSize && uiState.maxDimension == preset,
                                    onClick = { viewModel.updateMaxDimension(preset) },
                                    label = { Text("$preset px") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = uiState.maxDimensionInput,
                            onValueChange = { viewModel.updateMaxDimensionInput(it) },
                            label = { Text("Custom Dimension (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.keepOriginalSize && !uiState.isProcessing,
                            singleLine = true
                        )
                    }
                }

                // 3. Quality Control (if format supports quality)
                if (uiState.outputFormat.supportsQuality) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Compression Quality (${uiState.quality})",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Controls lossy compression encoder setting (1-100). Higher quality = larger file size.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Slider(
                                enabled = !uiState.isProcessing,
                                value = uiState.quality.toFloat(),
                                onValueChange = { viewModel.updateQuality(it.toInt()) },
                                valueRange = 1f..100f,
                                steps = 98,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // 4. JPEG Background Color (if format is JPEG)
                if (uiState.outputFormat == OutputFormat.JPEG) {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Transparency Background (JPEG)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "JPEG does not support alpha transparency. Transparent areas will be filled with this background color.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            JpegBackgroundColor.entries.forEach { bgColor ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RadioButton(
                                        enabled = !uiState.isProcessing,
                                        selected = uiState.jpegBackgroundColor == bgColor,
                                        onClick = { viewModel.updateJpegBackgroundColor(bgColor) }
                                    )
                                    Text(
                                        text = bgColor.displayName,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Metadata Preservation Settings (shared across modes)
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Preserve Metadata (EXIF)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Keeps orientation, creation date, and camera metadata in output files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        enabled = !uiState.isProcessing,
                        checked = uiState.preserveMetadata,
                        onCheckedChange = { viewModel.updatePreserveMetadata(it) }
                    )
                }
            }
        }
    }
}
