package sh.hopme.driver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MirrorLocationTest : DriverTestBase() {
    @Test fun mirrorsLiveUnderAndroidNoBackupStorage() {
        val peer = HopBearer.Peer(ByteArray(32) { 2 }, "peer", 0u)
        bearer.send("no backup", peer)
        settle()
        assertTrue(awaitFile(filesFile("messages.json")))
        assertTrue(filesFile("messages.json").canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(File(context.filesDir, "messages.json").exists())
    }

    @Test fun legacyMirrorMovesOnlyAfterTheNewCopyCommits() {
        val legacy = File(context.cacheDir, "legacy-${System.nanoTime()}").apply { mkdirs() }
        val current = File(context.cacheDir, "current-${System.nanoTime()}").apply { mkdirs() }
        val bytes = "legacy history".toByteArray()
        File(legacy, "messages.json").writeBytes(bytes)

        val store = MirrorStore(ByteArray(0), current, legacy)

        assertArrayEquals(bytes, store.file("messages.json").readBytes())
        assertFalse(File(legacy, "messages.json").exists())
        legacy.deleteRecursively(); current.deleteRecursively()
    }

    @Test fun mediaLookupRejectsTraversalAndForeignNames() {
        val root = File(context.cacheDir, "mirror-${System.nanoTime()}").apply { mkdirs() }
        val store = MirrorStore(ByteArray(0), root)
        File(root.parentFile, "outside").writeText("secret")
        assertNull(store.getMedia("../outside"))
        assertNull(store.getMedia("not-a-content-hash"))
        root.deleteRecursively()
    }
}
