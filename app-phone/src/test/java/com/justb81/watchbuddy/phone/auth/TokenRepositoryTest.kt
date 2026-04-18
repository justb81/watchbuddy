package com.justb81.watchbuddy.phone.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TokenRepository")
class TokenRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val mockPrefs: SharedPreferences = mockk(relaxed = true)
    private val mockEditor: SharedPreferences.Editor = mockk(relaxed = true)
    private lateinit var repository: TokenRepository

    @BeforeEach
    fun setUp() {
        mockkStatic(MasterKeys::class)
        mockkStatic(EncryptedSharedPreferences::class)
        every { MasterKeys.getOrCreate(any()) } returns "test-master-key"
        every {
            EncryptedSharedPreferences.create(any(), any(), any(), any(), any())
        } returns mockPrefs
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putLong(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor
        repository = TokenRepository(context)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(MasterKeys::class)
        unmockkStatic(EncryptedSharedPreferences::class)
    }

    @Test
    fun `constructor opens EncryptedSharedPreferences with correct parameters`() {
        verify { EncryptedSharedPreferences.create(any(), any(), any(), any(), any()) }
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
        fun `returns false when access token is blank`() {
            every { mockPrefs.getString("access_token", null) } returns ""
            assertFalse(repository.isTokenValid())
        }

        @Test
        fun `returns true when token is present and not expired`() {
            every { mockPrefs.getString("access_token", null) } returns "valid-token"
            every { mockPrefs.getLong("expires_at", 0L) } returns System.currentTimeMillis() + 60_000L
            assertTrue(repository.isTokenValid())
        }

        @Test
        fun `returns false when token has expired`() {
            every { mockPrefs.getString("access_token", null) } returns "expired-token"
            every { mockPrefs.getLong("expires_at", 0L) } returns System.currentTimeMillis() - 1_000L
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
        fun `returns true when token is blank`() {
            every { mockPrefs.getString("access_token", null) } returns ""
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns true when token expires within the buffer window`() {
            every { mockPrefs.getString("access_token", null) } returns "token"
            // expires in 1 second, well within the 5-minute buffer
            every { mockPrefs.getLong("expires_at", 0L) } returns System.currentTimeMillis() + 1_000L
            assertTrue(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }

        @Test
        fun `returns false when token expires well beyond the buffer`() {
            every { mockPrefs.getString("access_token", null) } returns "token"
            // expires in 10 minutes, outside the 5-minute buffer
            every { mockPrefs.getLong("expires_at", 0L) } returns System.currentTimeMillis() + 10 * 60_000L
            assertFalse(repository.isTokenExpiredOrExpiringSoon(5 * 60_000L))
        }
    }

    @Nested
    @DisplayName("token storage")
    inner class TokenStorage {

        @Test
        fun `saveTokens writes access token, refresh token, and expiry`() {
            repository.saveTokens("access-123", "refresh-456", 3600)

            verify { mockEditor.putString("access_token", "access-123") }
            verify { mockEditor.putString("refresh_token", "refresh-456") }
            verify { mockEditor.putLong(eq("expires_at"), any()) }
            verify { mockEditor.apply() }
        }

        @Test
        fun `getAccessToken returns the stored access token`() {
            every { mockPrefs.getString("access_token", null) } returns "my-access-token"
            assertEquals("my-access-token", repository.getAccessToken())
        }

        @Test
        fun `getAccessToken returns null when nothing is stored`() {
            every { mockPrefs.getString("access_token", null) } returns null
            assertNull(repository.getAccessToken())
        }

        @Test
        fun `getRefreshToken returns the stored refresh token`() {
            every { mockPrefs.getString("refresh_token", null) } returns "my-refresh-token"
            assertEquals("my-refresh-token", repository.getRefreshToken())
        }

        @Test
        fun `clearTokens removes all credential keys`() {
            repository.clearTokens()

            verify { mockEditor.remove("access_token") }
            verify { mockEditor.remove("refresh_token") }
            verify { mockEditor.remove("expires_at") }
            verify { mockEditor.apply() }
        }
    }

    @Nested
    @DisplayName("client secret")
    inner class ClientSecret {

        @Test
        fun `saveClientSecret persists the secret`() {
            repository.saveClientSecret("super-secret")

            verify { mockEditor.putString("trakt_client_secret", "super-secret") }
            verify { mockEditor.apply() }
        }

        @Test
        fun `getClientSecret returns the stored value`() {
            every { mockPrefs.getString("trakt_client_secret", "") } returns "stored-secret"
            assertEquals("stored-secret", repository.getClientSecret())
        }

        @Test
        fun `getClientSecret returns empty string when prefs returns null`() {
            every { mockPrefs.getString("trakt_client_secret", "") } returns null
            assertEquals("", repository.getClientSecret())
        }
    }
}
