package com.justb81.watchbuddy.phone.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64

@DisplayName("TokenRepository")
class TokenRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val legacyPrefs: SharedPreferences = mockk(relaxed = true)
    private val mockPrefs: SharedPreferences = mockk(relaxed = true)
    private val mockEditor: SharedPreferences.Editor = mockk(relaxed = true)
    private val aead: Aead = mockk()
    private lateinit var repository: TokenRepository

    @BeforeEach
    fun setUp() {
        every { context.getSharedPreferences("watchbuddy_tokens", Context.MODE_PRIVATE) } returns legacyPrefs
        every { legacyPrefs.all } returns emptyMap()

        every { context.getSharedPreferences("watchbuddy_tokens_v2", Context.MODE_PRIVATE) } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor

        // Fixed-AAD AEAD: ciphertext layout is "enc(FIXED_AAD):$plaintext" for v1 format.
        // Supports both the new fixed-AAD and the legacy key-name AAD for migration tests.
        val plaintextSlot = slot<ByteArray>()
        val encryptAssociatedSlot = slot<ByteArray>()
        every { aead.encrypt(capture(plaintextSlot), capture(encryptAssociatedSlot)) } answers {
            val plain = String(plaintextSlot.captured, Charsets.UTF_8)
            val aad = String(encryptAssociatedSlot.captured, Charsets.UTF_8)
            "enc($aad):$plain".toByteArray(Charsets.UTF_8)
        }
        val cipherSlot = slot<ByteArray>()
        val decryptAssociatedSlot = slot<ByteArray>()
        every { aead.decrypt(capture(cipherSlot), capture(decryptAssociatedSlot)) } answers {
            val raw = String(cipherSlot.captured, Charsets.UTF_8)
            val aad = String(decryptAssociatedSlot.captured, Charsets.UTF_8)
            val prefix = "enc($aad):"
            require(raw.startsWith(prefix)) { "aad mismatch: expected prefix '$prefix' in '$raw'" }
            raw.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        }

        repository = TokenRepository(context, aead)
    }

    /** Builds a v1-format storage string using the fixed AAD. */
    private fun v1Ciphertext(plaintext: String): String {
        val fixed = "watchbuddy_aead_v1"
        val raw = "enc($fixed):$plaintext".toByteArray(Charsets.UTF_8)
        return "v1:" + Base64.getEncoder().encodeToString(raw)
    }

    /** Builds a legacy-format storage string using the key name as AAD. */
    private fun legacyCiphertext(key: String, plaintext: String): String {
        val bytes = "enc($key):$plaintext".toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Nested
    @DisplayName("legacy migration (EncryptedSharedPreferences)")
    inner class LegacyMigration {

        @Test
        fun `deletes legacy EncryptedSharedPreferences file when present`() {
            every { legacyPrefs.all } returns mapOf("access_token" to "legacy-blob")
            every { context.deleteSharedPreferences("watchbuddy_tokens") } returns true

            TokenRepository(context, aead)

            verify { context.deleteSharedPreferences("watchbuddy_tokens") }
        }

        @Test
        fun `skips deletion when legacy file is empty`() {
            verify(exactly = 0) { context.deleteSharedPreferences("watchbuddy_tokens") }
        }
    }

    @Nested
    @DisplayName("v1 ciphertext format (fixed AAD)")
    inner class V1Format {

        @Test
        fun `encrypt produces v1: prefix`() {
            val capturedValues = mutableMapOf<String, String>()
            every { mockEditor.putString(any(), any()) } answers {
                capturedValues[firstArg()] = secondArg()
                mockEditor
            }

            repository.saveTokens("access-123", "refresh-456", 3600)

            assertTrue(capturedValues["access_token"]!!.startsWith("v1:"), "access_token should have v1: prefix")
            assertTrue(capturedValues["refresh_token"]!!.startsWith("v1:"), "refresh_token should have v1: prefix")
            assertTrue(capturedValues["expires_at"]!!.startsWith("v1:"), "expires_at should have v1: prefix")
        }

        @Test
        fun `encrypt uses fixed AAD (not key name)`() {
            val capturedAads = mutableListOf<ByteArray>()
            every { aead.encrypt(any(), any()) } answers {
                capturedAads.add(secondArg())
                "enc(watchbuddy_aead_v1):placeholder".toByteArray(Charsets.UTF_8)
            }

            repository.saveTokens("access", "refresh", 3600)

            assertTrue(capturedAads.isNotEmpty())
            capturedAads.forEach { aadBytes ->
                assertEquals("watchbuddy_aead_v1", String(aadBytes, Charsets.UTF_8))
            }
        }

        @Test
        fun `decrypt reads v1-format ciphertext with fixed AAD`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("my-access-token")
            assertEquals("my-access-token", repository.getAccessToken())
        }

        @Test
        fun `getRefreshToken decrypts v1-format ciphertext`() {
            every { mockPrefs.getString("refresh_token", null) } returns v1Ciphertext("my-refresh-token")
            assertEquals("my-refresh-token", repository.getRefreshToken())
        }

        @Test
        fun `getClientSecret decrypts v1-format ciphertext`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns v1Ciphertext("stored-secret")
            assertEquals("stored-secret", repository.getClientSecret())
        }

        @Test
        fun `getClientSecret returns empty string when nothing stored`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns null
            assertEquals("", repository.getClientSecret())
        }
    }

    @Nested
    @DisplayName("legacy AAD migration (key-name to fixed AAD)")
    inner class LegacyAadMigration {

        @Test
        fun `decrypts legacy access_token and re-encrypts in v1 format`() {
            every { mockPrefs.getString("access_token", null) } returns
                legacyCiphertext("access_token", "legacy-access")

            val result = repository.getAccessToken()

            assertEquals("legacy-access", result)
            // Verify re-encryption was triggered (putString called with v1: prefix)
            verify { mockEditor.putString(eq("access_token"), match { it.startsWith("v1:") }) }
        }

        @Test
        fun `decrypts legacy refresh_token and re-encrypts in v1 format`() {
            every { mockPrefs.getString("refresh_token", null) } returns
                legacyCiphertext("refresh_token", "legacy-refresh")

            val result = repository.getRefreshToken()

            assertEquals("legacy-refresh", result)
            verify { mockEditor.putString(eq("refresh_token"), match { it.startsWith("v1:") }) }
        }

        @Test
        fun `decrypts legacy client_secret and re-encrypts in v1 format`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns
                legacyCiphertext("trakt_client_secret", "old-secret")

            val result = repository.getClientSecret()

            assertEquals("old-secret", result)
            verify { mockEditor.putString(eq("trakt_client_secret"), match { it.startsWith("v1:") }) }
        }

        @Test
        fun `legacy format with wrong key-name AAD fails and sets hadDecryptionFailure`() {
            // Simulate a ciphertext with AAD "wrong_key" stored under "access_token".
            // Decrypt with key-name "access_token" will fail due to AAD mismatch.
            val wrongAad = "enc(wrong_key):bad-plaintext".toByteArray(Charsets.UTF_8)
            val encoded = Base64.getEncoder().encodeToString(wrongAad)
            every { mockPrefs.getString("access_token", null) } returns encoded

            val result = repository.getAccessToken()

            assertNull(result)
            assertTrue(repository.hadDecryptionFailure)
        }
    }

    @Nested
    @DisplayName("isTokenValid")
    inner class IsTokenValid {

        @Test
        fun `returns false when no access token stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertFalse(repository.isTokenValid())
        }

        @Test
        fun `returns false when access token decrypts to blank`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("")
            assertFalse(repository.isTokenValid())
        }

        @Test
        fun `returns true when token is present and not expired`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("valid-token")
            every { mockPrefs.getString("expires_at", null) } returns
                v1Ciphertext((System.currentTimeMillis() + 60_000L).toString())
            assertTrue(repository.isTokenValid())
        }

        @Test
        fun `returns false when token has expired`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("expired")
            every { mockPrefs.getString("expires_at", null) } returns
                v1Ciphertext((System.currentTimeMillis() - 1_000L).toString())
            assertFalse(repository.isTokenValid())
        }
    }

    @Nested
    @DisplayName("isTokenExpiredOrExpiringSoon")
    inner class IsTokenExpiredOrExpiringSoon {

        @Test
        fun `returns true when no token stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns true when token decrypts to blank`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("")
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns true when token expires within the buffer window`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("token")
            every { mockPrefs.getString("expires_at", null) } returns
                v1Ciphertext((System.currentTimeMillis() + 1_000L).toString())
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns false when token expires well beyond the buffer`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("token")
            every { mockPrefs.getString("expires_at", null) } returns
                v1Ciphertext((System.currentTimeMillis() + 10 * 60_000L).toString())
            assertFalse(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }
    }

    @Nested
    @DisplayName("token storage roundtrip")
    inner class TokenStorage {

        @Test
        fun `saveTokens writes v1-format ciphertext for each key`() {
            val capturedValues = mutableMapOf<String, String>()
            every { mockEditor.putString(any(), any()) } answers {
                capturedValues[firstArg()] = secondArg()
                mockEditor
            }

            repository.saveTokens("access-123", "refresh-456", 3600)

            assertTrue(capturedValues["access_token"]!!.startsWith("v1:"))
            assertTrue(capturedValues["refresh_token"]!!.startsWith("v1:"))
            assertTrue(capturedValues["expires_at"]!!.startsWith("v1:"))
        }

        @Test
        fun `getAccessToken decrypts the stored v1 ciphertext`() {
            every { mockPrefs.getString("access_token", null) } returns v1Ciphertext("my-access-token")
            assertEquals("my-access-token", repository.getAccessToken())
        }

        @Test
        fun `getAccessToken returns null when nothing is stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertNull(repository.getAccessToken())
        }

        @Test
        fun `getRefreshToken decrypts the stored v1 ciphertext`() {
            every { mockPrefs.getString("refresh_token", null) } returns v1Ciphertext("my-refresh-token")
            assertEquals("my-refresh-token", repository.getRefreshToken())
        }

        @Test
        fun `getAccessToken returns null on corrupted ciphertext and sets hadDecryptionFailure`() {
            every { mockPrefs.getString("access_token", null) } returns "not-base64-or-valid-ciphertext"
            assertNull(repository.getAccessToken())
            assertTrue(repository.hadDecryptionFailure)
        }

        @Test
        fun `getAccessToken returns null on v1 ciphertext with wrong AAD and sets hadDecryptionFailure`() {
            // Build a v1: blob whose inner ciphertext uses the WRONG AAD.
            val wrongAad = "enc(wrong_aad):secret".toByteArray(Charsets.UTF_8)
            val b64 = Base64.getEncoder().encodeToString(wrongAad)
            every { mockPrefs.getString("access_token", null) } returns "v1:$b64"

            assertNull(repository.getAccessToken())
            assertTrue(repository.hadDecryptionFailure)
        }

        @Test
        fun `hadDecryptionFailure starts false`() {
            assertFalse(repository.hadDecryptionFailure)
        }

        @Test
        fun `hadDecryptionFailure not set when stored value is null`() {
            every { mockPrefs.getString("access_token", null) } returns null
            repository.getAccessToken()
            assertFalse(repository.hadDecryptionFailure)
        }

        @Test
        fun `clearTokens removes all credential keys`() {
            repository.clearTokens()

            verify { mockEditor.remove("access_token") }
            verify { mockEditor.remove("refresh_token") }
            verify { mockEditor.remove("expires_at") }
        }
    }

    @Nested
    @DisplayName("client secret")
    inner class ClientSecret {

        @Test
        fun `saveClientSecret writes v1-format ciphertext`() {
            val capturedValue = slot<String>()
            every { mockEditor.putString(eq("trakt_client_secret"), capture(capturedValue)) } returns mockEditor

            repository.saveClientSecret("super-secret")

            assertTrue(capturedValue.captured.startsWith("v1:"))
        }

        @Test
        fun `getClientSecret decrypts the stored value`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns v1Ciphertext("stored-secret")
            assertEquals("stored-secret", repository.getClientSecret())
        }

        @Test
        fun `getClientSecret returns empty string when prefs returns null`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns null
            assertEquals("", repository.getClientSecret())
        }
    }

    @Nested
    @DisplayName("AuthUnavailableException propagation")
    inner class AuthUnavailableExceptionPropagation {

        private fun makeUnvailableAead(): Aead {
            val brokenAead: Aead = mockk()
            val reason = AuthUnavailableException("Keystore locked")
            every { brokenAead.encrypt(any(), any()) } throws reason
            every { brokenAead.decrypt(any(), any()) } throws reason
            return brokenAead
        }

        @Test
        fun `getAccessToken propagates AuthUnavailableException`() {
            val brokenRepo = TokenRepository(context, makeUnvailableAead())
            // Store a non-null value so decrypt is attempted
            every { mockPrefs.getString("access_token", null) } returns "v1:anyciphertext"

            assertThrows(AuthUnavailableException::class.java) {
                brokenRepo.getAccessToken()
            }
        }

        @Test
        fun `AuthUnavailableException does NOT set hadDecryptionFailure`() {
            val brokenRepo = TokenRepository(context, makeUnvailableAead())
            every { mockPrefs.getString("access_token", null) } returns "v1:anyciphertext"

            runCatching { brokenRepo.getAccessToken() }

            assertFalse(brokenRepo.hadDecryptionFailure,
                "hadDecryptionFailure must not be set for Keystore-unavailable failures")
        }

        @Test
        fun `isTokenValid returns false and swallows AuthUnavailableException in MainActivity catch block`() {
            val brokenAead: Aead = mockk()
            every { brokenAead.encrypt(any(), any()) } throws AuthUnavailableException("locked")
            every { brokenAead.decrypt(any(), any()) } throws AuthUnavailableException("locked")
            val brokenRepo = TokenRepository(context, brokenAead)
            every { mockPrefs.getString("access_token", null) } returns "v1:data"

            // MainActivity wraps isTokenValid() in try/catch — simulate that
            val result = runCatching { brokenRepo.isTokenValid() }.getOrDefault(false)
            assertFalse(result)
        }
    }
}
