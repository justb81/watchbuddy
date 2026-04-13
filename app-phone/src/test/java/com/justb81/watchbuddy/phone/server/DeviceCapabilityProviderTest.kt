package com.justb81.watchbuddy.phone.server

import android.app.ActivityManager
import android.content.Context
import com.justb81.watchbuddy.core.model.LlmBackend
import com.justb81.watchbuddy.core.trakt.TraktApiService
import com.justb81.watchbuddy.core.trakt.TraktUserProfile
import com.justb81.watchbuddy.phone.auth.TokenRepository
import com.justb81.watchbuddy.phone.llm.LlmOrchestrator
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Field

@DisplayName("DeviceCapabilityProvider")
class DeviceCapabilityProviderTest {

    private val context: Context = mockk(relaxed = true)
    private val orchestrator: LlmOrchestrator = mockk()
    private val traktApi: TraktApiService = mockk()
    private val tokenRepository: TokenRepository = mockk()
    private val activityManager: ActivityManager = mockk(relaxed = true)
    private lateinit var provider: DeviceCapabilityProvider

    @BeforeEach
    fun setUp() {
        // Build.ID and Build.MODEL are static String fields that are null on JVM
        // Set them via reflection so DeviceCapabilityProvider doesn't NPE
        setStaticField("ID", "test-id")
        setStaticField("MODEL", "Pixel 8")

        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns activityManager
        every { activityManager.getMemoryInfo(any()) } answers {
            val memInfo = firstArg<ActivityManager.MemoryInfo>()
            memInfo.availMem = 4000L * 1_048_576L
        }
        every { orchestrator.selectConfig() } returns LlmOrchestrator.LlmConfig(
            LlmBackend.LITERT,
            LlmOrchestrator.ModelVariant.GEMMA4_E2B,
            70
        )
        provider = DeviceCapabilityProvider(context, orchestrator, traktApi, tokenRepository)
    }

    private fun setStaticField(fieldName: String, value: String) {
        try {
            val field: Field = android.os.Build::class.java.getDeclaredField(fieldName)
            field.isAccessible = true
            // Use Unsafe to write static final fields (works on JDK 17+)
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field) as Long
            unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, staticFieldBase, staticFieldOffset, value)
        } catch (_: Exception) {
            // Ignore if the field can't be set
        }
    }

    @Test
    fun `getCapability returns capability with LLM config`() = runTest {
        every { tokenRepository.getAccessToken() } returns null

        val cap = provider.getCapability()
        assertEquals(LlmBackend.LITERT, cap.llmBackend)
        assertEquals(70, cap.modelQuality)
        assertEquals(4000, cap.freeRamMb)
        assertTrue(cap.isAvailable)
    }

    @Test
    fun `getCapability uses Trakt profile username when available`() = runTest {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { traktApi.getProfile("Bearer token") } returns TraktUserProfile("walter")

        val cap = provider.getCapability()
        assertEquals("walter", cap.userName)
    }

    @Test
    fun `getCapability returns default userName when token is null`() = runTest {
        every { tokenRepository.getAccessToken() } returns null

        val cap = provider.getCapability()
        assertEquals("user", cap.userName)
    }

    @Test
    fun `getCapability returns default userName when API fails`() = runTest {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { traktApi.getProfile(any()) } throws RuntimeException("Network error")

        val cap = provider.getCapability()
        assertEquals("user", cap.userName)
    }

    @Test
    fun `getCapability caches profile after first successful call`() = runTest {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { traktApi.getProfile(any()) } returns TraktUserProfile("cached-user")

        provider.getCapability()
        provider.getCapability()
        // API should only be called once
        coVerify(exactly = 1) { traktApi.getProfile(any()) }
    }

    @Test
    fun `invalidateCache clears cached profile`() = runTest {
        every { tokenRepository.getAccessToken() } returns "token"
        coEvery { traktApi.getProfile(any()) } returns TraktUserProfile("user1")

        provider.getCapability()
        provider.invalidateCache()
        provider.getCapability()
        // API should be called twice (once before and once after invalidation)
        coVerify(exactly = 2) { traktApi.getProfile(any()) }
    }
}
