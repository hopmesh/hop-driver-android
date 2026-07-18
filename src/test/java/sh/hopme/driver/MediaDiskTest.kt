package sh.hopme.driver

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class MediaDiskTest {
    private lateinit var root: File
    private val media get() = File(root, "media")

    @Before fun setUp() {
        root = Files.createTempDirectory("hop-media-").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun limits(
        global: Long = 8,
        peer: Long = 8,
        conversation: Long = 8,
        attachment: Long = 8,
        files: Int = 32,
        scanBytes: Long = 256,
    ) = RetentionLimits(
        globalMediaBytes = global,
        peerMediaBytes = peer,
        conversationMediaBytes = conversation,
        attachmentBytes = attachment,
        mediaDirectoryFiles = files,
        mediaDirectoryScanBytes = scanBytes,
    )

    private fun disk(limits: RetentionLimits = limits()) = MediaDisk(
        media,
        limits,
        encode = { it },
        decode = { it },
    )

    private fun reference(
        disk: MediaDisk,
        data: ByteArray,
        peer: String = "p",
        conversation: String = "c",
    ) = MediaDiskReference(disk.name(data), peer, conversation)

    private fun files(): List<File> = media.listFiles().orEmpty().filter { !it.name.startsWith(".") }

    @Test fun capAndCapPlusOneUseActualFilesystemBytes() {
        val subject = disk()
        var durable = emptyList<MediaDiskReference>()
        for (data in listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))) {
            val projected = durable + reference(subject, data)
            assertEquals(MediaDiskResult.COMMITTED, subject.commit(
                durableSnapshot = { MediaDiskSnapshot(durable) },
                blobs = listOf(MediaDiskBlob(data, "p", "c")),
                resultingReferences = projected,
                durableCommit = { durable = projected; true },
            ))
        }
        assertEquals(8L, subject.usageBytesForTest())

        val extra = byteArrayOf(9)
        assertEquals(MediaDiskResult.QUOTA, subject.commit(
            durableSnapshot = { MediaDiskSnapshot(durable) },
            blobs = listOf(MediaDiskBlob(extra, "p", "c")),
            resultingReferences = durable + reference(subject, extra),
            durableCommit = { throw AssertionError("over-cap content reached durable commit") },
        ))
        assertEquals(8L, subject.usageBytesForTest())
    }

    @Test fun sustainedUniqueAttachmentChurnDeletesEachEvictionSynchronously() {
        val subject = disk(limits(global = 4, peer = 4, conversation = 4, attachment = 4))
        var durable = emptyList<MediaDiskReference>()
        repeat(100) { value ->
            val data = byteArrayOf(value.toByte(), (value ushr 8).toByte(), 0x55, 0x2a)
            val ref = reference(subject, data)
            assertEquals(MediaDiskResult.COMMITTED, subject.commit(
                durableSnapshot = { MediaDiskSnapshot(durable) },
                blobs = listOf(MediaDiskBlob(data, "p", "c")),
                resultingReferences = listOf(ref),
                durableCommit = { durable = listOf(ref); true },
            ))
            assertEquals(4L, subject.usageBytesForTest())
            assertEquals(listOf(ref.name), files().map { it.name })
        }
    }

    @Test fun journalSnapshotAndInsertionFailureAfterBlobWriteRollBackTheNewFile() {
        val subject = disk()
        val data = byteArrayOf(1, 3, 5, 7)
        val ref = reference(subject, data)
        var durable = emptyList<MediaDiskReference>()
        val journalFile = File(root, "messages.delta").apply { mkdir() }
        val journal = DeltaJournal(journalFile, ByteArray(0), 1_024, 4, 128)
        val snapshotFile = File(root, "messages.json").apply { mkdir() }
        val failures = listOf<Pair<String, () -> Boolean>>(
            "journal" to { journal.append(byteArrayOf(1), byteArrayOf(2)) },
            "snapshot" to { runCatching { snapshotFile.writeBytes(byteArrayOf(1)); true }.getOrDefault(false) },
            "insertion" to { false },
        )
        for ((failure, commit) in failures) {
            assertEquals(MediaDiskResult.IO_ERROR, subject.commit(
                durableSnapshot = { MediaDiskSnapshot(durable) },
                blobs = listOf(MediaDiskBlob(data, "p", "c")),
                resultingReferences = listOf(ref),
                durableCommit = {
                    assertTrue("$failure fails after blob write", File(media, ref.name).isFile)
                    commit()
                },
            ))
            assertTrue(files().isEmpty())
            assertTrue(durable.isEmpty())
        }
    }

    @Test fun sharedBlobSurvivesUntilItsLastReferenceIsEvicted() {
        val subject = disk()
        val data = byteArrayOf(2, 4, 6, 8)
        val alice = reference(subject, data, "alice", "a")
        val bob = reference(subject, data, "bob", "b")
        var durable = emptyList<MediaDiskReference>()
        assertEquals(MediaDiskResult.COMMITTED, subject.commit(
            { MediaDiskSnapshot(durable) }, listOf(MediaDiskBlob(data, "alice", "a")),
            listOf(alice, bob), { durable = listOf(alice, bob); true },
        ))
        assertEquals(1, files().size)
        assertEquals(MediaDiskResult.COMMITTED, subject.commit(
            { MediaDiskSnapshot(durable) }, emptyList(), listOf(bob), { durable = listOf(bob); true },
        ))
        assertEquals(1, files().size)
        assertEquals(MediaDiskResult.COMMITTED, subject.commit(
            { MediaDiskSnapshot(durable) }, emptyList(), emptyList(), { durable = emptyList(); true },
        ))
        assertTrue(files().isEmpty())
    }

    @Test fun peerAndConversationCapsCountSharedFilesPerOwner() {
        val subject = disk(limits(global = 16, peer = 8, conversation = 4, attachment = 8))
        val first = byteArrayOf(1, 1, 1, 1)
        val second = byteArrayOf(2)
        val firstRef = reference(subject, first)
        val secondRef = reference(subject, second)
        var durable = emptyList<MediaDiskReference>()
        assertEquals(MediaDiskResult.COMMITTED, subject.commit(
            { MediaDiskSnapshot(durable) }, listOf(MediaDiskBlob(first, "p", "c")), listOf(firstRef),
            { durable = listOf(firstRef); true },
        ))
        assertEquals(MediaDiskResult.QUOTA, subject.commit(
            { MediaDiskSnapshot(durable) }, listOf(MediaDiskBlob(second, "p", "c")),
            listOf(firstRef, secondRef), { throw AssertionError("conversation cap plus one committed") },
        ))
    }

    @Test fun restartDeletesOrphansAndQuarantinesForeignAndNonRegularEntries() {
        val subject = disk(limits(scanBytes = 4))
        media.mkdirs()
        val orphan = byteArrayOf(9, 8, 7)
        File(media, subject.name(orphan)).writeBytes(orphan)
        File(media, "foreign").writeBytes(byteArrayOf(1))
        val outside = File(root, "outside").apply { writeBytes(byteArrayOf(2)) }
        Files.createSymbolicLink(File(media, "a".repeat(43)).toPath(), outside.toPath())
        File(media, "b".repeat(43)).mkdir()

        assertEquals(MediaDiskResult.COMMITTED, subject.reconcile(MediaDiskSnapshot(emptyList())))
        assertTrue(files().isEmpty())
        assertArrayEquals(byteArrayOf(2), outside.readBytes())
        assertEquals(3, File(root, "media.quarantine").listFiles().orEmpty().size)
    }

    @Test fun restartRestoresInterruptedEvictionAndBoundsDirectoryEnumeration() {
        val data = byteArrayOf(4, 3, 2, 1)
        val subject = disk(limits(global = 8, peer = 8, conversation = 8, attachment = 8, files = 4))
        val ref = reference(subject, data)
        File(root, ".media.transaction").apply { mkdirs() }.resolve(ref.name).writeBytes(data)
        assertEquals(MediaDiskResult.COMMITTED, subject.reconcile(MediaDiskSnapshot(listOf(ref))))
        assertArrayEquals(data, subject.read(ref.name))

        val bounded = disk(limits(global = 8, peer = 8, conversation = 8, attachment = 8, files = 1))
        File(media, bounded.name(byteArrayOf(7))).writeBytes(byteArrayOf(7))
        File(media, bounded.name(byteArrayOf(8))).writeBytes(byteArrayOf(8))
        assertEquals(MediaDiskResult.IO_ERROR, bounded.reconcile(MediaDiskSnapshot(emptyList())))

        media.deleteRecursively()
        val byteBounded = disk(limits(
            global = 8, peer = 8, conversation = 8, attachment = 8, files = 4, scanBytes = 1,
        ))
        media.mkdirs()
        File(media, byteBounded.name(byteArrayOf(1, 2))).writeBytes(byteArrayOf(1, 2))
        assertEquals(MediaDiskResult.IO_ERROR, byteBounded.reconcile(MediaDiskSnapshot(emptyList())))
    }

    @Test fun concurrentAdmissionCannotExceedTheGlobalCap() {
        val subject = disk(limits(global = 8, peer = 8, conversation = 8, attachment = 2))
        var durable = emptyList<MediaDiskReference>()
        var accepted = 0
        val executor = Executors.newFixedThreadPool(8)
        repeat(32) { value ->
            executor.submit {
                val data = byteArrayOf(value.toByte(), 0xff.toByte())
                val ref = reference(subject, data)
                var projected = emptyList<MediaDiskReference>()
                subject.commit(
                    durableSnapshot = { MediaDiskSnapshot(durable) },
                    blobs = listOf(MediaDiskBlob(data, "p", "c")),
                    resultingReferences = {
                        projected = durable + ref
                        projected
                    },
                    durableCommit = {
                        durable = projected
                        accepted += 1
                        true
                    },
                )
            }
        }
        executor.shutdown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(4, accepted)
        assertEquals(8L, subject.usageBytesForTest())
        assertEquals(4, files().size)
    }
}
