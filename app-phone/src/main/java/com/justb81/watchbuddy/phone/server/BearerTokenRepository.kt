package com.justb81.watchbuddy.phone.server

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.Aead
import com.justb81.watchbuddy.core.discovery.BleDiscoveryContract
import com.justb81.watchbuddy.core.logging.DiagnosticLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates and persists the per-device bearer token used to authenticate
 * TV→phone HTTP requests.
 *
 * The token is [TOKEN_BYTES] random bytes from [SecureRandom], stored as a
 * Base64url string (URL-safe, no padding) in a Tink-AEAD-encrypted
 * SharedPreferences entry. It survives app restarts but is device-local and
 * never backed up.
 *
 * The phone embeds [tokenBytes] in the BLE scan-response (as raw bytes via
 * [BleDiscoveryContract.TOKEN_SERVICE_UUID]) so the TV can reconstruct the
 * same Base64url string and present it as `Authorization: Bearer <token>`.
 *
 * The token is stable until the user explicitly resets pairing — it does not
 * rotate automatically.
 */
@Singleton
class BearerTokenRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val aead: Aead,
) {
    companion object {
        private const val TAG = "BearerTokenRepository"
        private const val PREFS_FILE = "watchbuddy_bearer_token"
        private const val KEY_TOKEN = "bearer_token"
        private const val AEAD_AAD = "watchbuddy_bearer_token_v1"

        /** Raw byte length of the bearer token — fits in one BLE scan-response AD structure. */
        const val TOKEN_BYTES = BleDiscoveryContract.TOKEN_PAYLOAD_SIZE_BYTES
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _tokenBytes: ByteArray by lazy { loadOrGenerate() }

    /** Base64url (URL_SAFE, no padding) encoding of the 13-byte token for HTTP bearer auth. */
    val token: String
        get() = encodeBase64url(_tokenBytes)

    /** Raw 13-byte token for embedding in the BLE scan-response payload. */
    val tokenBytes: ByteArray
        get() = _tokenBytes.copyOf()

    private fun loadOrGenerate(): ByteArray {
        val stored = prefs.getString(KEY_TOKEN, null)
        if (stored != null) {
            return runCatching { decryptAndDecode(stored) }
                .onFailure { DiagnosticLog.warn(TAG, "failed to decrypt stored token; regenerating", it) }
                .getOrNull() ?: generateAndStore()
        }
        return generateAndStore()
    }

    private fun generateAndStore(): ByteArray {
        val bytes = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
        val encoded = encodeBase64url(bytes)
        runCatching {
            val ciphertext = aead.encrypt(
                encoded.toByteArray(StandardCharsets.UTF_8),
                AEAD_AAD.toByteArray(StandardCharsets.UTF_8),
            )
            val stored = Base64.encodeToString(ciphertext, Base64.DEFAULT)
            prefs.edit { putString(KEY_TOKEN, stored) }
        }.onFailure { DiagnosticLog.warn(TAG, "failed to persist bearer token", it) }
        DiagnosticLog.event(TAG, "bearer token generated and stored")
        return bytes
    }

    private fun decryptAndDecode(stored: String): ByteArray {
        val ciphertext = Base64.decode(stored, Base64.DEFAULT)
        val plaintext = aead.decrypt(
            ciphertext,
            AEAD_AAD.toByteArray(StandardCharsets.UTF_8),
        ).toString(StandardCharsets.UTF_8)
        return decodeBase64url(plaintext)
    }

    private fun encodeBase64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun decodeBase64url(encoded: String): ByteArray =
        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING)
}
