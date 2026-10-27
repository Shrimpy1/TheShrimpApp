package com.example.theshrimpapp.features.home.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.theshrimpapp.components.NavigationCard

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onNavigateToImageOptimizer: () -> Unit = {},
    onNavigateToFiles: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Utilities",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        NavigationCard(
            title = "Image Optimizer",
            icon = Icons.Default.PhotoLibrary,
            onClick = onNavigateToImageOptimizer,
        )

        NavigationCard(
            title = "Files",
            icon = Icons.Default.Folder,
            onClick = onNavigateToFiles,
        )
    }
}
