package fr.yandexdown

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeRedirectPolicyTest {
    @Test
    fun acceptsOnlyPublicHttpsUrlShapes() {
        assertTrue(isSafeHttpsTarget("https://files.example.com/download"))
        assertFalse(isSafeHttpsTarget("http://files.example.com/download"))
        assertFalse(isSafeHttpsTarget("https://user@files.example.com/download"))
        assertFalse(isSafeHttpsTarget("https://files.example.com:8443/download"))
        assertFalse(isSafeHttpsTarget("https://localhost/admin"))
        assertFalse(isSafeHttpsTarget("https://printer.local/admin"))
    }

    @Test
    fun rejectsPrivateReservedAndDocumentationIpv4Ranges() {
        val blocked = listOf(
            "0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1",
            "169.254.1.1", "172.16.0.1", "192.168.1.1", "192.0.2.1",
            "198.18.0.1", "203.0.113.1", "224.0.0.1"
        )
        blocked.forEach { ip ->
            assertFalse("$ip must not be considered public", isPublicInternetAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun acceptsPublicIpv4AndIpv6Addresses() {
        assertTrue(isPublicInternetAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicInternetAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }

    @Test
    fun rejectsLoopbackUniqueLocalAndDocumentationIpv6() {
        val blocked = listOf("::1", "fd00::1", "fe80::1", "2001:db8::1")
        blocked.forEach { ip ->
            assertFalse("$ip must not be considered public", isPublicInternetAddress(InetAddress.getByName(ip)))
        }
    }
}
