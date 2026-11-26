package fr.yandexdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkResolverTest {
    @Test
    fun extractsEncodedHttpsDestination() {
        assertEquals(
            "https://files.example.com/download?id=42",
            extractEmbeddedHttpsTarget("https://short.example/?url=https%3A%2F%2Ffiles.example.com%2Fdownload%3Fid%3D42")
        )
    }

    @Test
    fun rejectsInsecureOrRelativeTargets() {
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?url=http%3A%2F%2Ffiles.example%2Ffile"))
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?target=%2Frelative%2Fpath"))
    }

    @Test
    fun rejectsCredentialsAndSameHostLoops() {
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?to=https%3A%2F%2Fuser%40files.example%2Ffile"))
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?redirect=https%3A%2F%2Fshort.example%2Fagain"))
    }
    @Test
    fun rejectsPrivateOrInsecureEmbeddedDestinations() {
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?url=https%3A%2F%2F127.0.0.1%2Fadmin"))
        assertNull(extractEmbeddedHttpsTarget("https://short.example/?url=https%3A%2F%2Ffiles.example.com%3A8443%2Ffile"))
    }

}
