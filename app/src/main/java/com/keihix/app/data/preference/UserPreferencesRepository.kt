package com.keihix.app.data.preference

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Year

/**
 * アプリの基本設定・利用規約および免責事項の同意フラグを管理するリポジトリ。
 */
class UserPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _hasAgreedDisclaimer = MutableStateFlow(
        prefs.getBoolean(KEY_HAS_AGREED_DISCLAIMER, false)
    )
    val hasAgreedDisclaimer: StateFlow<Boolean> = _hasAgreedDisclaimer.asStateFlow()

    private val _targetYear = MutableStateFlow(
        prefs.getInt(KEY_TARGET_YEAR, Year.now().value)
    )
    val targetYear: StateFlow<Int> = _targetYear.asStateFlow()

    /**
     * 免責事項への同意状態を保存する
     */
    fun setDisclaimerAgreed(agreed: Boolean) {
        prefs.edit()
            .putBoolean(KEY_HAS_AGREED_DISCLAIMER, agreed)
            .putLong(KEY_DISCLAIMER_AGREED_TIMESTAMP, if (agreed) System.currentTimeMillis() else 0L)
            .apply()
        _hasAgreedDisclaimer.value = agreed
    }

    /**
     * 免責同意のタイムスタンプを取得（ミリ秒）
     */
    fun getDisclaimerAgreedTimestamp(): Long {
        return prefs.getLong(KEY_DISCLAIMER_AGREED_TIMESTAMP, 0L)
    }

    /**
     * 対象年度を設定
     */
    fun setTargetYear(year: Int) {
        prefs.edit().putInt(KEY_TARGET_YEAR, year).apply()
        _targetYear.value = year
    }

    companion object {
        private const val PREFS_NAME = "keihix_user_preferences"
        private const val KEY_HAS_AGREED_DISCLAIMER = "has_agreed_disclaimer"
        private const val KEY_DISCLAIMER_AGREED_TIMESTAMP = "disclaimer_agreed_timestamp"
        private const val KEY_TARGET_YEAR = "target_accounting_year"
    }
}
