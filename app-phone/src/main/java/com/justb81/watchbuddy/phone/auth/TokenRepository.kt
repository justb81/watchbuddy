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
 * plumbing lives in [TokenAeadModule]; this class consumes the primitive via
 * dependency injection so unit tests can inject a fake.
 *
 * ## Ciphertext versioning
 * All new encryptions write a `v1:` prefix before the Base64 ciphertext and use a
 * fixed AAD constant ([AEAD_FIXED_AAD]) independent of the storage key name. Values
 * written by earlier builds (no prefix, key-name AAD) are detected and transparently
 * re-encrypted on first read so the fleet self-migrates without a forced re-auth.
 *
 * ## Error handling
 * - AEAD primitive unavailable (Keystore locked / hardware failure): every call that
 *   touches the primitive throws [AuthUnavailableException]. Callers **must not** swallow
 *   this — it propagates to the UI, which shows a typed error message.
 * - Ciphertext corruption / AAD mismatch: [hadDecryptionFailure] is set to `true`, an
 *   ERROR-level breadcrumb is emitted via [DiagnosticLog], and the affected getter
 *   returns `null` so the app falls back to the sign-in screen with an informative banner.
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

    /**
     * Set to `true` the first time a stored ciphertext cannot be decrypted (excluding
     * the case where no value is stored at all). The UI layer reads this flag on the
     * sign-in screen to show an explanatory snackbar.
     */
    @Volatile
    var hadDecryptionFailure: Boolean = false
        private set

    init {
        migrateLegacyStoreIfPresent(context)
        prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        DiagnosticLog.event(TAG, "init: ready (file=$PREFS_FILE)")
    }

    fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + expiresIn * 1_000L
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, encrypt(accessToken))
            putString(KEY_REFRESH_TOKEN, encrypt(refreshToken))
            putString(KEY_EXPIRES_AT, encrypt(expiresAt.toString()))
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
     * expire within [bufferMs] milliseconds. Used by [com.justb81.watchbuddy.phone.auth.TokenRefreshManager]
     * to trigger a proactive refresh before the token actually expires.
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
        prefs.edit { putString(KEY_CLIENT_SECRET, encrypt(secret)) }
    }

    private fun readExpiresAt(): Long =
        decrypt(KEY_EXPIRES_AT, prefs.getString(KEY_EXPIRES_AT, null))?.toLongOrNull() ?: 0L

    /**
     * Encrypts [plaintext] with the fixed AAD constant and returns a versioned
     * storage string: `"v1:" + Base64(ciphertext)`.
     *
     * Throws [AuthUnavailableException] if the underlying AEAD primitive is
     * unavailable (propagated from [TokenAeadModule.BrokenAead]).
     */
    private fun encrypt(plaintext: String): String {
        val ciphertext = aead.encrypt(
            plaintext.toByteArray(StandardCharsets.UTF_8),
            AEAD_FIXED_AAD.toByteArray(StandardCharsets.UTF_8),
        )
        return V1_PREFIX + Base64.getEncoder().encodeToString(ciphertext)
    }

    /**
     * Decrypts a stored value, handling both the current v1 format and the legacy
     * key-name-AAD format written by earlier builds.
     *
     * - `v1:` prefix → strip prefix, Base64-decode, decrypt with [AEAD_FIXED_AAD].
     * - No prefix → Base64-decode, decrypt with [storageKey] as AAD (legacy), then
     *   immediately re-encrypt in v1 format so the value self-migrates.
     *
     * Returns `null` when [encoded] is `null` (nothing stored) or when decryption
     * fails due to ciphertext corruption — in the latter case [hadDecryptionFailure]
     * is set and an ERROR breadcrumb is logged.
     *
     * Throws [AuthUnavailableException] if the Keystore is unavailable.
     */
    private fun decrypt(storageKey: String, encoded: String?): String? {
        if (encoded == null) return null
        return try {
            if (encoded.startsWith(V1_PREFIX)) {
                val b64 = encoded.removePrefix(V1_PREFIX)
                val ciphertext = Base64.getDecoder().decode(b64)
                aead.decrypt(ciphertext, AEAD_FIXED_AAD.toByteArray(StandardCharsets.UTF_8))
                    .toString(StandardCharsets.UTF_8)
            } else {
                // Legacy format: no prefix, AAD was the storage key name.
                val ciphertext = Base64.getDecoder().decode(encoded)
                val plaintext = aead.decrypt(
                    ciphertext,
                    storageKey.toByteArray(StandardCharsets.UTF_8),
                ).toString(StandardCharsets.UTF_8)
                // Re-encrypt in v1 format (best-effort; failure is non-fatal here).
                runCatching {
                    prefs.edit { putString(storageKey, encrypt(plaintext)) }
                    DiagnosticLog.event(TAG, "migrated key=$storageKey to v1 AAD format")
                }.onFailure { e ->
                    DiagnosticLog.warn(TAG, "re-encrypt migration failed for key=$storageKey", e)
                }
                plaintext
            }
        } catch (e: AuthUnavailableException) {
            throw e
        } catch (e: Exception) {
            hadDecryptionFailure = true
            DiagnosticLog.error(
                TAG,
                "decrypt failed for key=$storageKey — re-auth required (${e.javaClass.simpleName})",
                e,
            )
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

        /** Prefix prepended to all v1-format stored ciphertexts. */
        const val V1_PREFIX = "v1:"

        /**
         * Fixed AAD used for all new encryptions. Independent of the storage key name
         * so key renames never invalidate existing ciphertext.
         */
        const val AEAD_FIXED_AAD = "watchbuddy_aead_v1"
    }
}
