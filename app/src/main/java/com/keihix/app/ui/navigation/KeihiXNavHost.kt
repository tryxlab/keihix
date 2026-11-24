package com.keihix.app.ui.navigation

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.keihix.app.KeihiXApplication
import com.keihix.app.data.drive.GoogleAuthStatus
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
    val pendingSharedUri by app.pendingSharedUri.collectAsState()
    val authStatus by app.googleAuthManager.authStatus.collectAsState()
    val isDriveConnected = authStatus is GoogleAuthStatus.Connected
    val consentIntent by app.googleAuthManager.consentRequiredIntent.collectAsState()

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 同意完了後、UI 側の状態更新を促すため必要に応じて処理継続可能
        }
    }

    LaunchedEffect(consentIntent) {
        val intent = consentIntent
        if (intent != null) {
            app.googleAuthManager.consumeConsentIntent()
            consentLauncher.launch(intent)
        }
    }

    // 起動時の初期遷移先判定:
    // 免責未同意なら Disclaimer 画面へ。
    // 同意済みなら Home (抽出) 画面へ（APIキー未設定でもデモや設定確認が可能）。
    val startDestination = if (!hasAgreedDisclaimer) Screen.Disclaimer.route else Screen.Home.route

    // 設定画面など別画面を開いている最中に共有インテントが届いた場合は Home へ戻す
    LaunchedEffect(pendingSharedUri, hasAgreedDisclaimer) {
        if (pendingSharedUri != null && hasAgreedDisclaimer) {
            if (navController.currentDestination?.route != Screen.Home.route) {
                navController.navigate(Screen.Home.route) {
                    popUpTo(Screen.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Disclaimer.route) {
            val disclaimerViewModel = remember { DisclaimerViewModel(app.preferencesRepository) }
            DisclaimerScreen(
                viewModel = disclaimerViewModel,
                onProceed = {
                    val needsInitialSetup = !app.secureKeyManager.hasApiKey() ||
                        app.googleAuthManager.authStatus.value !is GoogleAuthStatus.Connected
                    val nextRoute = if (needsInitialSetup) Screen.Settings.route else Screen.Home.route
                    navController.navigate(nextRoute) {
                        popUpTo(Screen.Disclaimer.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val context = LocalContext.current
            val extractionViewModel = remember {
                ExtractionViewModel(
                    parser = app.transactionParser,
                    driveRepository = app.driveStorageRepository,
                    preferencesRepository = app.preferencesRepository
                )
            }

            // 共有インテント (ACTION_SEND) で受け取った画像・PDFを自動解析
            LaunchedEffect(pendingSharedUri) {
                val uri = pendingSharedUri
                if (uri != null) {
                    app.consumeSharedUri()
                    extractionViewModel.processUri(context, uri)
                }
            }

            ExtractionScreen(
                viewModel = extractionViewModel,
                isGeminiKeyConfigured = app.secureKeyManager.hasApiKey(),
                isDriveConnected = isDriveConnected,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val settingsViewModel = remember {
                SettingsViewModel(
                    secureKeyManager = app.secureKeyManager,
                    preferencesRepository = app.preferencesRepository,
                    googleAuthManager = app.googleAuthManager
                )
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateBack = {
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    }
                }
            )
        }
    }
}
