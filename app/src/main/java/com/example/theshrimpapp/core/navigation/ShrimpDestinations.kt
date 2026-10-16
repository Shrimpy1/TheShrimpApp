package com.example.theshrimpapp.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

sealed class ShrimpTopLevelDestination(
    val route: String,
    val title: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    object Home : ShrimpTopLevelDestination(
        route = "home",
        title = "The Shrimp App",
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    )

    object ImageOptimizer : ShrimpTopLevelDestination(
        route = "image_optimizer",
        title = "Image Optimizer",
        label = "Optimizer",
        selectedIcon = Icons.Filled.PhotoLibrary,
        unselectedIcon = Icons.Outlined.PhotoLibrary
    ) {
        const val OUTPUT_ROUTE = "image_optimizer_output"
        const val PROCESSING_ROUTE = "image_optimizer_processing"
    }

    object Files : ShrimpTopLevelDestination(
        route = "files",
        title = "Files",
        label = "Files",
        selectedIcon = Icons.Filled.Folder,
        unselectedIcon = Icons.Outlined.Folder
    )

    companion object {
        val ALL: List<ShrimpTopLevelDestination> get() = listOf(Home, ImageOptimizer, Files)
        const val HOME_ROUTE = "home"
        const val IMAGE_OPTIMIZER_ROUTE = "image_optimizer"
        const val FILES_ROUTE = "files"

        fun fromRoute(route: String?): ShrimpTopLevelDestination {
            return when (route) {
                ImageOptimizer.OUTPUT_ROUTE, ImageOptimizer.PROCESSING_ROUTE -> ImageOptimizer
                else -> ALL.find { it.route == route } ?: Home
            }
        }
    }
}
