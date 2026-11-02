package com.keihix.app

import android.app.Application
import com.keihix.app.data.ai.GeminiTransactionParser
import com.keihix.app.data.drive.GoogleAuthManager
import com.keihix.app.data.drive.GoogleDriveStorageRepository
import com.keihix.app.data.preference.UserPreferencesRepository
import com.keihix.app.data.security.SecureKeyManager

class KeihiXApplication : Application() {

    lateinit var secureKeyManager: SecureKeyManager
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    lateinit var transactionParser: GeminiTransactionParser
        private set

    lateinit var googleAuthManager: GoogleAuthManager
        private set

    lateinit var driveStorageRepository: GoogleDriveStorageRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        secureKeyManager = SecureKeyManager(this)
        preferencesRepository = UserPreferencesRepository(this)
        transactionParser = GeminiTransactionParser(secureKeyManager)
        googleAuthManager = GoogleAuthManager(this)
        driveStorageRepository = GoogleDriveStorageRepository(
            driveServiceProvider = { googleAuthManager.getDriveService() }
        )
    }

    companion object {
        lateinit var instance: KeihiXApplication
            private set
    }
}
