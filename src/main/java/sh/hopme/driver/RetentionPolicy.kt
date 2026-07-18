package sh.hopme.driver

internal data class RetentionLimits(
    val globalMessages: Int = 5_000,
    val globalMessageBytes: Long = 64L * 1024L * 1024L,
    val peerMessages: Int = 1_000,
    val peerMessageBytes: Long = 16L * 1024L * 1024L,
    val conversationMessages: Int = 500,
    val conversationMessageBytes: Long = 8L * 1024L * 1024L,
    val contacts: Int = 1_000,
    val attachmentBytes: Long = 8L * 1024L * 1024L,
    val peerMediaBytes: Long = 32L * 1024L * 1024L,
    val conversationMediaBytes: Long = 16L * 1024L * 1024L,
    val globalMediaBytes: Long = 128L * 1024L * 1024L,
    val mediaDirectoryFiles: Int = 16_384,
    val mediaDirectoryScanBytes: Long = 256L * 1024L * 1024L,
    val pendingGlobalMessages: Int = 512,
    val pendingGlobalBytes: Long = 32L * 1024L * 1024L,
    val pendingPeerMessages: Int = 128,
    val pendingPeerBytes: Long = 8L * 1024L * 1024L,
    val pendingConversationMessages: Int = 64,
    val pendingConversationBytes: Long = 4L * 1024L * 1024L,
    val metadataEntries: Int = 1_000,
    val conversations: Int = 1_000,
    val messageMirrorBytes: Long = 96L * 1024L * 1024L,
    val channelMirrorBytes: Long = 64L * 1024L * 1024L,
    val contactMirrorBytes: Long = 4L * 1024L * 1024L,
    val journalBytes: Long = 64L * 1024L * 1024L,
    val journalRecords: Int = 4_096,
    val journalRecordBytes: Int = 16 * 1024 * 1024,
)

internal enum class PendingAdmission {
    ACCEPTED,
    GLOBAL_COUNT,
    GLOBAL_BYTES,
    PEER_COUNT,
    PEER_BYTES,
    CONVERSATION_COUNT,
    CONVERSATION_BYTES,
}

/** Hard admission accounting for outgoing messages retention intentionally cannot evict. */
internal class PendingQuota(private val limits: RetentionLimits = RetentionPolicy.defaults) {
    private data class Entry(val peer: String, val conversation: String, val bytes: Long)
    private val entries = LinkedHashMap<Long, Entry>()

    @Synchronized
    fun reserve(id: Long, peer: String, conversation: String, bytes: Long): PendingAdmission {
        if (id in entries) return PendingAdmission.ACCEPTED
        if (entries.size >= limits.pendingGlobalMessages) return PendingAdmission.GLOBAL_COUNT
        if (bytes > limits.pendingGlobalBytes - entries.values.sumOf { it.bytes }) {
            return PendingAdmission.GLOBAL_BYTES
        }
        val peerEntries = entries.values.filter { it.peer == peer }
        if (peerEntries.size >= limits.pendingPeerMessages) return PendingAdmission.PEER_COUNT
        if (bytes > limits.pendingPeerBytes - peerEntries.sumOf { it.bytes }) return PendingAdmission.PEER_BYTES
        val conversationEntries = entries.values.filter { it.conversation == conversation }
        if (conversationEntries.size >= limits.pendingConversationMessages) {
            return PendingAdmission.CONVERSATION_COUNT
        }
        if (bytes > limits.pendingConversationBytes - conversationEntries.sumOf { it.bytes }) {
            return PendingAdmission.CONVERSATION_BYTES
        }
        entries[id] = Entry(peer, conversation, bytes)
        return PendingAdmission.ACCEPTED
    }

    /** Restore already-durable pending rows without evicting them, even if an older build exceeded a cap. */
    @Synchronized
    fun restore(message: HopBearer.Message) {
        if (!message.incoming && !message.delivered && !message.failed) {
            entries[message.localId] = Entry(message.peer, message.peer, RetentionPolicy.messageBytes(message))
        }
    }

    @Synchronized fun release(id: Long) { entries.remove(id) }

    @Synchronized
    fun reconcile(messages: Collection<HopBearer.Message>) {
        entries.clear()
        messages.forEach(::restore)
    }

    @Synchronized fun count(): Int = entries.size
}

internal object RetentionPolicy {
    val defaults = RetentionLimits()

    fun retain(messages: List<HopBearer.Message>, limits: RetentionLimits = defaults): List<HopBearer.Message> {
        val kept = messages.toMutableList()
        while (true) {
            val globalCountExceeded = kept.size > limits.globalMessages
            val globalBytesExceeded = kept.sumOf(::messageBytes) > limits.globalMessageBytes
            val peerCountExceeded = kept.groupingBy { it.peer }.eachCount()
                .filterValues { it > limits.peerMessages }.keys
            val peerBytesExceeded = kept.groupBy { it.peer }.filterValues {
                it.sumOf(::messageBytes) > limits.peerMessageBytes
            }.keys
            val conversationCountExceeded = kept.groupingBy(::conversationKey).eachCount()
                .filterValues { it > limits.conversationMessages }.keys
            val conversationBytesExceeded = kept.groupBy(::conversationKey).filterValues {
                it.sumOf(::messageBytes) > limits.conversationMessageBytes
            }.keys
            if (!globalCountExceeded && !globalBytesExceeded && peerCountExceeded.isEmpty() &&
                peerBytesExceeded.isEmpty() && conversationCountExceeded.isEmpty() &&
                conversationBytesExceeded.isEmpty()) break

            val candidate = kept.withIndex()
                .filter { (_, message) -> message.incoming || message.delivered || message.failed }
                .filter { (_, message) ->
                    globalCountExceeded || globalBytesExceeded || message.peer in peerCountExceeded ||
                        message.peer in peerBytesExceeded || conversationKey(message) in conversationCountExceeded ||
                        conversationKey(message) in conversationBytesExceeded
                }
                .minWithOrNull(compareBy<IndexedValue<HopBearer.Message>> { it.value.sentAt }
                    .thenBy { it.value.localId }) ?: break
            kept.removeAt(candidate.index)
        }
        return kept
    }

    fun acceptsMedia(
        messages: List<HopBearer.Message>,
        peer: String,
        attachments: List<ByteArray>,
        knownIdentity: Boolean,
        limits: RetentionLimits = defaults,
    ): Boolean {
        if (!knownIdentity || attachments.any { it.size.toLong() > limits.attachmentBytes }) return false
        val added = attachments.sumOf { it.size.toLong() }
        val global = messages.sumOf(::mediaBytes)
        val perPeer = messages.asSequence().filter { it.peer == peer }.sumOf(::mediaBytes)
        return added <= limits.globalMediaBytes - global && added <= limits.peerMediaBytes - perPeer
    }

    fun messageBytes(message: HopBearer.Message): Long =
        message.text.toByteArray(Charsets.UTF_8).size.toLong() +
            message.peer.toByteArray(Charsets.UTF_8).size +
            message.contentType.toByteArray(Charsets.UTF_8).size +
            message.trace.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } + mediaBytes(message) +
            (message.bundleId?.size ?: 0) + (message.inboxId?.size ?: 0)

    fun mediaBytes(message: HopBearer.Message): Long =
        (message.imageData?.size?.toLong() ?: 0L) + message.images.sumOf { it.size.toLong() }

    fun canAddContact(currentCount: Int, alreadyKnown: Boolean, limits: RetentionLimits = defaults): Boolean =
        alreadyKnown || currentCount < limits.contacts

    private fun conversationKey(message: HopBearer.Message): String = message.peer
}
