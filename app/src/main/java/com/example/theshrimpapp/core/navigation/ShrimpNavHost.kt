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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.theshrimpapp.core.ui.ShrimpTopAppBar
import com.example.theshrimpapp.features.files.ui.FilesScreen
import com.example.theshrimpapp.features.home.ui.HomeScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.ImageOptimizerScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.ImageOptimizerViewModel
import com.example.theshrimpapp.features.imageoptimizer.ui.OutputSelectionScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.ProcessingScreen
import com.example.theshrimpapp.features.imageoptimizer.ui.ResultScreen
import kotlinx.coroutines.launch

@Composable
fun ShrimpNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = ShrimpTopLevelDestination.Home.route,
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentDestination = ShrimpTopLevelDestination.fromRoute(currentRoute)
    val title = currentDestination.title

    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) {
                launchSingleTop = true
            }
            onRouteConsumed()
        }
    }

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
                    title = title,
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
                            navController.navigate(ShrimpTopLevelDestination.ImageOptimizer.OUTPUT_ROUTE)
                        },
                        onNavigateToProcessing = {
                            navController.navigate(ShrimpTopLevelDestination.ImageOptimizer.PROCESSING_ROUTE)
                        },
                        onNavigateToResult = {
                            navController.navigate(ShrimpTopLevelDestination.ImageOptimizer.RESULT_ROUTE)
                        }
                    )
                }

                composable(ShrimpTopLevelDestination.ImageOptimizer.OUTPUT_ROUTE) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(ShrimpTopLevelDestination.ImageOptimizer.route)
                    }
                    val viewModel: ImageOptimizerViewModel = hiltViewModel(parentEntry)
                    OutputSelectionScreen(
                        onBackClick = {
                            navController.popBackStack()
                        },
                        onNavigateToProcessing = {
                            navController.navigate(ShrimpTopLevelDestination.ImageOptimizer.PROCESSING_ROUTE)
                        },
                        viewModel = viewModel
                    )
                }

                composable(ShrimpTopLevelDestination.ImageOptimizer.PROCESSING_ROUTE) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(ShrimpTopLevelDestination.ImageOptimizer.route)
                    }
                    val viewModel: ImageOptimizerViewModel = hiltViewModel(parentEntry)
                    ProcessingScreen(
                        onNavigateToResult = {
                            navController.navigate(ShrimpTopLevelDestination.ImageOptimizer.RESULT_ROUTE) {
                                popUpTo(ShrimpTopLevelDestination.ImageOptimizer.PROCESSING_ROUTE) {
                                    inclusive = true
                                }
                            }
                        },
                        viewModel = viewModel
                    )
                }

                composable(ShrimpTopLevelDestination.ImageOptimizer.RESULT_ROUTE) { backStackEntry ->
                    val parentEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(ShrimpTopLevelDestination.ImageOptimizer.route)
                    }
                    val viewModel: ImageOptimizerViewModel = hiltViewModel(parentEntry)
                    ResultScreen(
                        onBackClick = {
                            navController.popBackStack(
                                route = ShrimpTopLevelDestination.ImageOptimizer.route,
                                inclusive = false
                            )
                        },
                        viewModel = viewModel
                    )
                }

                composable(ShrimpTopLevelDestination.Files.route) {
                    FilesScreen()
                }
            }
        }
    }
}
