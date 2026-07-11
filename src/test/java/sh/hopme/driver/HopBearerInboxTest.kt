package sh.hopme.driver

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.hop.InboxMessage

/** cov/android-driver: pump()'s inbox drain - text / image / multipart decode, unread badge, and the
 *  notification path (both the granted-permission build and the denied early-return). */
class HopBearerInboxTest : DriverTestBase() {

    private fun sender(b: Byte = 3) = ByteArray(32) { b }
    private fun inbox(ct: String, body: ByteArray, from: ByteArray = sender()) =
        InboxMessage(from = from, contentType = ct, body = body, hops = 2u, createdAt = 1uL, trace = emptyList())

    /** pump() is private; a send() ends with pump(), so it drains the inbox we pre-loaded. */
    private fun pumpViaSend() {
        bearer.send("trigger", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u))
        settle()
    }

    @Test fun textInboxBecomesIncomingMessage() {
        bearer.appInForeground = true   // suppress notify so we isolate the decode
        fake.pendingInbox.add(inbox("text/plain", "hello world".toByteArray()))
        pumpViaSend()
        val incoming = bearer.messages.filter { it.incoming }
        assertEquals(1, incoming.size)
        assertEquals("hello world", incoming[0].text)
        assertEquals(2.toUByte(), incoming[0].hops)
    }

    @Test fun imageInboxCarriesBytes() {
        bearer.appInForeground = true
        val img = ByteArray(16) { it.toByte() }
        fake.pendingInbox.add(inbox("image/jpeg", img))
        pumpViaSend()
        val incoming = bearer.messages.first { it.incoming }
        assertTrue(incoming.imageData!!.contentEquals(img))
        assertTrue(incoming.text.isEmpty())
    }

    @Test fun multipartInboxDecodesTextAndImages() {
        bearer.appInForeground = true
        val body = HopBearer.encodeMultipart(
            listOf("text/plain" to "cap".toByteArray(), "image/jpeg" to ByteArray(4) { 9 }),
        )
        fake.pendingInbox.add(inbox("multipart/mixed", body))
        pumpViaSend()
        val incoming = bearer.messages.first { it.incoming }
        assertEquals("cap", incoming.text)
        assertEquals(1, incoming.images.size)
    }

    @Test fun backgroundMessageBumpsUnreadAndNotifies() {
        // SDK 33 (TIRAMISU) WITH the runtime notification permission → the NotificationCompat build path.
        shadowOf(ApplicationProvider.getApplicationContext() as Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        bearer.appInForeground = false
        fake.pendingInbox.add(inbox("text/plain", "ping".toByteArray()))
        pumpViaSend()
        assertEquals(1, bearer.unread.intValue)
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])   // pre-TIRAMISU: notify() skips the permission gate entirely
    fun backgroundNotifyWithoutPermissionGateOnOlderSdk() {
        bearer.appInForeground = false
        fake.pendingInbox.add(inbox("text/plain", "legacy".toByteArray()))
        pumpViaSend()
        assertEquals(1, bearer.unread.intValue)
    }

    @Test fun foregroundDoesNotBumpUnread() {
        bearer.appInForeground = true
        fake.pendingInbox.add(inbox("text/plain", "quiet".toByteArray()))
        pumpViaSend()
        assertEquals(0, bearer.unread.intValue)
        assertFalse(bearer.messages.none { it.incoming })
    }
}
