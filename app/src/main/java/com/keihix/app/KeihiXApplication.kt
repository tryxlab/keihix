package com.keihix.app

import android.app.Application
import android.net.Uri
import com.keihix.app.data.ai.GeminiTransactionAdvisor
import com.keihix.app.data.ai.GeminiTransactionParser
import com.keihix.app.data.drive.GoogleAuthManager
import com.keihix.app.data.drive.GoogleDriveStorageRepository
import com.keihix.app.data.preference.UserPreferencesRepository
import com.keihix.app.data.security.SecureKeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class KeihiXApplication : Application() {

    lateinit var secureKeyManager: SecureKeyManager
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var transactionParser: GeminiTransactionParser
        private set

    lateinit var transactionAdvisor: GeminiTransactionAdvisor
        private set

    lateinit var googleAuthManager: GoogleAuthManager
        private set

    lateinit var driveStorageRepository: GoogleDriveStorageRepository
        private set

    private val _pendingSharedUri = MutableStateFlow<Uri?>(null)
    val pendingSharedUri: StateFlow<Uri?> = _pendingSharedUri.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this

        secureKeyManager = SecureKeyManager(this)
        preferencesRepository = UserPreferencesRepository(this)
        transactionParser = GeminiTransactionParser(secureKeyManager)
        transactionAdvisor = GeminiTransactionAdvisor(secureKeyManager)
        googleAuthManager = GoogleAuthManager(this)
        driveStorageRepository = GoogleDriveStorageRepository(
            driveServiceProvider = { googleAuthManager.getDriveService() },
            onConsentRequired = { intent -> googleAuthManager.notifyConsentRequired(intent) }
        )
    }

    fun submitSharedUri(uri: Uri) {
        _pendingSharedUri.value = uri
    }

    fun consumeSharedUri() {
        _pendingSharedUri.value = null
    }

    companion object {
        lateinit var instance: KeihiXApplication
            private set
    }
}
