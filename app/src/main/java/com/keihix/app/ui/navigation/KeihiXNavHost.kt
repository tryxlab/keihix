package com.keihix.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.keihix.app.KeihiXApplication
import com.keihix.app.ui.screens.disclaimer.DisclaimerScreen
import com.keihix.app.ui.screens.disclaimer.DisclaimerViewModel
import com.keihix.app.ui.screens.settings.SettingsScreen
import com.keihix.app.ui.screens.settings.SettingsViewModel

sealed class Screen(val route: String) {
    object Disclaimer : Screen("disclaimer")
    object Home : Screen("home")
    object Settings : Screen("settings")
}

@Composable
fun KeihiXNavHost(
    navController: NavHostController,
    app: KeihiXApplication
) {
    val hasAgreedDisclaimer by app.preferencesRepository.hasAgreedDisclaimer.collectAsState()
    val startDestination = if (hasAgreedDisclaimer) Screen.Settings.route else Screen.Disclaimer.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Disclaimer.route) {
            val disclaimerViewModel = DisclaimerViewModel(app.preferencesRepository)
            DisclaimerScreen(
                viewModel = disclaimerViewModel,
                onProceed = {
                    navController.navigate(Screen.Settings.route) {
                        popUpTo(Screen.Disclaimer.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel = SettingsViewModel(app.secureKeyManager, app.preferencesRepository)
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    // ナビゲーション戻り処理
                }
            )
        }
    }
}
