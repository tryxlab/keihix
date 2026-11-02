package com.keihix.app.data.drive

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Drive OAuth 2.0 認可およびアカウント状態を管理するクラス。
 * 最小権限原則に基づき、スコープは 'drive.file' のみを要求します。
 */
class GoogleAuthManager(
    private val context: Context
) {
    private val credential: GoogleAccountCredential = GoogleAccountCredential.usingOAuth2(
        context.applicationContext,
        listOf(DriveScopes.DRIVE_FILE)
    )

    private val _authStatus = MutableStateFlow<GoogleAuthStatus>(GoogleAuthStatus.NotConnected)
    val authStatus: StateFlow<GoogleAuthStatus> = _authStatus.asStateFlow()

    private var driveService: Drive? = null

    init {
        // 保存済みのアカウントがあれば復元
        val savedAccountName = getSavedAccountName()
        if (!savedAccountName.isNullOrBlank()) {
            setSelectedAccountName(savedAccountName)
        }
    }

    /**
     * アカウント選択用 Intent を生成
     */
    fun createSignInIntent(): Intent {
        return credential.newChooseAccountIntent()
    }

    /**
     * サインイン結果のアカウント名（メールアドレス）を設定
     */
    fun setSelectedAccountName(accountName: String) {
        try {
            credential.selectedAccountName = accountName
            driveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
            ).setApplicationName("KeihiX").build()

            saveAccountName(accountName)
            _authStatus.value = GoogleAuthStatus.Connected(email = accountName)
        } catch (e: Exception) {
            _authStatus.value = GoogleAuthStatus.Error(e.localizedMessage ?: "アカウント設定に失敗しました")
        }
    }

    /**
     * サインアウト（連携解除）
     */
    fun signOut() {
        credential.selectedAccount = null
        driveService = null
        clearSavedAccountName()
        _authStatus.value = GoogleAuthStatus.NotConnected
    }

    /**
     * 認可済み Drive サービスインスタンスを取得
     */
    fun getDriveService(): Drive? = driveService

    /**
     * 連携済みかどうか
     */
    fun isConnected(): Boolean = _authStatus.value is GoogleAuthStatus.Connected

    private fun getSavedAccountName(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CONNECTED_ACCOUNT, null)
    }

    private fun saveAccountName(email: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONNECTED_ACCOUNT, email).apply()
    }

    private fun clearSavedAccountName() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CONNECTED_ACCOUNT).apply()
    }

    companion object {
        private const val PREFS_NAME = "keihix_google_auth"
        private const val KEY_CONNECTED_ACCOUNT = "connected_google_account_email"
    }
}
