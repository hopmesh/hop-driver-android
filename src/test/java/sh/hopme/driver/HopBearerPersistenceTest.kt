package sh.hopme.driver

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import uniffi.hop.HpsAccess
import uniffi.hop.InboxMessage
import uniffi.hop.addressBase58
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration

/** cov/android-driver: the on-disk mirror round-trips - messages.json (+ externalized media),
 *  contacts.json, channels.json - written by one driver and re-read by a "restart" (start()) driver,
 *  in both the plaintext (empty key) and AES-GCM-sealed (32-byte key) forms. */
class HopBearerPersistenceTest : DriverTestBase() {

    private fun stopForRestart(bearer: HopBearer) {
        bearer.teardown()
        runCatching { shadowOf(bearer.coreLooper).idle() }
    }

    private fun restart(cfg: HopConfig): HopBearer {
        val b = newBearer(FakeHopNode(), cfg)
        b.start("Restart")
        settleOn(b)
        return b
    }

    private fun restart(previous: HopBearer, cfg: HopConfig): HopBearer {
        stopForRestart(previous)
        return restart(cfg)
    }

    @Test fun messagesAndMediaSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("persist me", HopBearer.Peer(ByteArray(32) { 2 }, "Bob", 0u))
        b1.sendImage(ByteArray(32) { 5 }, HopBearer.Peer(ByteArray(32) { 3 }, "Cy", 0u))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "persist me"))

        val b2 = restart(b1, cfg)
        assertTrue("text survived", b2.messages.any { it.text == "persist me" })
        assertTrue("image survived via media/", b2.messages.any { it.imageData != null })
    }

    @Test fun contactsSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        val addr = ByteArray(32) { 12 }
        b1.rememberContact(HopBearer.Peer(addr, "Dana", 0u, active = false, platform = "android", app = "HopDemo"))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("contacts.json"), "Dana"))

        val b2 = restart(b1, cfg)
        assertEquals("Dana", b2.displayName(addr))
    }

    @Test fun channelThreadsSurviveARestart() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.hpsRegister("townsquare", channel = true, access = HpsAccess.OPEN)
        settleOn(b1)
        val topic = b1.hpsTopics.first()
        b1.hpsPublish(topic, "persisted post")
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("channels.json"), "persisted post"))

        val b2 = restart(b1, cfg)
        val thread = b2.hpsThreads[topic.id]
        assertTrue("channel post survived", thread != null && thread.any { it.text == "persisted post" })
    }

    @Test fun sealedMirrorsRoundTripWithADbKey() {
        val cfg = defaultConfig(dbKey = ByteArray(32) { 0x7 })
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("secret msg", HopBearer.Peer(ByteArray(32) { 4 }, "Eve", 0u))
        settleOn(b1)
        assertTrue(awaitFile(filesFile("messages.json")))
        val bytes = filesFile("messages.json").readBytes()
        assertTrue("mirror is AES-GCM sealed, not plaintext JSON", MirrorCrypto.isSealed(bytes))

        val b2 = restart(b1, cfg)
        assertTrue("sealed history re-read on restart", b2.messages.any { it.text == "secret msg" })
    }

    @Test fun legacyPlaintextMirrorStillReads() {
        // an existing install's cleartext mirror (empty key) must round-trip
        val cfg = defaultConfig()
        val addr58 = addressBase58(ByteArray(32) { 9 })
        assertTrue(addr58.isNotEmpty())
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("cleartext", HopBearer.Peer(ByteArray(32) { 9 }, "F", 0u))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "cleartext"))
        val bytes = filesFile("messages.json").readBytes()
        assertTrue("empty key => plaintext JSON array", bytes.isNotEmpty() && bytes[0] == '['.code.toByte())
        val b2 = restart(b1, cfg)
        assertTrue(b2.messages.any { it.text == "cleartext" })
    }

    @Test fun startupInboxMergeDoesNotOverwriteRestoredHistory() {
        val cfg = defaultConfig()
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("older", HopBearer.Peer(ByteArray(32) { 9 }, "Old", 0u))
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "older"))
        stopForRestart(b1)

        val fake2 = FakeHopNode()
        fake2.pendingInbox.add(InboxMessage(
            id = ByteArray(32) { 6 },
            from = ByteArray(32) { 7 },
            contentType = "text/plain",
            body = "newer".toByteArray(),
            hops = 1u,
            createdAt = 1uL,
            trace = emptyList(),
        ))
        val b2 = newBearer(fake2, cfg)
        b2.start("Restart")

        // Run startup and the first inbox pump before allowing main to apply the restored rows.
        shadowOf(b2.coreLooper).idleFor(Duration.ofMillis(1_200))
        shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
        settleOn(b2)

        assertTrue(fake2.acceptedInboxIds.single().contentEquals(ByteArray(32) { 6 }))
        assertTrue(b2.messages.any { it.text == "older" })
        assertTrue(b2.messages.any { it.text == "newer" })
        assertTrue(awaitFileContains(filesFile("messages.json"), "older"))
        assertTrue(awaitFileContains(filesFile("messages.json"), "newer"))
    }

    @Test fun mediaAndConversationDeletionPersistAndGarbageCollectFiles() {
        val cfg = defaultConfig()
        val peer = HopBearer.Peer(ByteArray(32) { 0x44 }, "Delete", 0u)
        val b1 = newBearer(FakeHopNode(), cfg)
        b1.send("keep until conversation delete", peer)
        b1.sendImage(ByteArray(64) { 5 }, peer)
        settleOn(b1)
        assertTrue(awaitFileContains(filesFile("messages.json"), "imageRef"))
        val mediaDir = File(context.noBackupFilesDir, "media")
        assertTrue(mediaDir.listFiles().orEmpty().isNotEmpty())

        b1.deleteMedia(peer)
        settleOn(b1)
        assertTrue(mediaDir.listFiles().orEmpty().isEmpty())
        assertTrue(b1.messages.filter { it.peer == b1.keyFor(peer) }.all { it.imageData == null && it.images.isEmpty() })

        b1.deleteConversation(peer)
        settleOn(b1)
        assertTrue(b1.messages.none { it.peer == b1.keyFor(peer) })
        val b2 = restart(b1, cfg)
        assertTrue(b2.messages.none { it.peer == b2.keyFor(peer) })
    }

    @Test fun startupReconciliationDeletesOnlyOwnedOrphanMedia() {
        val mediaDir = File(context.noBackupFilesDir, "media").apply { mkdirs() }
        val orphan = File(mediaDir, "A".repeat(43)).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val foreign = File(mediaDir, "do-not-delete").apply { writeText("foreign") }

        restart(defaultConfig())

        assertTrue("owned content-addressed orphan is removed", !orphan.exists())
        assertFalse("foreign files leave the owned directory", foreign.exists())
        assertTrue(File(context.noBackupFilesDir, "media.quarantine").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun startupReplaysJournalAndDeduplicatesSnapshotAfterEitherCompactionCrashPoint() {
        val id = ByteArray(32) { 0x6a }
        val row = HopBearer.Message(
            localId = 1, peer = "peer", text = "journal-only", incoming = true, inboxId = id,
        )
        val payload = MessageCodec.encode(listOf(row), emptyMap()).toByteArray()
        val journalFile = filesFile("messages.delta")
        val journal = DeltaJournal(
            journalFile, ByteArray(0), RetentionPolicy.defaults.journalBytes,
            RetentionPolicy.defaults.journalRecords, RetentionPolicy.defaults.journalRecordBytes,
        )
        assertTrue(journal.append(id, payload))

        val beforeSnapshot = restart(defaultConfig())
        assertEquals(1, beforeSnapshot.messages.count { it.inboxId?.contentEquals(id) == true })
        assertTrue(DeltaJournal(
            journalFile, ByteArray(0), RetentionPolicy.defaults.journalBytes,
            RetentionPolicy.defaults.journalRecords, RetentionPolicy.defaults.journalRecordBytes,
        ).replay().records.isEmpty())
        beforeSnapshot.teardown()
        runCatching { shadowOf(beforeSnapshot.coreLooper).idle() }

        filesFile("messages.json").writeText(MessageCodec.encode(listOf(row), emptyMap()))
        assertTrue(journal.append(id, payload))
        val afterSnapshot = restart(defaultConfig())
        assertEquals(1, afterSnapshot.messages.count { it.inboxId?.contentEquals(id) == true })
    }

    @Test fun startupReplaysChannelJournalAndDeduplicatesSnapshotAfterEitherCompactionCrashPoint() {
        val id = ByteArray(32) { 0x6b }
        val topic = "host/topic"
        val row = HopBearer.HpsMsg(
            id = 1, path = "topic", sender = ByteArray(32) { 3 }, text = "journal-only", inboxId = id,
        )
        val payload = ChannelCodec.encode(mapOf(topic to listOf(row))).toByteArray()
        val journalFile = filesFile("channels.delta")
        val journal = DeltaJournal(
            journalFile, ByteArray(0), RetentionPolicy.defaults.journalBytes,
            RetentionPolicy.defaults.journalRecords, RetentionPolicy.defaults.journalRecordBytes,
        )
        assertTrue(journal.append(id, payload))

        val beforeSnapshot = restart(defaultConfig())
        assertEquals(1, beforeSnapshot.hpsThreads[topic].orEmpty().count { it.inboxId?.contentEquals(id) == true })
        assertTrue(DeltaJournal(
            journalFile, ByteArray(0), RetentionPolicy.defaults.journalBytes,
            RetentionPolicy.defaults.journalRecords, RetentionPolicy.defaults.journalRecordBytes,
        ).replay().records.isEmpty())
        beforeSnapshot.teardown()
        runCatching { shadowOf(beforeSnapshot.coreLooper).idle() }

        assertTrue(journal.append(id, payload))
        val afterSnapshot = restart(defaultConfig())
        assertEquals(1, afterSnapshot.hpsThreads[topic].orEmpty().count { it.inboxId?.contentEquals(id) == true })
    }

    @Test fun oversizedHistoryMirrorsAreQuarantinedBeforeJsonDecode() {
        for ((mirror, maximum) in listOf(
            filesFile("messages.json") to RetentionPolicy.defaults.messageMirrorBytes,
            filesFile("channels.json") to RetentionPolicy.defaults.channelMirrorBytes,
        )) {
            RandomAccessFile(mirror, "rw").use { it.setLength(maximum + 129L) }
        }
        val restarted = restart(defaultConfig())
        assertTrue(restarted.messages.isEmpty())
        assertTrue(filesFile("messages.json.quarantine").isFile)
        assertTrue(filesFile("channels.json.quarantine").isFile)
    }
}
