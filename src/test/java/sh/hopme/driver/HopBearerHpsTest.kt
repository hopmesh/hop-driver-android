package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import uniffi.hop.HpsAccess
import uniffi.hop.HpsInvite
import uniffi.hop.HpsKind
import uniffi.hop.HpsMessage
import uniffi.hop.HpsMyTopic
import uniffi.hop.HpsTopicInfo
import uniffi.hop.addressBase58

/** cov/android-driver: the hps:// pub/sub surface (§32) - register / subscribe / publish / invite /
 *  accept / decline / leave / join / approve / deny / rekey / browse / reach / members, plus drainHps()
 *  and the startup loadHpsTopics() rebuild. */
class HopBearerHpsTest : DriverTestBase() {

    @Test fun registerHostsATopic() {
        bearer.hpsRegister("lobby", channel = true, access = HpsAccess.OPEN, discoverable = true)
        settle()
        assertTrue(fake.registered.contains("lobby"))
        assertEquals(1, bearer.hpsTopics.size)
        assertTrue(bearer.hpsTopics[0].hosting)
        assertTrue(bearer.hpsTopics[0].writable)
    }

    @Test fun subscribeAddsATopic() {
        val host = addressBase58(ByteArray(32) { 5 })
        bearer.hpsSubscribe(host, "news")
        settle()
        assertTrue(fake.hpsSubscribed.contains("news"))
        assertEquals(1, bearer.hpsTopics.size)
        assertFalse(bearer.hpsTopics[0].hosting)
    }

    @Test fun publishEchoesLocallyAndPersists() {
        bearer.hpsRegister("room", channel = true)
        settle()
        val topic = bearer.hpsTopics.first()
        bearer.hpsPublish(topic, "gm all")
        settle()
        assertTrue(fake.hpsPublished.any { it.first == "room" })
        val thread = bearer.hpsThreads[topic.id]
        assertTrue(thread != null && thread.any { it.text == "gm all" })
    }

    @Test fun incomingHpsMessageAppendsAndCountsUnread() {
        bearer.hpsRegister("room", channel = true)
        settle()
        val topic = bearer.hpsTopics.first()
        fake.pendingHpsMessages.add(HpsMessage(id = ByteArray(32) { 9 }, path = "room", sender = ByteArray(32) { 2 }, body = "yo".toByteArray()))
        // drainHps runs inside pump()
        bearer.hpsPublish(topic, "seed"); settle()
        val thread = bearer.hpsThreads[topic.id]!!
        assertTrue(thread.any { it.text == "yo" })
        assertTrue((bearer.hpsUnread[topic.id] ?: 0) >= 1)
    }

    @Test fun incomingHpsMessageJournalsBeforeCoreAcceptanceAndMainProjection() {
        bearer.hpsRegister("room", channel = true)
        settle()
        val topic = bearer.hpsTopics.first()
        val inboxId = ByteArray(32) { 7 }
        fake.pendingHpsMessages.add(
            HpsMessage(id = inboxId, path = "room", sender = ByteArray(32) { 2 }, body = "durable".toByteArray()),
        )

        bearer.hpsPublish(topic, "seed")
        shadowOf(bearer.coreLooper).idle()
        assertTrue(fake.acceptedHpsIds.single().contentEquals(inboxId))
        assertTrue(filesFile("channels.delta").length() > "durable".length)

        idleMain()
        settle()
        assertTrue(awaitFileContains(filesFile("channels.json"), "durable"))
    }

    @Test fun hpsJournalAppendFailureLeavesCoreItemForRetry() {
        bearer.hpsRegister("room", channel = true)
        settle()
        val topic = bearer.hpsTopics.first()
        val inboxId = ByteArray(32) { 0x71 }
        val journal = filesFile("channels.delta")
        journal.delete()
        assertTrue(journal.mkdir())
        fake.pendingHpsMessages.add(
            HpsMessage(id = inboxId, path = "room", sender = ByteArray(32) { 2 }, body = "retry".toByteArray()),
        )

        // Drive pump without scheduling a channel snapshot that races the obstructed journal path.
        bearer.hpsInvite(topic, ByteArray(32) { 3 })
        settle()
        assertTrue(fake.acceptedHpsIds.isEmpty())
        assertTrue(fake.pendingHpsMessages.isNotEmpty())
        assertFalse(bearer.hpsThreads[topic.id].orEmpty().any { it.inboxId?.contentEquals(inboxId) == true })

        assertFalse(journal.exists())
        bearer.hpsInvite(topic, ByteArray(32) { 3 })
        settle()
        assertTrue(fake.acceptedHpsIds.single().contentEquals(inboxId))
        assertEquals(1, bearer.hpsThreads[topic.id].orEmpty().count { it.inboxId?.contentEquals(inboxId) == true })
    }

