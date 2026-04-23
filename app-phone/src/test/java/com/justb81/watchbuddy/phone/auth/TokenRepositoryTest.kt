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

        // Deterministic AEAD: the ciphertext layout is "enc($aad):$plaintext" so the
        // tests can round-trip through Base64 without a real crypto backend.
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
            require(raw.startsWith(prefix)) { "aad mismatch" }
            raw.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        }

        repository = TokenRepository(context, aead)
    }

    private fun storedCiphertext(key: String, plaintext: String): String {
        val bytes = "enc($key):$plaintext".toByteArray(Charsets.UTF_8)
        return Base64.getEncoder().encodeToString(bytes)
    }

    @Nested
    @DisplayName("legacy migration")
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
    @DisplayName("isTokenValid")
    inner class IsTokenValid {

        @Test
        fun `returns false when no access token stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertFalse(repository.isTokenValid())
        }

        @Test
        fun `returns false when access token decrypts to blank`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "")
            assertFalse(repository.isTokenValid())
        }

        @Test
        fun `returns true when token is present and not expired`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "valid-token")
            every { mockPrefs.getString("expires_at", null) } returns
                storedCiphertext("expires_at", (System.currentTimeMillis() + 60_000L).toString())
            assertTrue(repository.isTokenValid())
        }

        @Test
        fun `returns false when token has expired`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "expired")
            every { mockPrefs.getString("expires_at", null) } returns
                storedCiphertext("expires_at", (System.currentTimeMillis() - 1_000L).toString())
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
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "")
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns true when token expires within the buffer window`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "token")
            every { mockPrefs.getString("expires_at", null) } returns
                storedCiphertext("expires_at", (System.currentTimeMillis() + 1_000L).toString())
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns false when token expires well beyond the buffer`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "token")
            every { mockPrefs.getString("expires_at", null) } returns
                storedCiphertext("expires_at", (System.currentTimeMillis() + 10 * 60_000L).toString())
            assertFalse(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }
    }

    @Nested
    @DisplayName("token storage roundtrip")
    inner class TokenStorage {

        @Test
        fun `saveTokens writes ciphertext for each key`() {
            repository.saveTokens("access-123", "refresh-456", 3600)

            verify { mockEditor.putString("access_token", storedCiphertext("access_token", "access-123")) }
            verify { mockEditor.putString("refresh_token", storedCiphertext("refresh_token", "refresh-456")) }
            verify { mockEditor.putString(eq("expires_at"), any()) }
        }

        @Test
        fun `getAccessToken decrypts the stored ciphertext`() {
            every { mockPrefs.getString("access_token", null) } returns storedCiphertext("access_token", "my-access-token")
            assertEquals("my-access-token", repository.getAccessToken())
        }

        @Test
        fun `getAccessToken returns null when nothing is stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertNull(repository.getAccessToken())
        }

        @Test
        fun `getRefreshToken decrypts the stored ciphertext`() {
            every { mockPrefs.getString("refresh_token", null) } returns storedCiphertext("refresh_token", "my-refresh-token")
            assertEquals("my-refresh-token", repository.getRefreshToken())
        }

        @Test
        fun `getAccessToken returns null on ciphertext corruption`() {
            every { mockPrefs.getString("access_token", null) } returns "not-base64-or-valid-ciphertext"
            assertNull(repository.getAccessToken())
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
        fun `saveClientSecret writes ciphertext`() {
            repository.saveClientSecret("super-secret")

            verify {
                mockEditor.putString(
                    "trakt_client_secret",
                    storedCiphertext("trakt_client_secret", "super-secret"),
                )
            }
        }

        @Test
        fun `getClientSecret decrypts the stored value`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns
                storedCiphertext("trakt_client_secret", "stored-secret")
            assertEquals("stored-secret", repository.getClientSecret())
        }

        @Test
        fun `getClientSecret returns empty string when prefs returns null`() {
            every { mockPrefs.getString("trakt_client_secret", null) } returns null
            assertEquals("", repository.getClientSecret())
        }
    }
}
