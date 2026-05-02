package com.justb81.watchbuddy.phone.server

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.justb81.watchbuddy.core.discovery.BleDiscoveryContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("BearerTokenRepository")
class BearerTokenRepositoryTest {

    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)
    private val aead: Aead = mockk()

    @BeforeEach
    fun setUp() {
        every { context.getSharedPreferences("watchbuddy_bearer_token", Context.MODE_PRIVATE) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor

        val plaintextSlot = slot<ByteArray>()
        val aadSlot = slot<ByteArray>()
        every { aead.encrypt(capture(plaintextSlot), capture(aadSlot)) } answers {
            val plain = String(plaintextSlot.captured, Charsets.UTF_8)
            val aad = String(aadSlot.captured, Charsets.UTF_8)
            "enc($aad):$plain".toByteArray(Charsets.UTF_8)
        }
        val cipherSlot = slot<ByteArray>()
        val decryptAadSlot = slot<ByteArray>()
        every { aead.decrypt(capture(cipherSlot), capture(decryptAadSlot)) } answers {
            val cipher = String(cipherSlot.captured, Charsets.UTF_8)
            val prefix = "enc(${String(decryptAadSlot.captured, Charsets.UTF_8)}):"
            cipher.removePrefix(prefix).toByteArray(Charsets.UTF_8)
        }
    }

    private fun makeRepo(): BearerTokenRepository = BearerTokenRepository(context, aead)

    @Nested
    @DisplayName("token generation")
    inner class TokenGeneration {

        @Test
        fun `generates token when no stored value`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()

            assertNotNull(repo.token)
            assertTrue(repo.token.isNotBlank())
        }

        @Test
        fun `tokenBytes has correct length`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()

            assertEquals(BleDiscoveryContract.TOKEN_PAYLOAD_SIZE_BYTES, repo.tokenBytes.size)
        }

        @Test
        fun `token is Base64url encoded`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()

            // Base64url uses A-Z, a-z, 0-9, -, _ without = padding
            assertTrue(repo.token.matches(Regex("[A-Za-z0-9_-]+")))
        }

        @Test
        fun `token encoding length is correct for 13 bytes`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()

            // 13 bytes → ceil(13*4/3) = 18 chars in Base64 (no padding)
            assertEquals(18, repo.token.length)
        }

        @Test
        fun `persists generated token`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()
            repo.token // trigger lazy init which generates and persists the token

            verify { editor.putString("bearer_token", any()) }
        }
    }

    @Nested
    @DisplayName("token loading")
    inner class TokenLoading {

        @Test
        fun `loads existing token from storage`() {
            // Generate a token first, then simulate it being stored
            every { prefs.getString("bearer_token", null) } returns null
            val repo1 = makeRepo()
            val firstToken = repo1.token

            // Now simulate the token being stored (encrypt then store)
            val storedValue = slot<String>()
            verify { editor.putString("bearer_token", capture(storedValue)) }

            // Second repo load should use the stored value
            every { prefs.getString("bearer_token", null) } returns storedValue.captured
            val repo2 = makeRepo()

            assertEquals(firstToken, repo2.token)
        }

        @Test
        fun `regenerates token when decryption fails`() {
            every { prefs.getString("bearer_token", null) } returns "invalid-ciphertext"
            every { aead.decrypt(any(), any()) } throws Exception("Keystore error")

            val repo = makeRepo()

            assertNotNull(repo.token)
            assertTrue(repo.token.isNotBlank())
        }
    }

    @Nested
    @DisplayName("tokenBytes")
    inner class TokenBytesTests {

        @Test
        fun `tokenBytes returns copy not reference`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()
            val bytes1 = repo.tokenBytes
            val bytes2 = repo.tokenBytes

            // Should be equal but not the same reference
            assertTrue(bytes1.contentEquals(bytes2))
            assertTrue(bytes1 !== bytes2)
        }

        @Test
        fun `token and tokenBytes are consistent`() {
            every { prefs.getString("bearer_token", null) } returns null

            val repo = makeRepo()

            // Encode the tokenBytes manually and compare to token
            val manualEncoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(repo.tokenBytes)
            assertEquals(manualEncoded, repo.token)
        }
    }
}
