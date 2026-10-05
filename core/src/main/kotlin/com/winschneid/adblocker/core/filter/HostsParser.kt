package com.winschneid.adblocker.core.filter

import java.io.BufferedReader
import java.net.IDN

/**
 * Parses blocklists in the common formats:
 *  - hosts files (`0.0.0.0 ads.example.com`, `127.0.0.1 a.example b.example`)
 *  - plain domain lists (one domain per line)
 *  - AdBlock-style pure domain rules (`||ads.example.com^`)
 * Comments (`#`, `!`, `[...]` headers) and anything that is not a plain domain rule are ignored.
 */
object HostsParser {
    private val RESERVED_NAMES = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts",
    )
    private val IPV4_LITERAL = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_DOMAIN_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63

    /** Parses every line of [reader], passing each valid domain to [sink]. Returns the number of domains emitted. */
    fun parse(reader: BufferedReader, sink: (String) -> Unit): Int {
        var count = 0
        while (true) {
            val line = reader.readLine() ?: break
            count += parseLine(line, sink)
        }
        return count
    }

    /** Parses [text] (whole file content). Returns the number of domains emitted. */
    fun parse(text: String, sink: (String) -> Unit): Int = text.lineSequence().sumOf { parseLine(it, sink) }

    /** Parses a single line. Returns the number of domains emitted (0 when the line carries no rule). */
    fun parseLine(rawLine: String, sink: (String) -> Unit): Int {
        var line = rawLine
        val hash = line.indexOf('#')
        if (hash >= 0) line = line.substring(0, hash)
        line = line.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) return 0

        if (line.startsWith("@@")) return 0 // AdBlock exception rules are not supported
        if (line.startsWith("||")) {
            var rule = line.substring(2)
            if (!rule.endsWith("^")) return 0
            rule = rule.dropLast(1)
            if (rule.any { it == '/' || it == '*' || it == '$' || it == '|' || it == '^' || it == '@' || it == '?' }) return 0
            return emit(rule, sink)
        }

        val tokens = line.split(WHITESPACE)
        return when {
            tokens.isEmpty() -> 0
            isIpLiteral(tokens[0]) -> {
                var n = 0
                for (i in 1 until tokens.size) n += emit(tokens[i], sink)
                n
            }
            tokens.size == 1 -> emit(tokens[0], sink)
            else -> 0 // dnsmasq / unbound / other syntaxes are not supported
        }
    }

    private fun emit(candidate: String, sink: (String) -> Unit): Int {
        val domain = normalizeDomain(candidate) ?: return 0
        if (domain in RESERVED_NAMES) return 0
        sink(domain)
        return 1
    }

    /**
     * Normalizes a domain: lower-cases, strips a leading `*.` and trailing dot, converts IDN to punycode and
     * validates the label syntax. Returns null if [input] is not a usable domain (including IP literals and
     * single-label names).
     */
    fun normalizeDomain(input: String): String? {
        var s = input.trim().lowercase()
        if (s.startsWith("*.")) s = s.substring(2)
        if (s.endsWith(".")) s = s.dropLast(1)
        if (s.isEmpty()) return null
        if (!s.all { it.code < 128 }) {
            s = try {
                IDN.toASCII(s).lowercase()
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        if (s.length > MAX_DOMAIN_LENGTH) return null
        var labelStart = 0
        for (i in 0..s.length) {
            if (i == s.length || s[i] == '.') {
                val labelLength = i - labelStart
                if (labelLength == 0 || labelLength > MAX_LABEL_LENGTH) return null
                labelStart = i + 1
            } else {
                val c = s[i]
                val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
                if (!ok) return null
            }
        }
        if (s.indexOf('.') < 0) return null
        if (isIpLiteral(s)) return null
        return s
    }

    fun isIpLiteral(token: String): Boolean {
        if (IPV4_LITERAL.matches(token)) return true
        return token.contains(':') // domains never contain ':', so this must be an IPv6 literal
    }
}
