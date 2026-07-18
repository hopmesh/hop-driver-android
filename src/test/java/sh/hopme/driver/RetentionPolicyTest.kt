package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetentionPolicyTest {
    private fun message(
        id: Long,
        peer: String = "peer",
        text: String = "x",
        incoming: Boolean = true,
        delivered: Boolean = false,
        failed: Boolean = false,
        media: Int = 0,
    ) = HopBearer.Message(
        localId = id,
        peer = peer,
        text = text,
        incoming = incoming,
        delivered = delivered,
        failed = failed,
        imageData = media.takeIf { it > 0 }?.let { ByteArray(it) },
        sentAt = id,
    )

    private val roomy = RetentionLimits(
        globalMessages = 100,
        globalMessageBytes = 10_000,
        peerMessages = 100,
        peerMessageBytes = 10_000,
        conversationMessages = 2,
        conversationMessageBytes = 10_000,
        attachmentBytes = 10,
        peerMediaBytes = 10,
        globalMediaBytes = 20,
    )

    @Test fun quotaBoundaryKeepsExactLimitAndEvictsOldestOverLimit() {
        val boundary = listOf(message(1), message(2))
        assertEquals(listOf(1L, 2L), RetentionPolicy.retain(boundary, roomy).map { it.localId })
        assertEquals(listOf(2L, 3L), RetentionPolicy.retain(boundary + message(3), roomy).map { it.localId })
    }

    @Test fun peerAndGlobalByteQuotasEvictDeterministically() {
        val limits = roomy.copy(
            conversationMessages = 100,
            peerMessageBytes = 20,
            globalMessageBytes = 30,
        )
        val kept = RetentionPolicy.retain(
            listOf(message(1, peer = "a", text = "1234"), message(2, peer = "a", text = "12"),
                message(3, peer = "b", text = "1234")),
            limits,
        )
        assertEquals(listOf(2L, 3L), kept.map { it.localId })
    }

    @Test fun pendingOutgoingMessagesAreNeverEvictedAutomatically() {
        val pending = listOf(
            message(1, incoming = false), message(2, incoming = false), message(3, incoming = false),
        )
        assertEquals(pending.map { it.localId }, RetentionPolicy.retain(pending, roomy.copy(globalMessages = 1)).map { it.localId })
    }

    @Test fun mediaAdmissionRequiresKnownIdentityAndHonorsEveryByteBoundary() {
        val existing = listOf(message(1, peer = "a", media = 6), message(2, peer = "b", media = 4))
        assertFalse(RetentionPolicy.acceptsMedia(existing, "a", listOf(ByteArray(1)), false, roomy))
        assertFalse(RetentionPolicy.acceptsMedia(existing, "a", listOf(ByteArray(11)), true, roomy))
        assertTrue(RetentionPolicy.acceptsMedia(existing, "a", listOf(ByteArray(4)), true, roomy))
        assertFalse(RetentionPolicy.acceptsMedia(existing, "a", listOf(ByteArray(5)), true, roomy))
        assertTrue(RetentionPolicy.acceptsMedia(existing, "b", listOf(ByteArray(6)), true, roomy))
        assertFalse(RetentionPolicy.acceptsMedia(existing, "b", listOf(ByteArray(7)), true, roomy))
        assertTrue(RetentionPolicy.acceptsMedia(existing, "c", listOf(ByteArray(10)), true, roomy))
        assertFalse(RetentionPolicy.acceptsMedia(existing, "c", listOf(ByteArray(10), ByteArray(1)), true, roomy))
    }

    @Test fun contactLimitAllowsExistingButRejectsTheNextSybil() {
        val limits = roomy.copy(contacts = 2)
        assertTrue(RetentionPolicy.canAddContact(2, alreadyKnown = true, limits))
        assertFalse(RetentionPolicy.canAddContact(2, alreadyKnown = false, limits))
    }

    @Test fun manySybilIdentitiesCannotGrowTheContactBookPastTheGlobalLimit() {
        var contacts = 0
        repeat(10_000) {
            if (RetentionPolicy.canAddContact(contacts, alreadyKnown = false)) contacts += 1
        }
        assertEquals(RetentionPolicy.defaults.contacts, contacts)
    }

    @Test fun pendingCapPlusOneIsRejectedWithoutEvictionAndReleaseRestoresCapacity() {
        val limits = roomy.copy(
            pendingGlobalMessages = 2,
            pendingGlobalBytes = 100,
            pendingPeerMessages = 2,
            pendingPeerBytes = 100,
            pendingConversationMessages = 2,
            pendingConversationBytes = 100,
        )
        val quota = PendingQuota(limits)
        assertEquals(PendingAdmission.ACCEPTED, quota.reserve(1, "a", "c", 10))
        assertEquals(PendingAdmission.ACCEPTED, quota.reserve(2, "a", "c", 10))
        assertEquals(PendingAdmission.GLOBAL_COUNT, quota.reserve(3, "b", "d", 10))
        assertEquals(2, quota.count())
        quota.release(1)
        assertEquals(PendingAdmission.ACCEPTED, quota.reserve(3, "b", "d", 10))
        assertEquals(2, quota.count())
    }

    @Test fun pendingPeerConversationAndByteCapsAreIndependent() {
        val base = roomy.copy(
            pendingGlobalMessages = 20,
            pendingGlobalBytes = 1_000,
            pendingPeerMessages = 1,
            pendingPeerBytes = 1_000,
            pendingConversationMessages = 20,
            pendingConversationBytes = 1_000,
        )
        val peer = PendingQuota(base)
        assertEquals(PendingAdmission.ACCEPTED, peer.reserve(1, "a", "one", 10))
        assertEquals(PendingAdmission.PEER_COUNT, peer.reserve(2, "a", "two", 10))

        val conversation = PendingQuota(base.copy(pendingPeerMessages = 20, pendingConversationMessages = 1))
        assertEquals(PendingAdmission.ACCEPTED, conversation.reserve(1, "a", "same", 10))
        assertEquals(PendingAdmission.CONVERSATION_COUNT, conversation.reserve(2, "b", "same", 10))

        val bytes = PendingQuota(base.copy(pendingPeerMessages = 20, pendingPeerBytes = 15))
        assertEquals(PendingAdmission.ACCEPTED, bytes.reserve(1, "a", "one", 10))
        assertEquals(PendingAdmission.PEER_BYTES, bytes.reserve(2, "a", "two", 6))

        val globalBytes = PendingQuota(base.copy(pendingPeerMessages = 20, pendingGlobalBytes = 15))
        assertEquals(PendingAdmission.ACCEPTED, globalBytes.reserve(1, "a", "one", 10))
        assertEquals(PendingAdmission.GLOBAL_BYTES, globalBytes.reserve(2, "b", "two", 6))

        val conversationBytes = PendingQuota(base.copy(
            pendingPeerMessages = 20,
            pendingConversationBytes = 15,
        ))
        assertEquals(PendingAdmission.ACCEPTED, conversationBytes.reserve(1, "a", "same", 10))
        assertEquals(PendingAdmission.CONVERSATION_BYTES, conversationBytes.reserve(2, "b", "same", 6))
    }

    @Test fun pendingReconciliationKeepsOnlyUnresolvedOutgoingMessages() {
        val pending = message(1, incoming = false)
        val quota = PendingQuota(roomy)
        quota.reconcile(listOf(
            pending,
            message(2, incoming = true),
            message(3, incoming = false, delivered = true),
            message(4, incoming = false, failed = true),
        ))
        assertEquals(1, quota.count())
        quota.release(pending.localId)
        assertEquals(0, quota.count())
    }
}
