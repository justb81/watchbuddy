package com.justb81.watchbuddy.phone.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists Trakt OAuth tokens and the self-hosted `client_secret` override.
 *
 * Storage is implemented with Tink's AEAD primitive (AES-256-GCM) keyed by a Tink keyset
 * that is itself wrapped by an Android Keystore master key. The keyset + master-key
 * plumbing lives in [TokenAeadFactory]; this class consumes the primitive via
 * dependency injection so unit tests can inject a fake.
 *
 * On first launch after an upgrade from a build that used
 * `androidx.security:security-crypto` (deprecated, #430) the legacy
 * `watchbuddy_tokens.xml` shared-prefs file is deleted — the user has to sign in again
 * once. This avoids dragging the abandoned security-crypto dependency into the APK just
 * to decrypt a single file.
 */
@Singleton
class TokenRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val aead: Aead,
) {

    private val prefs: SharedPreferences

    init {
        migrateLegacyStoreIfPresent(context)
        prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        DiagnosticLog.event(TAG, "init: ready (file=$PREFS_FILE)")
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1_000L
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, encrypt(KEY_ACCESS_TOKEN, accessToken))
            putString(KEY_REFRESH_TOKEN, encrypt(KEY_REFRESH_TOKEN, refreshToken))
            putString(KEY_EXPIRES_AT, encrypt(KEY_EXPIRES_AT, expiresAt.toString()))
        }
    }

    fun getAccessToken(): String? = decrypt(KEY_ACCESS_TOKEN, prefs.getString(KEY_ACCESS_TOKEN, null))

    fun getRefreshToken(): String? = decrypt(KEY_REFRESH_TOKEN, prefs.getString(KEY_REFRESH_TOKEN, null))

    fun isTokenValid(): Boolean {
        val token = getAccessToken() ?: return false
        if (token.isBlank()) return false
        val expiresAt = readExpiresAt()
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
        val expiresAt = readExpiresAt()
        return System.currentTimeMillis() + bufferMs >= expiresAt
    }

    fun clearTokens() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_EXPIRES_AT)
        }
    }

    fun getClientSecret(): String = decrypt(KEY_CLIENT_SECRET, prefs.getString(KEY_CLIENT_SECRET, null)) ?: ""

    fun saveClientSecret(secret: String) {
        prefs.edit { putString(KEY_CLIENT_SECRET, encrypt(KEY_CLIENT_SECRET, secret)) }
    }

    private fun readExpiresAt(): Long =
        decrypt(KEY_EXPIRES_AT, prefs.getString(KEY_EXPIRES_AT, null))?.toLongOrNull() ?: 0L

    private fun encrypt(key: String, value: String): String {
        val ciphertext = aead.encrypt(value.toByteArray(StandardCharsets.UTF_8), key.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    private fun decrypt(key: String, encoded: String?): String? {
        if (encoded == null) return null
        return runCatching {
            val ciphertext = Base64.getDecoder().decode(encoded)
            aead.decrypt(ciphertext, key.toByteArray(StandardCharsets.UTF_8)).toString(StandardCharsets.UTF_8)
        }.getOrElse {
            DiagnosticLog.event(TAG, "decrypt failed for key=$key (${it.javaClass.simpleName})")
            null
        }
    }

    /**
     * One-shot migration from the legacy `EncryptedSharedPreferences` file written by
     * `androidx.security:security-crypto` in earlier builds. We cannot decrypt the old
     * ciphertext without dragging the deprecated library back in, so the file is deleted
     * outright — the user has to sign in again once. The event is logged via
     * [DiagnosticLog] so upgrade rollouts can be audited.
     */
    private fun migrateLegacyStoreIfPresent(context: Context) {
        val legacyFile = LEGACY_PREFS_FILE
        val exists = context.getSharedPreferences(legacyFile, Context.MODE_PRIVATE).all.isNotEmpty()
        if (!exists) return
        val deleted = context.deleteSharedPreferences(legacyFile)
        DiagnosticLog.event(TAG, "migrated legacy EncryptedSharedPreferences (deleted=$deleted); user must re-sign-in")
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_CLIENT_SECRET = "trakt_client_secret"
        const val TAG = "TokenRepository"

        const val PREFS_FILE = "watchbuddy_tokens_v2"
        const val LEGACY_PREFS_FILE = "watchbuddy_tokens"
    }
}
