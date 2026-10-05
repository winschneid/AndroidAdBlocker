package com.winschneid.adblocker.core.filter

/** Result of looking up a host name. */
enum class Decision(val blocked: Boolean) {
    ALLOWED(false),
    ALLOWED_BY_USER(false),
    BLOCKED(true),
    BLOCKED_BY_USER(true),

    /** The name itself is not listed, but its answer was a CNAME chain leading to a blocked name. */
    BLOCKED_BY_CNAME(true),
}

/**
 * Immutable snapshot of the active rules.
 *
 * A host matches an entry when it equals the entry or is a subdomain of it, so `example.com` also covers
 * `ads.example.com`. Precedence: user allowlist > user denylist > blocklists.
 */
class DomainMatcher(
    private val blocked: Set<String>,
    private val userAllow: Set<String> = emptySet(),
    private val userDeny: Set<String> = emptySet(),
) {
    val blockedCount: Int get() = blocked.size
    val userAllowCount: Int get() = userAllow.size
    val userDenyCount: Int get() = userDeny.size

    fun check(host: String): Decision {
        val normalized = HostsParser.normalizeDomain(host) ?: return Decision.ALLOWED
        if (userAllow.isNotEmpty() && findMatch(userAllow, normalized) != null) return Decision.ALLOWED_BY_USER
        if (userDeny.isNotEmpty() && findMatch(userDeny, normalized) != null) return Decision.BLOCKED_BY_USER
        if (findMatch(blocked, normalized) != null) return Decision.BLOCKED
        return Decision.ALLOWED
    }

    /** Returns the blocklist entry that matches [host] (the host itself or one of its parent domains), if any. */
    fun matchingBlockEntry(host: String): String? {
        val normalized = HostsParser.normalizeDomain(host) ?: return null
        return findMatch(blocked, normalized)
    }

    companion object {
        val EMPTY = DomainMatcher(emptySet())

        /** Walks from [host] up through its parent domains; single-label names (TLDs) never match. */
        fun findMatch(entries: Set<String>, host: String): String? {
            var candidate = host
            while (true) {
                if (entries.contains(candidate)) return candidate
                val dot = candidate.indexOf('.')
                if (dot < 0) return null
                candidate = candidate.substring(dot + 1)
                if (candidate.indexOf('.') < 0) return null
            }
        }
    }
}
