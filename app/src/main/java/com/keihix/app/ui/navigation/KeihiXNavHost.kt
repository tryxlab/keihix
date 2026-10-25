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
import com.keihix.app.ui.screens.extraction.ExtractionScreen
import com.keihix.app.ui.screens.extraction.ExtractionViewModel
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
    val hasApiKey = app.secureKeyManager.hasApiKey()

    // 免責未同意ならDisclaimer、同意済みでAPIキー未設定ならSettings、設定済みならHome
    val startDestination = when {
        !hasAgreedDisclaimer -> Screen.Disclaimer.route
        !hasApiKey -> Screen.Settings.route
        else -> Screen.Home.route
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Disclaimer.route) {
            val disclaimerViewModel = DisclaimerViewModel(app.preferencesRepository)
            DisclaimerScreen(
                viewModel = disclaimerViewModel,
                onProceed = {
                    val nextScreen = if (app.secureKeyManager.hasApiKey()) Screen.Home.route else Screen.Settings.route
                    navController.navigate(nextScreen) {
                        popUpTo(Screen.Disclaimer.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val extractionViewModel = ExtractionViewModel(app.transactionParser)
            ExtractionScreen(
                viewModel = extractionViewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel = SettingsViewModel(app.secureKeyManager, app.preferencesRepository)
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Settings.route) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}
