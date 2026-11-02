package com.keihix.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.keihix.app.data.drive.GoogleAuthManager
import com.keihix.app.data.drive.GoogleAuthStatus
import com.keihix.app.data.preference.UserPreferencesRepository
import com.keihix.app.data.security.SecureKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ApiKeyTestStatus {
    object Idle : ApiKeyTestStatus
    object Testing : ApiKeyTestStatus
    object Success : ApiKeyTestStatus
    data class Error(val message: String) : ApiKeyTestStatus
}

data class SettingsUiState(
    val currentApiKeyInput: String = "",
    val maskedApiKey: String = "",
    val hasApiKey: Boolean = false,
    val testStatus: ApiKeyTestStatus = ApiKeyTestStatus.Idle,
    val isPasswordVisible: Boolean = false,
    val googleAuthStatus: GoogleAuthStatus = GoogleAuthStatus.NotConnected,
    val targetYear: Int = 2026,
    val showKiteiDialog: Boolean = false
)

class SettingsViewModel(
    private val secureKeyManager: SecureKeyManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val googleAuthManager: GoogleAuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refreshState()
        // GoogleAuthManager の状態を監視
        viewModelScope.launch {
            googleAuthManager.authStatus.collect { authStatus ->
                _uiState.update { it.copy(googleAuthStatus = authStatus) }
            }
        }
    }

    fun refreshState() {
        val hasKey = secureKeyManager.hasApiKey()
        val masked = secureKeyManager.getMaskedApiKey()
        _uiState.update {
            it.copy(
                hasApiKey = hasKey,
                maskedApiKey = masked,
                currentApiKeyInput = if (hasKey) "" else it.currentApiKeyInput,
                targetYear = preferencesRepository.targetYear.value,
                googleAuthStatus = googleAuthManager.authStatus.value
            )
        }
    }

    fun onApiKeyInputChange(newValue: String) {
        _uiState.update {
            it.copy(
                currentApiKeyInput = newValue,
                testStatus = ApiKeyTestStatus.Idle
            )
        }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun testAndSaveApiKey() {
        val inputKey = _uiState.value.currentApiKeyInput.trim()
        val keyToTest = if (inputKey.isNotBlank()) inputKey else secureKeyManager.getGeminiApiKey().orEmpty()

        if (keyToTest.isBlank()) {
            _uiState.update { it.copy(testStatus = ApiKeyTestStatus.Error("APIキーを入力してください")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(testStatus = ApiKeyTestStatus.Testing) }
            val result = secureKeyManager.testConnection(keyToTest)
            when (result) {
                is SecureKeyManager.ConnectionTestResult.Success -> {
                    if (inputKey.isNotBlank()) {
                        secureKeyManager.saveGeminiApiKey(inputKey)
                    }
                    _uiState.update {
                        it.copy(
                            testStatus = ApiKeyTestStatus.Success,
                            hasApiKey = true,
                            maskedApiKey = secureKeyManager.getMaskedApiKey(),
                            currentApiKeyInput = ""
                        )
                    }
                }
                is SecureKeyManager.ConnectionTestResult.Error -> {
                    _uiState.update { it.copy(testStatus = ApiKeyTestStatus.Error(result.message)) }
                }
            }
        }
    }

    fun clearApiKey() {
        secureKeyManager.clearApiKey()
        _uiState.update {
            it.copy(
                hasApiKey = false,
                maskedApiKey = "未設定",
                currentApiKeyInput = "",
                testStatus = ApiKeyTestStatus.Idle
            )
        }
    }

    fun onGoogleAccountSelected(accountName: String) {
        googleAuthManager.setSelectedAccountName(accountName)
    }

    fun signOutGoogle() {
        googleAuthManager.signOut()
    }

    fun getGoogleSignInIntent() = googleAuthManager.createSignInIntent()

    fun showKiteiDialog() {
        _uiState.update { it.copy(showKiteiDialog = true) }
    }

    fun hideKiteiDialog() {
        _uiState.update { it.copy(showKiteiDialog = false) }
    }
}
