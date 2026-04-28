package com.justb81.watchbuddy.tv.scrobbler

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [WatchBuddyNotificationListener] notification filtering.
 *
 * These tests verify the [isMediaStyle] / [ingest] logic by calling the listener's
 * internal filtering paths through [onNotificationPosted] and [onNotificationRemoved].
 * The listener is constructed directly (without Hilt) and [notificationSource] is
 * injected manually.
 */
@DisplayName("WatchBuddyNotificationListener")
class WatchBuddyNotificationListenerTest {

    private lateinit var source: NotificationMetadataSource
    private lateinit var listener: WatchBuddyNotificationListener

    @BeforeEach
    fun setUp() {
        source = NotificationMetadataSource()
        listener = WatchBuddyNotificationListener()
        listener.notificationSource = source
    }

    private fun buildSbn(
        pkg: String = "com.netflix.ninja",
        template: String? = "android.app.Notification\$MediaStyle",
        ongoing: Boolean = true,
        title: String? = "Stranger Things",
        text: String? = "S4:E1 Chapter One",
        subText: String? = "Netflix",
    ): StatusBarNotification {
        val extras = Bundle().apply {
            template?.let { putString(Notification.EXTRA_TEMPLATE, it) }
            title?.let { putString(Notification.EXTRA_TITLE, it) }
            text?.let { putString(Notification.EXTRA_TEXT, it) }
            subText?.let { putString(Notification.EXTRA_SUB_TEXT, it) }
        }
        val notification = mockk<Notification>(relaxed = true) {
            every { this@mockk.extras } returns extras
        }
        return mockk<StatusBarNotification>(relaxed = true) {
            every { packageName } returns pkg
            every { isOngoing } returns ongoing
            every { this@mockk.notification } returns notification
        }
    }

    // ── onNotificationPosted ──────────────────────────────────────────────────

    @Nested
    @DisplayName("onNotificationPosted")
    inner class PostedTest {

        @Test
        fun `MediaStyle ongoing notification is ingested into source`() {
            listener.onNotificationPosted(buildSbn())

            val snippet = source.byPackage["com.netflix.ninja"]
            assert(snippet != null) { "snippet should be stored" }
            assert(snippet?.title == "Stranger Things") { "title mismatch: ${snippet?.title}" }
            assert(snippet?.text == "S4:E1 Chapter One") { "text mismatch: ${snippet?.text}" }
            assert(snippet?.subText == "Netflix") { "subText mismatch: ${snippet?.subText}" }
        }

        @Test
        fun `non-MediaStyle notification is ignored`() {
            listener.onNotificationPosted(buildSbn(template = null))

            assert(source.byPackage.isEmpty()) { "non-MediaStyle should not be stored" }
        }

        @Test
        fun `non-MediaStyle with wrong template class is ignored`() {
            listener.onNotificationPosted(buildSbn(template = "android.app.Notification\$BigTextStyle"))

            assert(source.byPackage.isEmpty()) { "non-media template should not be stored" }
        }

        @Test
        fun `non-ongoing MediaStyle notification is ignored`() {
            listener.onNotificationPosted(buildSbn(ongoing = false))

            assert(source.byPackage.isEmpty()) { "non-ongoing should not be stored" }
        }

        @Test
        fun `androidx MediaStyle template is also accepted`() {
            val sbn = buildSbn(template = "androidx.media.app.NotificationCompat\$MediaStyle")

            listener.onNotificationPosted(sbn)

            assert(source.byPackage.containsKey("com.netflix.ninja")) {
                "androidx MediaStyle should be ingested"
            }
        }

        @Test
        fun `EXTRA_TITLE_BIG is used as fallback when EXTRA_TITLE is blank`() {
            val extras = Bundle().apply {
                putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$MediaStyle")
                putString(Notification.EXTRA_TITLE, "")
                putString(Notification.EXTRA_TITLE_BIG, "Loki")
                putString(Notification.EXTRA_TEXT, "Glorious Purpose")
            }
            val notification = mockk<Notification>(relaxed = true) {
                every { this@mockk.extras } returns extras
            }
            val sbn = mockk<StatusBarNotification>(relaxed = true) {
                every { packageName } returns "com.disneyplus"
                every { isOngoing } returns true
                every { this@mockk.notification } returns notification
            }

            listener.onNotificationPosted(sbn)

            assert(source.byPackage["com.disneyplus"]?.title == "Loki") {
                "EXTRA_TITLE_BIG fallback should be used when EXTRA_TITLE is blank"
            }
        }
    }

    // ── onNotificationRemoved ─────────────────────────────────────────────────

    @Nested
    @DisplayName("onNotificationRemoved")
    inner class RemovedTest {

        @Test
        fun `MediaStyle removal evicts the snippet`() {
            listener.onNotificationPosted(buildSbn(pkg = "com.netflix.ninja"))
            assert(source.byPackage.containsKey("com.netflix.ninja"))

            listener.onNotificationRemoved(buildSbn(pkg = "com.netflix.ninja"))

            assert(!source.byPackage.containsKey("com.netflix.ninja")) {
                "snippet should be evicted on removal"
            }
        }

        @Test
        fun `non-MediaStyle removal does not evict a stored snippet`() {
            listener.onNotificationPosted(buildSbn(pkg = "com.netflix.ninja"))
            listener.onNotificationRemoved(buildSbn(pkg = "com.netflix.ninja", template = null))

            assert(source.byPackage.containsKey("com.netflix.ninja")) {
                "non-MediaStyle removal should not evict the snippet"
            }
        }
    }
}
