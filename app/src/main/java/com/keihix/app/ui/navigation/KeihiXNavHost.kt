package com.keihix.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
    navController: NavHostController = rememberNavController(),
    app: KeihiXApplication = KeihiXApplication.instance
) {
    val hasAgreedDisclaimer by app.preferencesRepository.hasAgreedDisclaimer.collectAsState()

    // 起動時の初期遷移先判定:
    // 免責未同意なら Disclaimer 画面へ。
    // 同意済みでAPIキー未設定なら Settings 画面、APIキー設定済みなら Home (抽出) 画面へ。
    val startDestination = when {
        !hasAgreedDisclaimer -> Screen.Disclaimer.route
        !app.secureKeyManager.hasApiKey() -> Screen.Settings.route
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
            val extractionViewModel = ExtractionViewModel(
                parser = app.transactionParser,
                driveRepository = app.driveStorageRepository,
                preferencesRepository = app.preferencesRepository
            )
            ExtractionScreen(
                viewModel = extractionViewModel,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel = SettingsViewModel(
                secureKeyManager = app.secureKeyManager,
                preferencesRepository = app.preferencesRepository,
                googleAuthManager = app.googleAuthManager
            )
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
