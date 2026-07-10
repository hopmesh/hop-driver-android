package sh.hopme.driver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quality-net-03: the address-book on-disk JSON codec (contacts.json).
 *
 * The driver keys every conversation on a peer's base58 ADDRESS, and persists the book so past
 * conversations stay reachable offline / after a restart. These pin that a contact's address, name,
 * platform, and app survive a save->load round-trip through the exact JSON schema the driver writes.
 * (base58<->bytes is core-owned and needs the native lib; here we feed base58-shaped addresses so the
 * codec's contract is fully exercised without the FFI.)
 */
class ContactBookTest {

    // A realistic base58-shaped 32-byte address (the app's addressBase58 output alphabet).
    private val addrA = "H1pMeshAddr11111111111111111111111111111AbC"
    private val addrB = "9xQrStUvWxYz2233445566778899AaBbCcDdEeFfGgH"

    @Test fun singleContactRoundTrips() {
        val orig = listOf(ContactRecord(addrA, "Pixel 7", platform = "android", app = "Hop Debug"))
        val decoded = ContactBook.decode(ContactBook.encode(orig))
        assertEquals(orig, decoded)
    }

    @Test fun manyContactsRoundTripInOrder() {
        val orig = listOf(
            ContactRecord(addrA, "Pixel 7", "android", "Hop Debug"),
            ContactRecord(addrB, "Test iPhone (XR)", "ios", "HopDemo"),
            ContactRecord("ZzTopAddr999999999999999999999999999999999", ""),  // no name/platform/app
        )
        val decoded = ContactBook.decode(ContactBook.encode(orig))
        assertEquals(orig.size, decoded.size)
        assertEquals(orig, decoded)
    }

    @Test fun emptyBookRoundTrips() {
        assertEquals(emptyList<ContactRecord>(), ContactBook.decode(ContactBook.encode(emptyList())))
    }

    @Test fun addressIsTheStableKeyNeverTheName() {
        // Two peers sharing a display name must NOT collapse: they keep distinct address keys.
        val orig = listOf(
            ContactRecord(addrA, "Pixel 7", "android", ""),
            ContactRecord(addrB, "Pixel 7", "android", ""),
        )
        val decoded = ContactBook.decode(ContactBook.encode(orig))
        assertEquals(2, decoded.size)
        assertEquals(setOf(addrA, addrB), decoded.map { it.addr58 }.toSet())
    }

    @Test fun namePlatformAppPreservedExactly() {
        val orig = ContactRecord(addrA, "Jillian's iPad", "ios", "HopDemo")
        val decoded = ContactBook.decode(ContactBook.encode(listOf(orig))).single()
        assertEquals("Jillian's iPad", decoded.name)
        assertEquals("ios", decoded.platform)
        assertEquals("HopDemo", decoded.app)
        assertEquals(addrA, decoded.addr58)
    }

    @Test fun unicodeAndSpecialCharsInNameSurvive() {
        val orig = ContactRecord(addrA, "Decklan’s iPad \"quote\" \\slash/ éà", "ios", "app")
        val decoded = ContactBook.decode(ContactBook.encode(listOf(orig))).single()
        assertEquals(orig.name, decoded.name)   // JSON escaping must not corrupt the display name
    }

    @Test fun malformedJsonYieldsEmptyNotCrash() {
        assertEquals(emptyList<ContactRecord>(), ContactBook.decode("not json at all"))
        assertEquals(emptyList<ContactRecord>(), ContactBook.decode(""))
        assertEquals(emptyList<ContactRecord>(), ContactBook.decode("{\"not\":\"an array\"}"))
    }

    @Test fun entriesWithBlankAddressAreDropped() {
        // A record with no addr is unusable as a conversation key, so it must not load.
        val json = "[{\"addr\":\"\",\"name\":\"ghost\"},{\"addr\":\"$addrA\",\"name\":\"real\"}]"
        val decoded = ContactBook.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(addrA, decoded.single().addr58)
    }

    @Test fun missingOptionalFieldsDefaultCleanly() {
        // Only addr present: name falls back to the address, platform/app to empty.
        val decoded = ContactBook.decode("[{\"addr\":\"$addrA\"}]").single()
        assertEquals(addrA, decoded.addr58)
        assertEquals(addrA, decoded.name)   // address is the honest fallback key
        assertTrue(decoded.platform.isEmpty())
        assertTrue(decoded.app.isEmpty())
    }

    @Test fun encodedShapeMatchesDriverSchema() {
        // Guard the exact field names the shipped app reads (addr/name/platform/app), so a rename can't
        // silently break already-persisted contacts.json on upgrade.
        val json = ContactBook.encode(listOf(ContactRecord(addrA, "n", "p", "a")))
        assertTrue(json.contains("\"addr\""))
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"platform\""))
        assertTrue(json.contains("\"app\""))
    }
}
