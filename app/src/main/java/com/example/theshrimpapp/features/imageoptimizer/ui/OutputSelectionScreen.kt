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
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.theshrimpapp.components.DropdownSection
import com.example.theshrimpapp.components.LabeledCheckbox
import com.example.theshrimpapp.components.LabeledRadioButton
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizerDefaults
import com.example.theshrimpapp.features.imageoptimizer.domain.ImageOptimizerLimits
import com.example.theshrimpapp.features.imageoptimizer.domain.JpegBackgroundColor
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputDestinationMode
import com.example.theshrimpapp.features.imageoptimizer.domain.OutputFormat
import com.example.theshrimpapp.features.imageoptimizer.domain.ProcessingMode
import com.example.theshrimpapp.features.imageoptimizer.domain.PostOptimizationPolicy

@Composable
fun OutputSelectionScreen(
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit,
    onNavigateToProcessing: () -> Unit = {},
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
    var isPostOptimizationExpanded by remember { mutableStateOf(false) }
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
                        modifier = Modifier.padding(bottom = 8.dp),
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
                        val hasSourceFolder =
                            uiState.selectedFolders.isNotEmpty() || uiState.selectedFolderUri != null
                        val isModeEnabled =
                            !uiState.isProcessing && !uiState.isLoading && when (mode) {
                                OutputDestinationMode.REPLACE_ORIGINAL, OutputDestinationMode.SUBFOLDER -> hasSourceFolder
                                else -> true
                            }

                        LabeledRadioButton(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp),
                            selected = isSelected,
                            enabled = isModeEnabled,
                            onClick = { viewModel.updateDestinationMode(mode) }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = mode.displayName,
                                    style = MaterialTheme.typography.bodyMedium
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
                        modifier = Modifier.padding(bottom = 8.dp),
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp),
                            enabled = !uiState.isProcessing,
                            selected = uiState.processingMode == mode,
                            onClick = { viewModel.updateProcessingMode(mode) }
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = when (mode) {
                                        ProcessingMode.LOSSLESS_OPTIMIZE -> "Lossless Optimization"
                                        ProcessingMode.REDUCE_SIZE -> "Custom Compression"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // SECTION 3 & 4: Advanced & Performance Options / Subsections
            DropdownSection(
                title = "Advanced & Performance Options",
                subtitle = if (uiState.processingMode == ProcessingMode.REDUCE_SIZE) {
                    "Format, dimensions, quality, threads & metadata"
                } else {
                    "${uiState.concurrency} threads • " + if (uiState.preserveMetadata) "EXIF preserved" else "EXIF stripped"
                },
                icon = Icons.Default.Settings,
                expanded = isAdvancedExpanded,
                onExpandedChange = { isAdvancedExpanded = it },
                containerColor = Color.Transparent
            ) {
                if (uiState.processingMode == ProcessingMode.REDUCE_SIZE) {
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
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    val desc = when (format) {
                                        OutputFormat.JPEG -> "Universal compatibility, lossy compression."
                                        OutputFormat.WEBP_LOSSY -> "Modern WebP format, smaller files, high quality."
                                        OutputFormat.WEBP_LOSSLESS -> "Lossless WebP, preserves exact pixels."
                                        OutputFormat.PNG -> "Lossless PNG, preserves transparency."
                                    }
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.labelSmall,
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
                        LabeledCheckbox(
                            checked = uiState.keepOriginalSize,
                            onCheckedChange = { viewModel.updateKeepOriginalSize(it) },
                            enabled = !uiState.isProcessing
                        ) {
                            Text("Keep original dimensions")
                        }

                        val presets = ImageOptimizerDefaults.DIMENSION_PRESETS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            presets.forEach { preset ->
                                FilterChip(
                                    enabled = !uiState.isProcessing && !uiState.keepOriginalSize,
                                    selected = !uiState.keepOriginalSize && uiState.maxDimension == preset,
                                    onClick = { viewModel.updateMaxDimension(preset) },
                                    label = { Text("$preset") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = uiState.maxDimensionInput,
                            onValueChange = { viewModel.updateMaxDimensionInput(it) },
                            label = { Text("Custom Value (px)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .fillMaxWidth(),
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
                            Slider(
                                enabled = !uiState.isProcessing,
                                value = uiState.quality.toFloat(),
                                onValueChange = { viewModel.updateQuality(it.toInt()) },
                                valueRange = ImageOptimizerLimits.MIN_QUALITY.toFloat()..ImageOptimizerLimits.MAX_QUALITY.toFloat(),
                                steps = (ImageOptimizerLimits.MAX_QUALITY - ImageOptimizerLimits.MIN_QUALITY - 1).coerceAtLeast(
                                    0
                                ),
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

                    // 5. Processing Speed Policy Dropdown
                    DropdownSection(
                        title = "Processing Speed Policy",
                        subtitle = when (uiState.postOptimizationPolicy) {
                            PostOptimizationPolicy.SKIP -> "Faster processing"
                            PostOptimizationPolicy.TRY_LOSSLESS -> "Try further size reduction"
                        },
                        icon = Icons.Default.Tune,
                        expanded = isPostOptimizationExpanded,
                        onExpandedChange = { isPostOptimizationExpanded = it }
                    ) {
                        Text(
                            text = "Choose whether to attempt an additional lossless optimization pass. The second option takes longer and may save no additional bytes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        PostOptimizationPolicy.entries.forEach { policy ->
                            LabeledRadioButton(
                                enabled = !uiState.isProcessing,
                                selected = uiState.postOptimizationPolicy == policy,
                                onClick = { viewModel.updatePostOptimizationPolicy(policy) }
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = when (policy) {
                                            PostOptimizationPolicy.SKIP -> "Faster processing"
                                            PostOptimizationPolicy.TRY_LOSSLESS -> "Try further size reduction"
                                        },
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = when (policy) {
                                            PostOptimizationPolicy.SKIP -> "Skips secondary lossless re-compression for maximum batch speed."
                                            PostOptimizationPolicy.TRY_LOSSLESS -> "Attempts secondary lossless optimization to minimize file size further."
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // 6. Concurrency & Metadata Dropdown
                    DropdownSection(
                        title = "Threads & Metadata",
                        subtitle = "${uiState.concurrency} threads • " + if (uiState.preserveMetadata) "EXIF preserved" else "EXIF stripped",
                        icon = Icons.Default.Settings,
                        expanded = isAdvancedOptionsExpanded,
                        onExpandedChange = { isAdvancedOptionsExpanded = it }
                    ) {
                        ConcurrencyAndMetadataContent(uiState = uiState, viewModel = viewModel)
                    }
                } else {
                    ConcurrencyAndMetadataContent(uiState = uiState, viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun ConcurrencyAndMetadataContent(
    uiState: ImageOptimizerUiState,
    viewModel: ImageOptimizerViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Parallel Threads: ${uiState.concurrency} (Max ${ImageOptimizerLimits.MAX_CONCURRENCY})",
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
            valueRange = ImageOptimizerLimits.MIN_CONCURRENCY.toFloat()..ImageOptimizerLimits.MAX_CONCURRENCY.toFloat(),
            steps = (ImageOptimizerLimits.MAX_CONCURRENCY - ImageOptimizerLimits.MIN_CONCURRENCY - 1).coerceAtLeast(
                0
            ),
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
