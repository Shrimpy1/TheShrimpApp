package com.example.theshrimpapp.features.imageoptimizer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.components.DropdownSection
import com.example.theshrimpapp.components.LabeledCheckbox
import com.example.theshrimpapp.components.LabeledRadioButton
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.MAX_BATCH_CONCURRENCY
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode

@Composable
fun OutputSelectionScreen(
    onBackClick: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var isAdvancedExpanded by remember { mutableStateOf(false) }

    // Dropdown states for Reduce Size subsections
    var isFormatExpanded by remember { mutableStateOf(false) }
    var isDimensionExpanded by remember { mutableStateOf(false) }
    var isQualityExpanded by remember { mutableStateOf(false) }
    var isBackgroundExpanded by remember { mutableStateOf(false) }
    var isAdvancedOptionsExpanded by remember { mutableStateOf(false) }

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
                            Text("Start Processing ${uiState.selectedImages.size} Image(s)")
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

            // Error Card
            if (uiState.optionsError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(
                        text = uiState.optionsError ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // SECTION 1: Save Output Location
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null
                        )
                        Text("Save Output Location", style = MaterialTheme.typography.titleMedium)
                    }

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
                            LabeledRadioButton(
                                selected = isSelected,
                                enabled = isModeEnabled,
                                onClick = { viewModel.updateDestinationMode(mode) }
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
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
                                        Text(if (uiState.outputFolderUri != null) "Change Custom Folder" else "Select Custom Folder")
                                    }
                                }
                            }
                        }
                    }

                    if (uiState.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }

            // SECTION 2: Processing Mode Selection
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null
                        )
                        Text(
                            text = "Optimization Strategy",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    ProcessingMode.entries.forEach { mode ->
                        LabeledRadioButton(
                            enabled = !uiState.isProcessing,
                            selected = uiState.processingMode == mode,
                            onClick = { viewModel.updateProcessingMode(mode) }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = when (mode) {
                                        ProcessingMode.LOSSLESS_OPTIMIZE -> "Lossless Optimization (Recommended)"
                                        ProcessingMode.REDUCE_SIZE -> "Resize, Convert & Lossy Compression"
                                    },
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (mode) {
                                        ProcessingMode.LOSSLESS_OPTIMIZE -> "Reduces file size without altering image pixels. Zero quality loss."
                                        ProcessingMode.REDUCE_SIZE -> "Adjust maximum dimensions, change formats (WebP, JPEG, PNG), or apply compression."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 3 & 4: Advanced & Performance Options / Subsections (when REDUCE_SIZE is active)
            if (uiState.processingMode == ProcessingMode.REDUCE_SIZE) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null
                            )
                            Text(
                                text = "Advanced & Performance Options",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Text(
                            text = "Configure resizing, format conversion, compression, and execution performance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 1. Target Format Dropdown
                        DropdownSection(
                            title = "Target Format",
                            subtitle = "Current: ${uiState.outputFormat.displayName}",
                            icon = Icons.Default.Tune,
                            expanded = isFormatExpanded,
                            onExpandedChange = { isFormatExpanded = it }
                        ) {
                            OutputFormat.entries.forEach { format ->
                                LabeledRadioButton(
                                    enabled = !uiState.isProcessing,
                                    selected = uiState.outputFormat == format,
                                    onClick = { viewModel.updateOutputFormat(format) }
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = format.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        val desc = when (format) {
                                            OutputFormat.JPEG -> "Universal compatibility, lossy compression."
                                            OutputFormat.WEBP_LOSSY -> "Modern WebP format, smaller files, high quality."
                                            OutputFormat.WEBP_LOSSLESS -> "Lossless WebP, preserves exact pixels."
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

                        // 2. Maximum Dimension Limit Dropdown
                        DropdownSection(
                            title = "Maximum Dimension Limit",
                            subtitle = if (uiState.keepOriginalSize) "Original size" else "${uiState.maxDimension} px",
                            icon = Icons.Default.Tune,
                            expanded = isDimensionExpanded,
                            onExpandedChange = { isDimensionExpanded = it }
                        ) {
                            Text(
                                text = "Resizes the longest side to fit within this limit while maintaining aspect ratio.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            LabeledCheckbox(
                                checked = uiState.keepOriginalSize,
                                onCheckedChange = { viewModel.updateKeepOriginalSize(it) },
                                enabled = !uiState.isProcessing
                            ) {
                                Text("Keep original dimensions")
                            }

                            val presets = listOf(1280, 1920, 2560, 3840)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                presets.forEach { preset ->
                                    FilterChip(
                                        enabled = !uiState.isProcessing && !uiState.keepOriginalSize,
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
                                label = { Text("Custom Maximum Dimension (px)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.keepOriginalSize && !uiState.isProcessing,
                                singleLine = true
                            )
                        }

                        // 3. Compression Quality Dropdown (if format supports quality)
                        if (uiState.outputFormat.supportsQuality) {
                            DropdownSection(
                                title = "Compression Quality",
                                subtitle = "${uiState.quality}%",
                                icon = Icons.Default.Tune,
                                expanded = isQualityExpanded,
                                onExpandedChange = { isQualityExpanded = it }
                            ) {
                                Text(
                                    text = "Higher percentage maintains better fidelity with larger file size.",
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

                        // 4. JPEG Background Color Dropdown (if format is JPEG)
                        if (uiState.outputFormat == OutputFormat.JPEG) {
                            DropdownSection(
                                title = "Transparency Background (JPEG)",
                                subtitle = uiState.jpegBackgroundColor.displayName,
                                icon = Icons.Default.Tune,
                                expanded = isBackgroundExpanded,
                                onExpandedChange = { isBackgroundExpanded = it }
                            ) {
                                Text(
                                    text = "JPEG lacks transparency support. Transparent areas will fill with this color.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                JpegBackgroundColor.entries.forEach { bgColor ->
                                    LabeledRadioButton(
                                        enabled = !uiState.isProcessing,
                                        selected = uiState.jpegBackgroundColor == bgColor,
                                        onClick = { viewModel.updateJpegBackgroundColor(bgColor) }
                                    ) {
                                        Text(
                                            text = bgColor.displayName,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    }
                                }
                            }
                        }

                        // 5. Concurrency & Metadata Dropdown
                        DropdownSection(
                            title = "Threads & Metadata",
                            subtitle = "${uiState.concurrency} threads • " + if (uiState.preserveMetadata) "EXIF preserved" else "EXIF stripped",
                            icon = Icons.Default.Settings,
                            expanded = isAdvancedOptionsExpanded,
                            onExpandedChange = { isAdvancedOptionsExpanded = it }
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Parallel Threads: ${uiState.concurrency} (Max $MAX_BATCH_CONCURRENCY)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Controls how many images process concurrently in parallel.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = uiState.concurrency.toFloat(),
                                    onValueChange = { viewModel.updateConcurrency(it.toInt()) },
                                    valueRange = 1f..MAX_BATCH_CONCURRENCY.toFloat(),
                                    steps = MAX_BATCH_CONCURRENCY - 2,
                                    enabled = !uiState.isProcessing && !uiState.isLoading
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Preserve EXIF Metadata",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Retains orientation, date, and camera metadata.",
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
            } else {
                // Advanced Settings (Collapsible Card) for Lossless mode
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Advanced & Performance Options",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            IconButton(onClick = { isAdvancedExpanded = !isAdvancedExpanded }) {
                                Icon(
                                    imageVector = if (isAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isAdvancedExpanded) "Collapse" else "Expand"
                                )
                            }
                        }

                        AnimatedVisibility(visible = isAdvancedExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Parallel Threads: ${uiState.concurrency} (Max $MAX_BATCH_CONCURRENCY)",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = "Controls how many images process concurrently in parallel.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Slider(
                                        value = uiState.concurrency.toFloat(),
                                        onValueChange = { viewModel.updateConcurrency(it.toInt()) },
                                        valueRange = 1f..MAX_BATCH_CONCURRENCY.toFloat(),
                                        steps = MAX_BATCH_CONCURRENCY - 2,
                                        enabled = !uiState.isProcessing && !uiState.isLoading
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Preserve EXIF Metadata",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "Retains orientation, date, and camera metadata.",
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
            }
        }
    }
}
