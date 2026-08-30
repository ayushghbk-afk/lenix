package com.lenix.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lenix.ui.screens.DesktopScreen
import com.lenix.ui.screens.HomeScreen
import com.lenix.ui.screens.InstallScreen
import com.lenix.ui.screens.InstanceScreen
import com.lenix.ui.screens.SettingsScreen
import com.lenix.ui.screens.TerminalScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val INSTANCES = "instances"
    const val INSTALL = "install"
    const val TERMINAL = "terminal"
    const val DESKTOP = "desktop"
}

/**
 * App shell and navigation. Screens are kept in separate files on purpose; do not
 * grow main activity logic here.
 */
@Composable
fun LenixApp() {
    val navController = rememberNavController()
    val application = LocalContext.current.applicationContext as Application
    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(application) }
    val homeUiState by homeViewModel.uiState.collectAsState()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                state = homeUiState,
                onInstall = homeViewModel::install,
                onCancelInstall = homeViewModel::cancelInstall,
                onStart = homeViewModel::start,
                onStop = homeViewModel::stop,
                onReset = homeViewModel::reset,
                onOpenInstance = { navController.navigate(Routes.INSTANCES) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = homeUiState.settings,
                onUpdate = homeViewModel::updateSettings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INSTANCES) {
            InstanceScreen(
                state = homeUiState,
                diskUsage = homeViewModel::diskUsageBytes,
                onSelect = homeViewModel::selectInstance,
                onCreate = homeViewModel::createInstance,
                onRename = homeViewModel::renameInstance,
                onDelete = homeViewModel::deleteInstance,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INSTALL) {
            InstallScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TERMINAL) {
            TerminalScreen(
                onBack = { navController.popBackStack() },
                onHome = { navController.popBackStack(Routes.HOME, inclusive = false) },
            )
        }
        composable(Routes.DESKTOP) {
            DesktopScreen(onBack = { navController.popBackStack() })
        }
    }
}
