package fr.yandexdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneDriveTransferTest {
    @Test
    fun parsesNextExpectedRangeOffset() {
        assertEquals(10_485_760L, parseOneDriveNextExpectedOffset(listOf("10485760-")))
    }

    @Test
    fun rejectsMissingOrInvalidNextExpectedRange() {
        assertNull(parseOneDriveNextExpectedOffset(emptyList()))
        assertNull(parseOneDriveNextExpectedOffset(listOf("invalid")))
    }

    @Test
    fun uploadChunkSizeIsMultipleOfGraphRequiredQuantum() {
        assertEquals(0, ONEDRIVE_UPLOAD_CHUNK_SIZE % (320 * 1024))
    }
}