    @Test fun openTopicClearsUnread() {
        bearer.hpsRegister("room", channel = true); settle()
        val id = bearer.hpsTopics.first().id
        bearer.hpsUnread[id] = 4
        bearer.openTopic(id)
        assertEquals(0, bearer.hpsUnread[id])
        bearer.closeTopic()
    }

    @Test fun inviteAcceptDeclineLeaveFlow() {
        bearer.hpsRegister("club", channel = false, access = HpsAccess.INVITE); settle()
        val topic = bearer.hpsTopics.first()
        bearer.hpsInvite(topic, ByteArray(32) { 4 }); settle()

        val inv = HpsInvite(path = "vip", host = ByteArray(32) { 8 }, kind = HpsKind.CHANNEL)
        fake.pendingHpsInvites.add(inv)
        // drainHps surfaces the invite
        bearer.hpsPublish(topic, "x"); settle()
        assertTrue(bearer.hpsInvites.any { it.path == "vip" })

        bearer.hpsAcceptInvite(inv); settle()
        assertTrue(bearer.hpsTopics.any { it.path == "vip" })
        assertFalse(bearer.hpsInvites.any { it.path == "vip" })

        val inv2 = HpsInvite(path = "spam", host = ByteArray(32) { 9 }, kind = HpsKind.CHANNEL)
        bearer.hpsInvites.add(inv2)
        bearer.hpsDeclineInvite(inv2); settle()
        assertFalse(bearer.hpsInvites.any { it.path == "spam" })

        val vip = bearer.hpsTopics.first { it.path == "vip" }
        bearer.hpsLeave(vip); settle()
        assertFalse(bearer.hpsTopics.any { it.path == "vip" })
    }

    @Test fun joinApproveDenyRekey() {
        val info = HpsTopicInfo(host = ByteArray(32) { 6 }, path = "open", kind = HpsKind.CHANNEL,
            title = "Open", summary = "", access = HpsAccess.OPEN)
        bearer.hpsJoin(info); settle()
        assertTrue(bearer.hpsTopics.any { it.path == "open" })

        bearer.hpsRegister("gated", channel = false, access = HpsAccess.REQUEST_TO_JOIN); settle()
        val topic = bearer.hpsTopics.first { it.path == "gated" }
        bearer.hpsApprove(topic, ByteArray(32) { 1 }); settle()
        bearer.hpsDeny(topic, ByteArray(32) { 2 }); settle()
        bearer.hpsRekey(topic, remove = listOf(ByteArray(32) { 2 })); settle()
        // no throw + still hosting
        assertTrue(topic.hosting)
    }

    @Test fun browseReachMembersPendingReadThrough() {
        fake.discoverable = listOf(
            HpsTopicInfo(host = ByteArray(32) { 6 }, path = "p", kind = HpsKind.CHANNEL, title = "t", summary = "s", access = HpsAccess.OPEN),
        )
        fake.reach = 5u
        fake.members = listOf(ByteArray(32) { 1 })
        fake.pending = listOf(ByteArray(32) { 2 })
        assertEquals(1, bearer.hpsBrowse().size)
        bearer.hpsRegister("room", channel = true); settle()
        val topic = bearer.hpsTopics.first()
        assertEquals(5, bearer.hpsReach(topic))
        assertEquals(1, bearer.hpsMembers(topic).size)
        assertEquals(1, bearer.hpsPending(topic).size)
    }

    @Test fun startRebuildsPersistedTopics() {
        fake.myTopics = listOf(
            HpsMyTopic(host = ByteArray(32) { 3 }, path = "saved", kind = HpsKind.CHANNEL, hosting = true, access = HpsAccess.OPEN),
        )
        bearer.start("Rebuild")
        settle()
        assertTrue(bearer.hpsTopics.any { it.path == "saved" })
    }
}
