package com.example.theshrimpapp.features.imageoptimizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ImageOptimizerScreen(
    modifier: Modifier = Modifier,
    viewModel: ImageOptimizerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
