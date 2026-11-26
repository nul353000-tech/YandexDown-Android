package fr.yandexdown

import java.net.URI
import java.net.URLDecoder

private val embeddedTargetParameterNames = listOf(
    "url", "target", "dest", "destination", "redirect", "redirect_url", "redirecturl", "link", "to", "u", "r"
)

internal fun extractEmbeddedHttpsTarget(input: String): String? {
    val wrapper = runCatching { URI(input) }.getOrNull() ?: return null
    if (!wrapper.scheme.equals("https", ignoreCase = true) || wrapper.host.isNullOrBlank()) return null
    val rawQuery = wrapper.rawQuery ?: return null
    val parameters = rawQuery.split('&').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val key = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull()
            ?.lowercase() ?: return@mapNotNull null
        val value = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull()
            ?: return@mapNotNull null
        key to value
    }

    for (name in embeddedTargetParameterNames) {
        val candidate = parameters.firstOrNull { it.first == name }?.second ?: continue
        val target = runCatching { URI(candidate) }.getOrNull() ?: continue
        val host = target.host?.lowercase().orEmpty()
        if (target.scheme.equals("https", ignoreCase = true) &&
            host.isNotBlank() &&
            target.userInfo == null &&
            (target.port == -1 || target.port == 443) &&
            host != wrapper.host.lowercase()
        ) {
            return target.toASCIIString()
        }
    }
    return null
}
