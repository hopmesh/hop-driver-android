package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.hop.InboxMessage
import uniffi.hop.ServiceReq
import uniffi.hop.ServiceResp
import uniffi.hop.serviceIdentify

/** cov/android-driver: drainServices() - service responses (binary + UTF-8), the 501 auto-reply to a
 *  custom request, and queueIdentify firing hop.identify for a new sender. */
class HopBearerServicesTest : DriverTestBase() {

    private fun pump() {
        bearer.send("t", HopBearer.Peer(ByteArray(32) { 1 }, "x", 0u)); settle()
    }

    @Test fun binaryServiceResponseIsByteCounted() {
        // unknown request id + non-UTF8 body → "service <- status: <N bytes>"
        fake.pendingServiceResponses.add(
            ServiceResp(id = ByteArray(32) { 1 }, from = ByteArray(32) { 2 }, forRequestId = ByteArray(16) { 8 },
                status = 200u, body = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00)),
        )
        pump()
        assertTrue(bearer.serviceLog.any { it.contains("bytes>") })
        assertTrue(fake.acceptedServiceResponseIds.any { it.contentEquals(ByteArray(32) { 1 }) })
    }

    @Test fun utf8ServiceResponseShowsText() {
        fake.pendingServiceResponses.add(
            ServiceResp(id = ByteArray(32) { 2 }, from = ByteArray(32) { 2 }, forRequestId = ByteArray(16) { 8 },
                status = 200u, body = "hello-svc".toByteArray()),
        )
        pump()
        assertTrue(bearer.serviceLog.any { it.contains("hello-svc") })
        assertTrue(fake.acceptedServiceResponseIds.any { it.contentEquals(ByteArray(32) { 2 }) })
    }

    @Test fun failedAcceptanceRetriesWithoutDuplicateDispatch() {
        val id = ByteArray(32) { 4 }
        fake.failServiceResponseAcceptance = true
        fake.pendingServiceResponses.add(
            ServiceResp(id = id, from = ByteArray(32) { 2 }, forRequestId = ByteArray(32) { 8 },
                status = 200u, body = "retry-svc".toByteArray()),
        )
        pump()
        assertEquals(1, bearer.serviceLog.count { it.contains("retry-svc") })
        assertTrue(fake.acceptedServiceResponseIds.isEmpty())

        fake.failServiceResponseAcceptance = false
        pump()
        assertEquals(1, bearer.serviceLog.count { it.contains("retry-svc") })
        assertTrue(fake.acceptedServiceResponseIds.any { it.contentEquals(id) })
    }

    @Test fun customServiceRequestGets501() {
        fake.pendingServiceRequests.add(
            ServiceReq(from = ByteArray(32) { 6 }, requestId = ByteArray(16) { 1 },
                service = "app.echo", method = "do", args = ByteArray(0)),
        )
        pump()
        assertTrue("driver auto-replies 501", fake.serviceResponses.contains(501))
        assertTrue(bearer.serviceLog.any { it.contains("(501)") })
    }

    @Test fun newSenderTriggersIdentify() {
        bearer.appInForeground = true
        fake.pendingInbox.add(
            InboxMessage(id = ByteArray(32) { 1 }, from = ByteArray(32) { 7 }, contentType = "text/plain",
                body = "hi".toByteArray(), hops = 1u, createdAt = 1uL, trace = emptyList()),
        )
        pump()
        // queueIdentify sent a hop.identify service request for the new sender
        assertTrue(fake.serviceRequests.any { it.second == serviceIdentify() })
    }

    @Test fun identifyRequestIdMatchIsConsumed() {
        // fire an identify (via an inbound message), then feed a response keyed to that request id.
        bearer.appInForeground = true
        fake.pendingInbox.add(
            InboxMessage(id = ByteArray(32) { 2 }, from = ByteArray(32) { 7 }, contentType = "text/plain",
                body = "hi".toByteArray(), hops = 1u, createdAt = 1uL, trace = emptyList()),
        )
        pump()
        val reqId = fake.lastServiceReqId
        assertTrue(reqId.isNotEmpty())
        // body isn't a real identity encoding, so decodeIdentity returns null -> the lenient text branch,
        // but the identify request id IS recognized + removed (the matched-id path).
        fake.pendingServiceResponses.add(
            ServiceResp(id = ByteArray(32) { 3 }, from = ByteArray(32) { 7 }, forRequestId = reqId, status = 0u, body = "x".toByteArray()),
        )
        pump()
        assertEquals(0, bearer.serviceLog.count { it.startsWith("identify ← ") })
    }
}
