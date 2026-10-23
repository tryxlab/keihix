package com.keihix.app

import android.app.Application
import com.keihix.app.data.preference.UserPreferencesRepository
import com.keihix.app.data.security.SecureKeyManager

class KeihiXApplication : Application() {

    lateinit var secureKeyManager: SecureKeyManager
        private set

    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        secureKeyManager = SecureKeyManager(this)
        preferencesRepository = UserPreferencesRepository(this)
    }

    companion object {
        lateinit var instance: KeihiXApplication
            private set
    }
}
