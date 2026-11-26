package fr.yandexdown

import java.net.InetAddress
import java.net.URI

/**
 * Rejects unsafe URL shapes before a link resolver makes a network request.
 * DNS answers are checked separately by the resolver's OkHttp Dns implementation.
 */
internal fun isSafeHttpsTarget(input: String): Boolean {
    val uri = runCatching { URI(input) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false

    val host = uri.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")
        ?.takeIf { it.isNotBlank() } ?: return false
    if (!host.contains('.') && !host.contains(':')) return false

    val localSuffixes = listOf(
        ".localhost", ".local", ".internal", ".test", ".example", ".invalid", ".onion"
    )
    if (host == "localhost" || localSuffixes.any(host::endsWith)) return false

    val isNumericAddress = host.contains(':') || host.all { it.isDigit() || it == '.' }
    if (isNumericAddress) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (!isPublicInternetAddress(address)) return false
    }
    return true
}

internal fun isPublicInternetAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress ||
        address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress
    ) return false

    val bytes = address.address
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false
        if (a == 100 && b in 64..127) return false
        if (a == 169 && b == 254) return false
        if (a == 172 && b in 16..31) return false
        if (a == 192 && b == 168) return false
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false
        if (a == 192 && b == 88 && c == 99) return false
        if (a == 198 && (b in 18..19 || (b == 51 && c == 100))) return false
        if (a == 203 && b == 0 && c == 113) return false
        return true
    }

    if (bytes.size == 16) {
        val first = bytes[0].toInt() and 0xff
        if (first !in 0x20..0x3f) return false
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        if (first == 0x20 && second == 0x01 &&
            ((third == 0x0d && fourth == 0xb8) ||
                (third == 0x00 && (fourth == 0x02 || fourth == 0x10)))
        ) return false
        return true
    }

    return false
}
