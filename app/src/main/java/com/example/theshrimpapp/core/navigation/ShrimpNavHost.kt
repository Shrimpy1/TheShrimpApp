package com.example.theshrimpapp.core.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.theshrimpapp.core.ui.ShrimpTopAppBar
import com.example.theshrimpapp.features.files.ui.FilesScreen
import com.example.theshrimpapp.features.home.ui.HomeScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.ImageOptimizerScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.OutputSelectionScreen
import kotlinx.coroutines.launch

@Composable
fun ShrimpNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = ShrimpTopLevelDestination.Home.route,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentDestination = ShrimpTopLevelDestination.ALL.find { it.route == currentRoute }
        ?: ShrimpTopLevelDestination.Home

    val onNavigateToDestination: (ShrimpTopLevelDestination) -> Unit = { destination ->
        scope.launch { drawerState.close() }
        if (currentRoute != destination.route) {
            navController.navigate(destination.route) {
                popUpTo(ShrimpTopLevelDestination.Home.route) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "The Shrimp App",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                ShrimpTopLevelDestination.ALL.forEach { destination ->
                    val selected = currentRoute == destination.route
                    NavigationDrawerItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(text = destination.label) },
                        selected = selected,
                        onClick = { onNavigateToDestination(destination) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        },
        modifier = modifier
    ) {
        Scaffold(
            topBar = {
                ShrimpTopAppBar(
                    title = currentDestination.title,
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open Navigation Drawer"
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(ShrimpTopLevelDestination.Home.route) {
                    HomeScreen(
                        onNavigateToImageOptimizer = {
                            onNavigateToDestination(ShrimpTopLevelDestination.ImageOptimizer)
                        },
                        onNavigateToFiles = {
                            onNavigateToDestination(ShrimpTopLevelDestination.Files)
                        }
                    )
                }

                composable(ShrimpTopLevelDestination.ImageOptimizer.route) {
                    ImageOptimizerScreen(
                        onNavigateToOutput = {
                            navController.navigate("image_optimizer_output")
                        }
                    )
                }

                composable("image_optimizer_output") {
                    OutputSelectionScreen(
                        onBackClick = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(ShrimpTopLevelDestination.Files.route) {
                    FilesScreen()
                }
            }
        }
    }
}
