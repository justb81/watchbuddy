package com.justb81.watchbuddy.tv.ui.showdetail

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.justb81.watchbuddy.core.model.ResolvedProvider
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Unit tests for [launchProvider] — the four-stage provider launch cascade in
 * [ShowDetailScreen].
 *
 * Test strategy: mock [Context] and [PackageManager] with MockK; stub
 * [PackageManager.resolveActivity] and [PackageManager.getLaunchIntentForPackage]
 * to control which stage fires. Verify that [Context.startActivity] is called with
 * the expected intent, or not called at all when every stage fails. Also verifies
 * the [onFailure] callback fires only when the entire cascade is exhausted.
 */
@DisplayName("launchProvider — four-stage launch cascade (#720)")
class LaunchProviderTest {

    private val pm: PackageManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val startedIntents = mutableListOf<Intent>()

    @BeforeEach
    fun setUp() {
        every { context.packageManager } returns pm
        val intentSlot = slot<Intent>()
        justRun { context.startActivity(capture(intentSlot)) }
        every { context.startActivity(capture(intentSlot)) } answers {
            startedIntents.add(intentSlot.captured)
        }
        // Default: resolveActivity returns null (nothing handles any intent)
        every { pm.resolveActivity(any(), any<Int>()) } returns null
        // Default: getLaunchIntentForPackage returns null (package not present)
        every { pm.getLaunchIntentForPackage(any()) } returns null
    }

    private fun makeProvider(
        packageName: String? = "com.amazon.avod.thirdpartyclient",
        isInstalled: Boolean = true,
        tmdbPageUrl: String? = "https://www.themoviedb.org/tv/1399",
    ) = ResolvedProvider(
        providerId = 9,
        name = "Prime Video",
        logoPath = null,
        packageName = packageName,
        isInstalled = isInstalled,
        isLastUsed = false,
        tmdbPageUrl = tmdbPageUrl,
    )

    private val deepLink = "https://www.amazon.com/gp/video/detail/B00VSTHUGG"
    private val launchIntent = mockk<Intent>(relaxed = true)

    @Nested
    @DisplayName("Stage 1 — targeted deep-link intent")
    inner class Stage1Tests {

        @Test
        fun `fires targeted intent when resolveActivity returns non-null`() {
            every { pm.resolveActivity(match { it.`package` != null }, any<Int>()) } returns mockk()

            launchProvider(context, makeProvider(), deepLink)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `skips to next stage when targeted resolveActivity returns null`() {
            every { pm.resolveActivity(match { it.`package` != null }, any<Int>()) } returns null
            every { pm.resolveActivity(match { it.`package` == null }, any<Int>()) } returns null

            launchProvider(context, makeProvider(), deepLink)

            // No startActivity for deep-link intents; falls through to launch-intent stage
            verify(exactly = 0) { context.startActivity(match { it.action == Intent.ACTION_VIEW && it.`package` != null }) }
        }
    }

    @Nested
    @DisplayName("Stage 2 — untargeted deep-link intent")
    inner class Stage2Tests {

        @Test
        fun `fires untargeted intent when targeted fails but untargeted resolves`() {
            every { pm.resolveActivity(match { it.`package` != null }, any<Int>()) } returns null
            every { pm.resolveActivity(match { it.`package` == null }, any<Int>()) } returns mockk()

            launchProvider(context, makeProvider(), deepLink)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `does not fire untargeted intent when provider has no package name`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null

            launchProvider(context, makeProvider(packageName = null), deepLink)

            verify(exactly = 0) { context.startActivity(match { it.action == Intent.ACTION_VIEW }) }
        }
    }

    @Nested
    @DisplayName("Stage 3 — getLaunchIntentForPackage fallback")
    inner class Stage3Tests {

        @Test
        fun `falls back to getLaunchIntentForPackage when deep-link stages fail`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null
            every { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") } returns launchIntent

            launchProvider(context, makeProvider(isInstalled = true), deepLink)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `uses getLaunchIntentForPackage even when isInstalled is false`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null
            every { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") } returns launchIntent

            launchProvider(context, makeProvider(isInstalled = false), deepLink)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `uses getLaunchIntentForPackage even when deepLink is null`() {
            every { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") } returns launchIntent

            launchProvider(context, makeProvider(isInstalled = false), deepLink = null)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `skips stage 3 when provider has no package name`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null

            launchProvider(context, makeProvider(packageName = null), deepLink)

            verify(exactly = 0) { pm.getLaunchIntentForPackage(any()) }
        }
    }

    @Nested
    @DisplayName("Stage 4 — TMDB page URL browser fallback")
    inner class Stage4Tests {

        @Test
        fun `opens TMDB page when all other stages fail`() {
            every { pm.resolveActivity(any(), any<Int>()) } returnsMany listOf(null, null, mockk())
            every { pm.getLaunchIntentForPackage(any()) } returns null

            launchProvider(context, makeProvider(), deepLink)

            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `onFailure fires when TMDB page URL is null and all other stages fail`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null
            every { pm.getLaunchIntentForPackage(any()) } returns null

            var failed = false
            launchProvider(context, makeProvider(tmdbPageUrl = null), deepLink) { failed = true }

            verify(exactly = 0) { context.startActivity(any()) }
            assertTrue(failed)
        }

        @Test
        fun `onFailure fires when provider is null and deepLink is null`() {
            var failed = false
            launchProvider(context, provider = null, deepLink = null) { failed = true }

            verify(exactly = 0) { context.startActivity(any()) }
            assertTrue(failed)
        }

        @Test
        fun `onFailure is not called when stage 4 succeeds`() {
            every { pm.resolveActivity(any(), any<Int>()) } returnsMany listOf(null, null, mockk())
            every { pm.getLaunchIntentForPackage(any()) } returns null

            var failed = false
            launchProvider(context, makeProvider(), deepLink) { failed = true }

            verify(exactly = 1) { context.startActivity(any()) }
            assertFalse(failed)
        }
    }

    @Nested
    @DisplayName("fallback priority order")
    inner class FallbackOrderTests {

        @Test
        fun `stage 3 is preferred over stage 4 when getLaunchIntentForPackage succeeds`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null
            every { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") } returns launchIntent

            launchProvider(context, makeProvider(isInstalled = false), deepLink)

            // Only one startActivity call — from stage 3, not stage 4
            verify(exactly = 1) { context.startActivity(any()) }
        }

        @Test
        fun `isInstalled=false no longer blocks stage 3 fallback`() {
            every { pm.resolveActivity(any(), any<Int>()) } returns null
            every { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") } returns launchIntent

            launchProvider(context, makeProvider(isInstalled = false), deepLink)

            verify { pm.getLaunchIntentForPackage("com.amazon.avod.thirdpartyclient") }
            verify(exactly = 1) { context.startActivity(any()) }
        }
    }
}
