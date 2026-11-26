package fr.yandexdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferIntegrityTest {
    @Test
    fun acceptsMatchingSizeAndMd5() {
        assertTrue(isTransferIntegrityValid(1024L, 1024L, "d41d8cd98f00b204e9800998ecf8427e", "d41d8cd98f00b204e9800998ecf8427e"))
    }

    @Test
    fun rejectsDifferentSize() {
        assertFalse(isTransferIntegrityValid(1023L, 1024L, "", ""))
    }

    @Test
    fun rejectsDifferentMd5() {
        assertFalse(isTransferIntegrityValid(1024L, 1024L, "expected", "actual"))
    }

    @Test
    fun allowsMissingRemoteMd5WhenSizeMatches() {
        assertTrue(isTransferIntegrityValid(1024L, 1024L, "", ""))
    }
}
