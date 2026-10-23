package com.keihix.app.ui.screens.disclaimer

import androidx.lifecycle.ViewModel
import com.keihix.app.data.preference.UserPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DisclaimerUiState(
    val isTaxAdviceUnderstood: Boolean = false,
    val isKiteiUnderstood: Boolean = false,
    val isSelfResponsibilityAccepted: Boolean = false,
    val showKiteiDialog: Boolean = false
) {
    val canProceed: Boolean
        get() = isTaxAdviceUnderstood && isKiteiUnderstood && isSelfResponsibilityAccepted
}

class DisclaimerViewModel(
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DisclaimerUiState())
    val uiState: StateFlow<DisclaimerUiState> = _uiState.asStateFlow()

    fun toggleTaxAdvice() {
        _uiState.update { it.copy(isTaxAdviceUnderstood = !it.isTaxAdviceUnderstood) }
    }

    fun toggleKitei() {
        _uiState.update { it.copy(isKiteiUnderstood = !it.isKiteiUnderstood) }
    }

    fun toggleSelfResponsibility() {
        _uiState.update { it.copy(isSelfResponsibilityAccepted = !it.isSelfResponsibilityAccepted) }
    }

    fun showKiteiDialog() {
        _uiState.update { it.copy(showKiteiDialog = true) }
    }

    fun hideKiteiDialog() {
        _uiState.update { it.copy(showKiteiDialog = false) }
    }

    fun acceptDisclaimerAndProceed(onAccepted: () -> Unit) {
        if (_uiState.value.canProceed) {
            preferencesRepository.setDisclaimerAgreed(true)
            onAccepted()
        }
    }
}
