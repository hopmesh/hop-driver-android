package sh.hopme.driver

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * At-rest mirror store, split out of [HopBearer] so the sealed-file IO the three persistence mirrors
 * (messages / contacts / channels) share lives in one cohesive unit instead of the god-object. Wraps
 * [MirrorCrypto] with the device db key so callers seal/open by concern, and owns the content-addressed
 * image blob store (android-12). Behavior-identical to the code that previously lived inline in
 * HopBearer: seal/open still route through [MirrorCrypto] with [dbKey], legacy plaintext mirrors still
 * read through transparently, and the same-photo-by-SHA256 dedupe on disk is unchanged.
 */
internal class MirrorStore(
    private val dbKey: ByteArray,
    private val rootDir: File,
    private val legacyDir: File = rootDir,
    private val limits: RetentionLimits = RetentionPolicy.defaults,
) {
    private val writer = CoalescingWriter()
    private val messageJournal by lazy { deltaJournal("messages.delta") }
    private val channelJournal by lazy { deltaJournal("channels.delta") }
    /// Image blobs live here as individual content-addressed files, referenced by name from
    /// messages.json (android-12). This keeps the mirror JSON small so the debounced rewrite doesn't
    /// re-base64 megabytes of photo bytes on every message burst.
    private val mediaDir = File(rootDir, "media")
    private val mediaDisk = MediaDisk(
        mediaDir,
        limits,
        encode = { MirrorCrypto.seal(dbKey, it) },
        decode = { if (dbKey.isEmpty()) it else MirrorCrypto.open(dbKey, it) },
    )

    init {
        check(rootDir.mkdirs() || rootDir.isDirectory) { "failed to create no-backup mirror directory" }
        for (name in listOf("messages.json", "contacts.json", "channels.json")) migrateFile(name)
        migrateMedia()
    }

    /** Mirror files live under Android's no-backup directory, never the backed-up files directory. */
    fun file(name: String): File {
        require(name in setOf("messages.json", "contacts.json", "channels.json")) { "unknown mirror file" }
        migrateFile(name)
        return File(rootDir, name)
    }

    /// Seal [plaintext] under the db key (empty key ⇒ plaintext, matching an unencrypted db).
    fun seal(plaintext: ByteArray): ByteArray = MirrorCrypto.seal(dbKey, plaintext)

    fun mediaName(bytes: ByteArray): String = mediaDisk.name(bytes)

    /** Seal and synchronously replace a bounded mirror file. Success means the rename completed. */
    fun write(file: File, plaintext: ByteArray): Boolean {
        if (plaintext.size.toLong() > maximumBytes(file.name)) return false
        return writeAtomic(file, seal(plaintext))
    }

    fun enqueue(key: String, action: () -> Unit) = writer.submit(key, action)

    fun <T> writeNow(key: String, action: () -> T): T = writer.runNow(key, action)

    fun flushAndClose() = writer.close()

    fun appendDelta(name: String, id: ByteArray, payload: ByteArray?): Boolean =
        journal(name).append(id, payload)

    fun replayDeltas(name: String): DeltaReplay = journal(name).replay()

    fun resetDelta(name: String): Boolean = journal(name).reset()

    /** Queue a coalesced full snapshot while retaining the same media transaction used by inbox writes. */
    fun enqueueMessages(messages: List<HopBearer.Message>, completion: (Boolean) -> Unit) {
        writer.submit("messages") {
            completion(persistMessagesLocked(messages, null, null, forceSnapshot = true) ==
                MediaDiskResult.COMMITTED)
        }
    }

    /** Synchronously commit a full message snapshot and release media evicted from it. */
    fun saveMessages(messages: List<HopBearer.Message>): Boolean = writer.runNow("messages") {
        persistMessagesLocked(messages, null, null, forceSnapshot = true) == MediaDiskResult.COMMITTED
    }

    /**
     * Append one inbox decision. Media admission, blob creation, journal sync, any required compaction,
     * and eviction cleanup execute without releasing the writer's ownership.
     */
    fun appendMessageDelta(
        id: ByteArray,
        message: HopBearer.Message?,
        resultingMessages: List<HopBearer.Message>,
    ): Boolean = writer.runNow("messages") {
        persistMessagesLocked(resultingMessages, id, message, forceSnapshot = false) ==
            MediaDiskResult.COMMITTED
    }

    fun getMedia(name: String): ByteArray? {
        return mediaDisk.read(name)
    }

    /** Delete only content-addressed files owned by this mirror and no longer referenced by history. */
    fun gcMedia(messages: List<HopBearer.Message>): Boolean =
        mediaDisk.reconcile(MediaDiskSnapshot(mediaReferences(messages))) == MediaDiskResult.COMMITTED

    /** Reconcile and enforce media disk limits before any persisted attachment body is materialized. */
    fun reconcileMediaOnStartup(): Boolean = writer.runNow("messages") {
        val snapshot = durableMediaSnapshot()
        snapshot.valid && mediaDisk.reconcile(snapshot) == MediaDiskResult.COMMITTED
    }

    /// android-r2-01: read a sealed mirror file back to its plaintext JSON string. Opens the sealed
    /// (AES-GCM, Keystore-wrapped-key) form; if the bytes are a LEGACY plaintext mirror from before
    /// at-rest encryption (or a plain-SQLite dev build with an empty key), returns them as text so the
    /// old file is read once and rewritten sealed. Returns null if the file is absent/unreadable.
    fun read(f: File): String? {
        val maximum = maximumBytes(f.name)
        if (!f.isFile) return null
        if (f.length() > maximum + 128L) {
            quarantine(f)
            return null
        }
        val blob = runCatching { f.readBytes() }.getOrNull() ?: return null
        val plaintext = MirrorCrypto.open(dbKey, blob) ?: if (dbKey.isEmpty() || !MirrorCrypto.isSealed(blob)) blob else {
            quarantine(f)
            return null
        }
        if (plaintext.size.toLong() > maximum) {
            quarantine(f)
            return null
        }
        return String(plaintext, Charsets.UTF_8)
    }

    @Synchronized
    private fun writeAtomic(file: File, bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(tmp).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        true
    }.getOrDefault(false)

    private fun migrateFile(name: String) {
        if (legacyDir.canonicalFile == rootDir.canonicalFile) return
        val old = File(legacyDir, name)
        val current = File(rootDir, name)
        if (!old.isFile || current.exists()) return
        val bytes = runCatching { old.readBytes() }.getOrNull() ?: return
        if (writeAtomic(current, bytes)) old.delete()
    }

    private fun migrateMedia() {
        if (legacyDir.canonicalFile == rootDir.canonicalFile) return
        val oldDir = File(legacyDir, "media")
        if (!Files.isDirectory(oldDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (oldDir.exists()) {
                runCatching {
                    Files.move(
                        oldDir.toPath(),
                        File(legacyDir, "media.legacy-quarantine-${UUID.randomUUID()}").toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }
            }
            return
        }
        var entries = 0
        var inspectedBytes = 0L
        val completed = runCatching {
            Files.newDirectoryStream(oldDir.toPath()).use { stream ->
                for (path in stream) {
                    entries += 1
                    check(entries <= limits.mediaDirectoryFiles)
                    val old = path.toFile()
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !isOwnedMediaName(old.name)) {
                        continue
                    }
                    inspectedBytes = Math.addExact(inspectedBytes, Files.size(path))
                    check(inspectedBytes <= limits.mediaDirectoryScanBytes)
                    val target = File(mediaDir, old.name)
                    if (target.exists()) continue
                    val bytes = old.readBytes()
                    if (writeAtomic(target, bytes)) old.delete()
                }
            }
            true
        }.getOrDefault(false)
        if (!completed) {
            runCatching {
                Files.move(
                    oldDir.toPath(),
                    File(legacyDir, "media.legacy-quarantine-${UUID.randomUUID()}").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
            return
        }
        if (!oldDir.delete()) {
            runCatching {
                Files.move(
                    oldDir.toPath(),
                    File(legacyDir, "media.legacy-quarantine-${UUID.randomUUID()}").toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }
        }
    }

    private fun isOwnedMediaName(name: String): Boolean =
        name.length == 43 && name.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun persistMessagesLocked(
        messages: List<HopBearer.Message>,
        deltaId: ByteArray?,
        deltaMessage: HopBearer.Message?,
        forceSnapshot: Boolean,
    ): MediaDiskResult {
        val resultingReferences = mediaReferences(messages)
        val before = durableMediaSnapshot()
        if (!before.valid) return MediaDiskResult.IO_ERROR
        val removedMedia = before.references.mapTo(HashSet()) { it.name } -
            resultingReferences.mapTo(HashSet()) { it.name }
        val compact = forceSnapshot || removedMedia.isNotEmpty()
        return mediaDisk.commit(
            durableSnapshot = ::durableMediaSnapshot,
            blobs = mediaBlobs(messages),
            resultingReferences = resultingReferences,
        ) {
            if (deltaId != null) {
                val payload = deltaMessage?.let { encodeMessages(listOf(it)) }
                if (!messageJournal.append(deltaId, payload)) return@commit false
            }
            if (compact) {
                val encoded = encodeMessages(messages)
                if (!write(file("messages.json"), encoded)) return@commit false
                if (!messageJournal.reset()) return@commit false
            }
            true
        }
    }

    private fun encodeMessages(messages: List<HopBearer.Message>): ByteArray {
        val references = HashMap<Long, Pair<String?, List<String>>>()
        for (message in messages) {
            val single = message.imageData?.let(mediaDisk::name)
            val multiple = message.images.map(mediaDisk::name)
            if (single != null || multiple.isNotEmpty()) references[message.localId] = single to multiple
        }
        return MessageCodec.encode(messages, references).toByteArray()
    }

    private fun mediaBlobs(messages: List<HopBearer.Message>): List<MediaDiskBlob> = buildList {
        for (message in messages) {
            val peer = message.peer
            message.imageData?.let { add(MediaDiskBlob(it, peer, peer)) }
            message.images.forEach { add(MediaDiskBlob(it, peer, peer)) }
        }
    }

    private fun mediaReferences(messages: List<HopBearer.Message>): List<MediaDiskReference> = buildList {
        for (message in messages) {
            val peer = message.peer
            message.imageData?.let { add(MediaDiskReference(mediaDisk.name(it), peer, peer)) }
            message.images.forEach { add(MediaDiskReference(mediaDisk.name(it), peer, peer)) }
        }
    }

    private fun durableMediaSnapshot(): MediaDiskSnapshot {
        val snapshotExists = file("messages.json").exists()
        val text = read(file("messages.json"))
        if (snapshotExists && text == null) return MediaDiskSnapshot(emptyList(), valid = false)
        val snapshotRows = text?.let(MessageCodec::mediaRows) ?: emptyList()
        if (text != null && snapshotRows.isEmpty() && text != "[]") {
            return MediaDiskSnapshot(emptyList(), valid = false)
        }
        val withoutId = ArrayList<MessageCodec.MediaRow>()
        val byId = LinkedHashMap<List<Byte>, MessageCodec.MediaRow>()
        for (row in snapshotRows) {
            val id = row.inboxId
            if (id == null) withoutId.add(row) else byId[id] = row
        }
        val replay = messageJournal.replay()
        if (replay.quarantined) return MediaDiskSnapshot(emptyList(), valid = false)
        for (record in replay.records) {
            val key = record.id.toList()
            val payload = record.payload
            if (payload == null) {
                byId.remove(key)
                continue
            }
            val row = MessageCodec.mediaRows(String(payload, Charsets.UTF_8), maximumElements = 1)
                ?.singleOrNull() ?: return MediaDiskSnapshot(emptyList(), valid = false)
            byId[key] = row
        }
        val references = (withoutId + byId.values).flatMap { row ->
            row.references.map { MediaDiskReference(it, row.peer, row.peer) }
        }
        return MediaDiskSnapshot(references)
    }

    private fun journal(name: String): DeltaJournal = when (name) {
        "messages" -> messageJournal
        "channels" -> channelJournal
        else -> error("unknown delta journal")
    }

    private fun deltaJournal(fileName: String) = DeltaJournal(
        File(rootDir, fileName), dbKey,
        RetentionPolicy.defaults.journalBytes,
        RetentionPolicy.defaults.journalRecords,
        RetentionPolicy.defaults.journalRecordBytes,
    )

    private fun maximumBytes(name: String): Long = when (name) {
        "messages.json" -> RetentionPolicy.defaults.messageMirrorBytes
        "channels.json" -> RetentionPolicy.defaults.channelMirrorBytes
        "contacts.json" -> RetentionPolicy.defaults.contactMirrorBytes
        else -> error("unknown mirror file")
    }

    fun quarantine(file: File) {
        runCatching {
            val target = File(file.parentFile, "${file.name}.quarantine")
            if (target.exists()) target.delete()
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
