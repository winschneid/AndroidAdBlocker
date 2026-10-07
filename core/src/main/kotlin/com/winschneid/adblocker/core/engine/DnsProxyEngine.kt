package com.winschneid.adblocker.core.engine

import com.winschneid.adblocker.core.dns.BlockResponseMode
import com.winschneid.adblocker.core.dns.DnsCodec
import com.winschneid.adblocker.core.dns.DnsQuery
import com.winschneid.adblocker.core.filter.Decision
import com.winschneid.adblocker.core.filter.DomainMatcher
import com.winschneid.adblocker.core.net.IpPacketCodec
import com.winschneid.adblocker.core.net.UdpDatagram

/** What is needed to turn an upstream answer back into a packet for the original client. */
class ReplyContext(val datagram: UdpDatagram, val query: DnsQuery, val decision: Decision) {
    /** The transaction id the client used; the forwarded copy of the query carries a different one. */
    val originalId: Int get() = query.id
}

/**
 * The packet that answers a forwarded query. [blockedAlias] is null when the upstream answer is passed on
 * unchanged; otherwise the answer was replaced by a block response because it pointed to that blocked name.
 */
class UpstreamReply(val packet: ByteArray, val blockedAlias: String?)

/** The engine's decision for one packet read from the TUN interface. */
sealed class Verdict {
    /**
     * The query was answered locally with a block response; write [packet] back to the TUN device.
     * [blockedAlias] is set for [Decision.BLOCKED_BY_CNAME]: the blocked name that [host] is an alias of.
     */
    class Blocked(
        val packet: ByteArray,
        val host: String,
        val type: Int,
        val decision: Decision,
        val blockedAlias: String? = null,
    ) : Verdict()

    /** Forward [payload] (a DNS message) upstream; use [context] to build the reply later. */
    class Forward(val payload: ByteArray, val context: ReplyContext, val host: String, val type: Int, val decision: Decision) : Verdict()

    /** A non-DNS reply (currently: TCP RST) to write back without logging. */
    class Reply(val packet: ByteArray) : Verdict()

    /** Nothing to do. */
    class Drop(val reason: String) : Verdict()
}

/**
 * Platform-independent core of the DNS filtering VPN: inspects raw IP packets from the TUN interface,
 * answers blocked queries itself and prepares everything else for forwarding.
 */
class DnsProxyEngine(
    matcher: DomainMatcher,
    @Volatile var blockMode: BlockResponseMode = BlockResponseMode.NXDOMAIN,
    /**
     * Whether an answer that is an alias (CNAME) of a blocked name is blocked as well. Off by default: sites
     * that detect ad blocking replace their content with a notice when this catches their loader.
     */
    @Volatile var blockAliases: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The active rules together with a counter that changes whenever they do. */
    private class Rules(val matcher: DomainMatcher, val generation: Int)

    @Volatile
    private var rules = Rules(matcher, 0)

    /** The active rules. Replacing them also invalidates every alias verdict derived from the old ones. */
    var matcher: DomainMatcher
        get() = rules.matcher
        @Synchronized set(value) {
            rules = Rules(value, rules.generation + 1)
        }

    private val cloakedAliases = CloakedAliasCache()

    fun process(packet: ByteArray, length: Int): Verdict {
        val version = IpPacketCodec.ipVersion(packet, length)
        if (version != 4 && version != 6) return Verdict.Drop("not an IP packet")

        val datagram = IpPacketCodec.parseUdp(packet, length)
        if (datagram == null) {
            val reset = IpPacketCodec.buildTcpReset(packet, length)
            return if (reset != null) Verdict.Reply(reset) else Verdict.Drop("not UDP")
        }
        if (datagram.destinationPort != DNS_PORT) return Verdict.Drop("not DNS")

        val query = DnsCodec.parseQuery(datagram.payload) ?: return Verdict.Drop("not a DNS query")
        val host = query.question.name
        val current = rules
        val decision = current.matcher.check(host)
        if (decision.blocked) {
            val response = DnsCodec.buildBlockedResponse(query, blockMode)
            return Verdict.Blocked(datagram.reply(response), host, query.question.type, decision)
        }
        if (blockAliases && decision == Decision.ALLOWED) {
            // A recent answer for this name pointed to a blocked name: block its retries without asking again.
            val target = cloakedAliases.get(host, current.generation, clock())
            if (target != null) {
                val response = DnsCodec.buildBlockedResponse(query, blockMode)
                return Verdict.Blocked(datagram.reply(response), host, query.question.type, Decision.BLOCKED_BY_CNAME, target)
            }
        }
        return Verdict.Forward(datagram.payload, ReplyContext(datagram, query, decision), host, query.question.type, decision)
    }

    /** Rewrites the transaction id of a forwarded query in place. */
    fun prepareUpstreamQuery(verdict: Verdict.Forward, upstreamId: Int): ByteArray {
        DnsCodec.setTransactionId(verdict.payload, upstreamId)
        return verdict.payload
    }

    /**
     * Turns an upstream [response] (first [length] bytes) into the packet for the original client.
     *
     * Ad and tracking services hide behind aliases that no blocklist knows: the name a page asks for is new,
     * but its answer is a CNAME to a host that is listed. With [blockAliases] on, such an answer is replaced
     * by a block response. Names the user explicitly allowed are never second-guessed, and the target is
     * judged with the usual precedence, so allowing either the alias or its target lets the answer through.
     */
    fun handleUpstreamResponse(context: ReplyContext, response: ByteArray, length: Int): UpstreamReply {
        if (blockAliases && context.decision == Decision.ALLOWED) {
            val current = rules
            val blockedTarget = DnsCodec.cnameTargets(response, length).firstOrNull { current.matcher.check(it).blocked }
            if (blockedTarget != null) {
                cloakedAliases.put(context.query.question.name, blockedTarget, current.generation, clock())
                val blocked = DnsCodec.buildBlockedResponse(context.query, blockMode)
                return UpstreamReply(context.datagram.reply(blocked), blockedTarget)
            }
        }
        return UpstreamReply(buildReply(context, response, length), null)
    }

    /** Builds the TUN packet that delivers an upstream [response] (first [length] bytes) unchanged. */
    fun buildReply(context: ReplyContext, response: ByteArray, length: Int): ByteArray {
        val payload = response.copyOf(length)
        DnsCodec.setTransactionId(payload, context.originalId)
        return context.datagram.reply(payload)
    }

    companion object {
        const val DNS_PORT = 53
    }
}

/**
 * Remembers names whose answers were aliases of blocked names. Pages that load such an alias tend to retry it
 * several times per second; answering from here keeps those retries from going upstream every time.
 * A verdict is only valid for the rule generation it was derived from. Thread-safe.
 */
internal class CloakedAliasCache(
    private val ttlMillis: Long = 5 * 60 * 1000L,
    private val maxEntries: Int = 512,
) {
    private class Entry(val target: String, val generation: Int, val expiresAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
    }

    /** Returns the blocked name [name] is an alias of, or null if nothing (still) valid is known. */
    @Synchronized
    fun get(name: String, generation: Int, now: Long): String? {
        val entry = entries[name] ?: return null
        if (entry.generation != generation || now >= entry.expiresAt) {
            entries.remove(name)
            return null
        }
        return entry.target
    }

    @Synchronized
    fun put(name: String, target: String, generation: Int, now: Long) {
        entries[name] = Entry(target, generation, now + ttlMillis)
    }
}
