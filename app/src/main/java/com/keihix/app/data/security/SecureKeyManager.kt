package com.keihix.app.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * BYOK (Bring Your Own Key) アーキテクチャの根幹となるセキュアストレージ。
 * Android Keystore と連携した EncryptedSharedPreferences を用い、
 * 端末内のハードウェアバックド暗号化領域にのみ Gemini APIキーを保持します。
 * 開発者サーバー等の外部中継インフラには一切送信されません。
 */
class SecureKeyManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /**
     * Gemini APIキーを暗号化保存する
     */
    fun saveGeminiApiKey(apiKey: String) {
        securePrefs.edit()
            .putString(KEY_GEMINI_API, apiKey.trim())
            .apply()
    }

    /**
     * 暗号化されたGemini APIキーを取得する
     */
    fun getGeminiApiKey(): String? {
        val key = securePrefs.getString(KEY_GEMINI_API, null)
        return if (!key.isNullOrBlank()) key else null
    }

    /**
     * APIキーが設定されているか
     */
    fun hasApiKey(): Boolean {
        return !getGeminiApiKey().isNullOrBlank()
    }

    /**
     * APIキーを削除する
     */
    fun clearApiKey() {
        securePrefs.edit()
            .remove(KEY_GEMINI_API)
            .apply()
    }

    /**
     * UI表示用のマスク文字列（例: AIzaSy...••••••••）を生成
     */
    fun getMaskedApiKey(): String {
        val key = getGeminiApiKey() ?: return "未設定"
        return if (key.length >= 10) {
            "${key.take(6)}...${"•".repeat(8)}"
        } else {
            "••••••••"
        }
    }

    /**
     * APIキーの形式簡易バリデーション (Google AI Studio のキーは通常 'AIzaSy' で始まる39文字)
     */
    fun isValidFormat(apiKey: String): Boolean {
        val trimmed = apiKey.trim()
        return trimmed.startsWith("AIzaSy") && trimmed.length in 35..45
    }

    /**
     * Gemini APIに対する実際の接続テスト（疎通確認）。
     * 軽量なモデル一覧取得 (models/gemini-1.5-flash) を行い、HTTP 200が返るか検証する。
     */
    suspend fun testConnection(apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext ConnectionTestResult.Error("APIキーが入力されていません")
        }

        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash?key=$trimmedKey")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Content-Type", "application/json")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                ConnectionTestResult.Success
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.readText()
                val message = try {
                    val json = JSONObject(errorStream ?: "")
                    json.getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP エラー: $responseCode"
                }
                ConnectionTestResult.Error(message)
            }
        } catch (e: Exception) {
            ConnectionTestResult.Error(e.localizedMessage ?: "通信に失敗しました")
        }
    }

    sealed interface ConnectionTestResult {
        object Success : ConnectionTestResult
        data class Error(val message: String) : ConnectionTestResult
    }

    companion object {
        private const val SECURE_PREFS_FILE_NAME = "keihix_secure_prefs"
        private const val KEY_GEMINI_API = "byok_gemini_api_key"
    }
}
