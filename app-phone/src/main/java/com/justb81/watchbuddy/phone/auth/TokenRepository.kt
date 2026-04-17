package com.justb81.watchbuddy.phone.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.justb81.watchbuddy.core.logging.DiagnosticLog
class TokenRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        DiagnosticLog.event(TAG, "init: requesting Keystore master key")
        val masterKeyAlias = try {
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "MasterKeys.getOrCreate failed", e)
            throw e
        }
        DiagnosticLog.event(TAG, "init: opening EncryptedSharedPreferences")
        prefs = try {
            EncryptedSharedPreferences.create(
                "watchbuddy_tokens",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            DiagnosticLog.error(TAG, "EncryptedSharedPreferences.create failed", e)
            throw e
        }
        DiagnosticLog.event(TAG, "init: ready")
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1_000L
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun isTokenValid(): Boolean {
        val token = getAccessToken() ?: return false
        if (token.isBlank()) return false
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return System.currentTimeMillis() < expiresAt
    }

    /**
     * Returns `true` when the access token is missing, blank, already expired, or will
     * expire within [bufferMs] milliseconds. Used by [TokenRefreshManager] to trigger a
     * proactive refresh before the token actually expires.
     */
    fun isTokenExpiredOrExpiringSoon(bufferMs: Long): Boolean {
        val token = getAccessToken()
        if (token.isNullOrBlank()) return true
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        return System.currentTimeMillis() + bufferMs >= expiresAt
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun getClientSecret(): String = prefs.getString(KEY_CLIENT_SECRET, "") ?: ""

    fun saveClientSecret(secret: String) {
        prefs.edit().putString(KEY_CLIENT_SECRET, secret).apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_CLIENT_SECRET = "trakt_client_secret"
        const val TAG = "TokenRepository"
    }
}
