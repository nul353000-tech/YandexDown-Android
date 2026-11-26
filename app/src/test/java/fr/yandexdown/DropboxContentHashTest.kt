package fr.yandexdown

import org.junit.Assert.assertEquals
import org.junit.Test

class DropboxContentHashTest {
    @Test
    fun matchesKnownHashForHelloWithIncrementalUpdates() {
        val hasher = DropboxContentHash()
        val bytes = "hello".toByteArray()
        hasher.update(bytes, 0, 2)
        hasher.update(bytes, 2, 3)

        assertEquals(
            "9595c9df90075148eb06860365df33584b75bff782a510c6cd4883a419833d50",
            hasher.finish()
        )
    }

    @Test
    fun matchesEmptyFileHash() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            DropboxContentHash().finish()
        )
    }
}
