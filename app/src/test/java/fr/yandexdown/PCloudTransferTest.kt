package fr.yandexdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PCloudTransferTest {
    @Test
    fun acceptsOnlyOfficialPCloudApiHosts() {
        assertTrue(isSupportedPCloudApiHost("api.pcloud.com"))
        assertTrue(isSupportedPCloudApiHost("eapi.pcloud.com"))
        assertFalse(isSupportedPCloudApiHost("example.com"))
    }

    @Test
    fun verifiesReturnedSha1Checksum() {
        val remote = mapOf("sha1" to "abc123")
        assertEquals(true, pCloudChecksumMatches(remote, "", "abc123", ""))
        assertEquals(false, pCloudChecksumMatches(remote, "", "different", ""))
    }

    @Test
    fun reportsUnavailableChecksumWithoutClaimingVerification() {
        assertEquals(null, pCloudChecksumMatches(emptyMap(), "", "", ""))
    }
}
