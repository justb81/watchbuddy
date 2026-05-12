package com.justb81.watchbuddy.phone.server.routes

import com.justb81.watchbuddy.core.network.WatchBuddyJson
import com.justb81.watchbuddy.phone.settings.AvatarImageStore
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

@DisplayName("AvatarRoutes — GET /avatar")
class AvatarRoutesTest {

    @TempDir
    lateinit var tempDir: File

    private val avatarImageStore: AvatarImageStore = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(WatchBuddyJson) }
            routing {
                avatarRoutes(AvatarRouteDeps(avatarImageStore))
            }
        }
        block()
    }

    @Test
    fun `returns 404 when no custom avatar stored`() = testApp {
        every { avatarImageStore.exists() } returns false

        val response = client.get("/avatar")

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `returns 200 with file content when ETag does not match`() = testApp {
        val avatarBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val avatarFile = File(tempDir, "avatar.jpg").apply { writeBytes(avatarBytes) }
        every { avatarImageStore.exists() } returns true
        every { avatarImageStore.file() } returns avatarFile

        val response = client.get("/avatar")

        assertEquals(HttpStatusCode.OK, response.status)
        assertArrayEquals(avatarBytes, response.readRawBytes())
    }

    @Test
    fun `returns 304 when If-None-Match matches file content hash`() = testApp {
        val avatarBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val avatarFile = File(tempDir, "avatar_304.jpg").apply { writeBytes(avatarBytes) }
        every { avatarImageStore.exists() } returns true
        every { avatarImageStore.file() } returns avatarFile
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(avatarBytes).joinToString("") { "%02x".format(it) }

        val response = client.get("/avatar") {
            header(HttpHeaders.IfNoneMatch, "\"$sha256\"")
        }

        assertEquals(HttpStatusCode.NotModified, response.status)
    }

    @Test
    fun `sets ETag header as SHA-256 of file content`() = testApp {
        val avatarBytes = byteArrayOf(1, 2, 3)
        val avatarFile = File(tempDir, "avatar.jpg").apply { writeBytes(avatarBytes) }
        every { avatarImageStore.exists() } returns true
        every { avatarImageStore.file() } returns avatarFile

        val response = client.get("/avatar")

        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(avatarBytes).joinToString("") { "%02x".format(it) }
        assertEquals("\"$sha256\"", response.headers[HttpHeaders.ETag])
    }

    @Test
    fun `sets Cache-Control private header`() = testApp {
        val avatarFile = File(tempDir, "avatar.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        every { avatarImageStore.exists() } returns true
        every { avatarImageStore.file() } returns avatarFile

        val response = client.get("/avatar")

        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertNotNull(cacheControl)
        assertTrue(cacheControl!!.contains("private"))
    }
}
