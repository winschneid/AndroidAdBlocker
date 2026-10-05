package com.winschneid.adblocker.core.engine

import com.winschneid.adblocker.core.dns.BlockResponseMode
import com.winschneid.adblocker.core.dns.DnsCodec
import com.winschneid.adblocker.core.filter.Decision
import com.winschneid.adblocker.core.filter.DomainMatcher
import com.winschneid.adblocker.core.net.IpPacketCodec
import com.winschneid.adblocker.core.net.UdpDatagram

/** What is needed to turn an upstream answer back into a packet for the original client. */
class ReplyContext(val datagram: UdpDatagram, val originalId: Int)

/** The engine's decision for one packet read from the TUN interface. */
sealed class Verdict {
    /** The query was answered locally with a block response; write [packet] back to the TUN device. */
    class Blocked(val packet: ByteArray, val host: String, val type: Int, val decision: Decision) : Verdict()

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
    @Volatile var matcher: DomainMatcher,
    @Volatile var blockMode: BlockResponseMode = BlockResponseMode.NXDOMAIN,
) {
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
        val decision = matcher.check(host)
        if (decision.blocked) {
            val response = DnsCodec.buildBlockedResponse(query, blockMode)
            return Verdict.Blocked(datagram.reply(response), host, query.question.type, decision)
        }
        return Verdict.Forward(datagram.payload, ReplyContext(datagram, query.id), host, query.question.type, decision)
    }

    /** Rewrites the transaction id of a forwarded query in place. */
    fun prepareUpstreamQuery(verdict: Verdict.Forward, upstreamId: Int): ByteArray {
        DnsCodec.setTransactionId(verdict.payload, upstreamId)
        return verdict.payload
    }

    /** Builds the TUN packet that delivers an upstream [response] (first [length] bytes) to the original client. */
    fun buildReply(context: ReplyContext, response: ByteArray, length: Int): ByteArray {
        val payload = response.copyOf(length)
        DnsCodec.setTransactionId(payload, context.originalId)
        return context.datagram.reply(payload)
    }

    companion object {
        const val DNS_PORT = 53
    }
}
