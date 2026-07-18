package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class DeltaJournalTest {
    private fun withDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("hop-delta-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun appendSyncReplayCapAndReset() = withDirectory { directory ->
        val file = directory.resolve("messages.delta")
        val journal = DeltaJournal(file, ByteArray(0), 1_024, 2, 128)
        assertTrue(journal.append(byteArrayOf(1), "one".toByteArray()))
        val firstLength = file.length()
        assertTrue(journal.append(byteArrayOf(2), null))
        assertFalse(journal.append(byteArrayOf(3), "three".toByteArray()))
        assertEquals(2, journal.replay().records.size)
        assertTrue("one-record append is independent of snapshot size", file.length() - firstLength < 128)
        assertTrue(journal.reset())
        assertTrue(journal.replay().records.isEmpty())
    }

    @Test fun encryptedRecordsReplayAndCorruptionIsQuarantined() = withDirectory { directory ->
        val file = directory.resolve("channels.delta")
        val key = ByteArray(32) { 7 }
        val journal = DeltaJournal(file, key, 4_096, 4, 256)
        assertTrue(journal.append(ByteArray(32) { 1 }, "secret".toByteArray()))
        assertEquals("secret", String(journal.replay().records.single().payload!!))

        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val replay = DeltaJournal(file, key, 4_096, 4, 256).replay()
        assertTrue(replay.quarantined)
        assertTrue(directory.resolve("channels.delta.quarantine").isFile)
    }

    @Test fun oversizedJournalIsQuarantinedBeforeFrameMaterialization() = withDirectory { directory ->
        val file = directory.resolve("messages.delta")
        file.writeBytes(ByteArray(1_025))
        val replay = DeltaJournal(file, ByteArray(0), 1_024, 4, 128).replay()
        assertTrue(replay.quarantined)
        assertTrue(replay.records.isEmpty())
        assertTrue(directory.resolve("messages.delta.quarantine").isFile)
    }

    @Test fun invalidInputsAndByteCapAreRejectedWithoutGrowingTheFile() = withDirectory { directory ->
        val file = directory.resolve("messages.delta")
        val journal = DeltaJournal(file, ByteArray(0), 59, 8, 8)
        assertFalse(journal.append(ByteArray(0), ByteArray(0)))
        assertFalse(journal.append(ByteArray(65), ByteArray(0)))
        assertFalse(journal.append(byteArrayOf(1), ByteArray(9)))
        assertTrue(journal.append(byteArrayOf(1), ByteArray(4)))
        assertEquals(59, file.length())
        assertFalse(journal.append(byteArrayOf(2), ByteArray(0)))
        assertEquals(59, file.length())
    }

    @Test fun truncatedTailReplaysOnlyTheCompleteSyncedPrefix() = withDirectory { directory ->
        val file = directory.resolve("messages.delta")
        val writer = DeltaJournal(file, ByteArray(0), 4_096, 4, 256)
        assertTrue(writer.append(byteArrayOf(1), "complete".toByteArray()))
        assertTrue(writer.append(byteArrayOf(2), "truncated".toByteArray()))
        RandomAccessFile(file, "rw").use { it.setLength(file.length() - 1) }

        val replay = DeltaJournal(file, ByteArray(0), 4_096, 4, 256).replay()
        assertTrue(replay.quarantined)
        assertEquals(1, replay.records.size)
        assertTrue(replay.records.single().id.contentEquals(byteArrayOf(1)))
    }

    @Test fun appendRetriesAfterParentIoFailure() = withDirectory { directory ->
        val parent = directory.resolve("not-a-directory").apply { writeBytes(byteArrayOf(1)) }
        val journal = DeltaJournal(parent.resolve("messages.delta"), ByteArray(0), 1_024, 2, 128)
        assertFalse(journal.append(byteArrayOf(1), "one".toByteArray()))
        assertTrue(parent.delete())
        assertTrue(parent.mkdir())
        assertTrue(journal.append(byteArrayOf(1), "one".toByteArray()))
    }
}
