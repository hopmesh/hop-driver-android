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
import java.io.File

/** cov/android-driver: pump()'s durable inbox poll - text / image / multipart decode, unread badge, and the
 *  notification path (both the granted-permission build and the denied early-return). */
class HopBearerInboxTest : DriverTestBase() {

    private fun sender(b: Byte = 3) = ByteArray(32) { b }
    private fun inbox(ct: String, body: ByteArray, from: ByteArray = sender(), id: ByteArray = ByteArray(32) { 4 }) =
        InboxMessage(id = id, from = from, contentType = ct, body = body, hops = 2u, createdAt = 1uL, trace = emptyList())

    /** pump() is private; a private-mode update ends with pump() without adding an outgoing message. */
    private fun pumpViaSend() {
        bearer.setPrivateMode(true)
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
        assertTrue(fake.acceptedInboxIds.single().contentEquals(incoming[0].inboxId))
    }

    @Test fun imageInboxCarriesBytes() {
        bearer.appInForeground = true
        val img = ByteArray(16) { it.toByte() }
        bearer.rememberContact(HopBearer.Peer(sender(), "known", 0u))
        fake.pendingInbox.add(inbox("image/jpeg", img))
        pumpViaSend()
        val incoming = bearer.messages.first { it.incoming }
        assertTrue(incoming.imageData!!.contentEquals(img))
        assertTrue(incoming.text.isEmpty())
    }

    @Test fun multipartInboxDecodesTextAndImages() {
        bearer.appInForeground = true
        bearer.rememberContact(HopBearer.Peer(sender(), "known", 0u))
        val body = HopBearer.encodeMultipart(
            listOf("text/plain" to "cap".toByteArray(), "image/jpeg" to ByteArray(4) { 9 }),
        )
        fake.pendingInbox.add(inbox("multipart/mixed", body))
        pumpViaSend()
        val incoming = bearer.messages.first { it.incoming }
        assertEquals("cap", incoming.text)
        assertEquals(1, incoming.images.size)
    }

    @Test fun unknownIdentityAttachmentIsRejectedButDurablyAcknowledged() {
        bearer.appInForeground = true
        val id = ByteArray(32) { 0x31 }
        fake.pendingInbox.add(inbox("image/jpeg", ByteArray(16) { 7 }, id = id))
        pumpViaSend()

        assertTrue(bearer.messages.none { it.incoming && it.imageData != null })
        assertTrue(fake.acceptedInboxIds.single().contentEquals(id))
        assertTrue(fake.pendingInbox.isEmpty())
    }

    @Test fun filesystemQuotaFailureLeavesKnownMediaPendingAndUnwritten() {
        val limits = RetentionLimits(
            attachmentBytes = 8,
            peerMediaBytes = 4,
            conversationMediaBytes = 4,
            globalMediaBytes = 4,
        )
        bearer = newBearer(fake, limits = limits)
        bearer.appInForeground = true
        bearer.rememberContact(HopBearer.Peer(sender(), "known", 0u))
        fake.pendingInbox.add(inbox("image/jpeg", ByteArray(5) { 7 }))

        pumpViaSend()

        assertTrue(fake.acceptedInboxIds.isEmpty())
        assertEquals(1, fake.pendingInbox.size)
        assertTrue(File(context.noBackupFilesDir, "media").listFiles().orEmpty().isEmpty())
    }

    @Test fun unknownMultipartKeepsTextButDropsMedia() {
        bearer.appInForeground = true
        val body = HopBearer.encodeMultipart(
            listOf("text/plain" to "safe text".toByteArray(), "image/jpeg" to ByteArray(4) { 9 }),
        )
        fake.pendingInbox.add(inbox("multipart/mixed", body, from = sender(8)))
        pumpViaSend()

        val incoming = bearer.messages.first { it.incoming }
        assertEquals("safe text", incoming.text)
        assertTrue(incoming.images.isEmpty())
        assertEquals("text/plain", incoming.contentType)
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

    @Test fun repeatedInboxIdIsPersistedOnceAndAccepted() {
        bearer.appInForeground = true
        val id = ByteArray(32) { 9 }
        fake.pendingInbox.add(inbox("text/plain", "once".toByteArray(), id = id))
        pumpViaSend()

        fake.pendingInbox.add(inbox("text/plain", "once".toByteArray(), id = id))
        pumpViaSend()

        assertEquals(1, bearer.messages.count { it.incoming && it.inboxId?.contentEquals(id) == true })
        assertEquals(2, fake.acceptedInboxIds.count { it.contentEquals(id) })
    }

    @Test fun journalAppendFailureRetainsInboxUntilARetrySucceeds() {
        bearer.appInForeground = true
        val id = ByteArray(32) { 8 }
        val journalPath = filesFile("messages.delta")
        assertTrue(journalPath.mkdir())
        fake.pendingInbox.add(inbox("text/plain", "retry".toByteArray(), id = id))

        pumpViaSend()

        assertTrue(fake.acceptedInboxIds.isEmpty())
        assertEquals(1, fake.pendingInbox.size)
        assertEquals(0, bearer.messages.count { it.inboxId?.contentEquals(id) == true })

        assertFalse(journalPath.isDirectory)
        assertTrue(filesFile("messages.delta.quarantine").isDirectory)
        pumpViaSend()

        assertTrue(fake.acceptedInboxIds.single().contentEquals(id))
        assertTrue(fake.pendingInbox.isEmpty())
        assertEquals(1, bearer.messages.count { it.inboxId?.contentEquals(id) == true })
    }
}
