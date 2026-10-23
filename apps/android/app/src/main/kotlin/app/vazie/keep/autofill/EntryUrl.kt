package app.vazie.keep.autofill

import java.net.IDN

// A web origin as far as an entry's URL or Autofill says: scheme and IDNA (punycode) host. Android
// reports only a host for a page, so ports can't be compared; an entry saved with a port other
// than the scheme's default is never offered.
data class WebOrigin(val scheme: String, val host: String, val port: Int?) {
    override fun toString() = "WebOrigin(<redacted>)"

    companion object {
        // An entry's URL field. A bare host ("example.com/login") is read as https. Only http and
        // https can be filled; "androidapp://" or "ssh://" entries have no origin.
        fun parseEntryUrl(url: String): WebOrigin? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            val text = if ("://" in trimmed) trimmed else "https://$trimmed"
            val scheme = text.substringBefore("://").lowercase()
            if (scheme != "https" && scheme != "http") return null
            val rest = text.substringAfter("://")
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
            val (rawHost, rawPort) = if (authority.startsWith("[")) {
                authority.substringBefore(']').removePrefix("[") to authority.substringAfter(']', "").removePrefix(":")
            } else {
                authority.substringBefore(':') to authority.substringAfter(':', "")
            }
            val host = asciiHost(rawHost) ?: return null
            val port = if (rawPort.isEmpty()) null else rawPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val default = if (scheme == "https") 443 else 80
            return WebOrigin(scheme, host, port?.takeIf { it != default })
        }

        // What a trusted browser reported for the page. Port is unknown (null).
        fun fromRequest(scheme: String?, domain: String): WebOrigin? {
            val host = asciiHost(domain) ?: return null
            val normalized = scheme?.lowercase()
            return WebOrigin(if (normalized == "http") "http" else "https", host, null)
        }

        // IDN.toASCII turns a Unicode host into punycode, so it can't pose as an ASCII one.
        fun asciiHost(raw: String): String? {
            var host = raw.trim().removeSuffix(".")
            if (host.isEmpty() || host.length > 253) return null
            host = try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return host.lowercase().takeIf { it.isNotEmpty() }
        }
    }
}
