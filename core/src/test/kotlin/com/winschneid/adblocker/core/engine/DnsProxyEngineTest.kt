package com.winschneid.adblocker.core.engine

import com.winschneid.adblocker.core.dns.BlockResponseMode
import com.winschneid.adblocker.core.dns.DnsCodec
import com.winschneid.adblocker.core.dns.DnsType
import com.winschneid.adblocker.core.filter.Decision
import com.winschneid.adblocker.core.filter.DomainMatcher
import com.winschneid.adblocker.core.net.IpPacketCodec
import com.winschneid.adblocker.core.put16
import com.winschneid.adblocker.core.put32
import com.winschneid.adblocker.core.u16
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProxyEngineTest {
    private val client4 = byteArrayOf(10, 111, 222.toByte(), 1)
    private val resolver4 = byteArrayOf(10, 111, 222.toByte(), 2)
    private val client6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
    private val resolver6 = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }

    private val engine = DnsProxyEngine(DomainMatcher(setOf("ads.example.com")))

    private fun dnsPacket(host: String, type: Int = DnsType.A, id: Int = 0x4242, ipVersion: Int = 4, port: Int = 53): ByteArray {
        val (source, destination) = if (ipVersion == 4) client4 to resolver4 else client6 to resolver6
        return IpPacketCodec.buildUdp(ipVersion, source, destination, 50000, port, DnsCodec.encodeQuery(id, host, type))
    }

    @Test
    fun blockedQueryIsAnsweredLocally() {
        val packet = dnsPacket("tracker.ads.example.com")
        val verdict = engine.process(packet, packet.size) as Verdict.Blocked
        assertEquals("tracker.ads.example.com", verdict.host)
        assertEquals(DnsType.A, verdict.type)
        assertEquals(Decision.BLOCKED, verdict.decision)

        assertTrue(IpPacketCodec.verifyChecksums(verdict.packet))
        val reply = IpPacketCodec.parseUdp(verdict.packet, verdict.packet.size)!!
        assertArrayEquals(resolver4, reply.sourceAddress)
        assertArrayEquals(client4, reply.destinationAddress)
        assertEquals(53, reply.sourcePort)
        assertEquals(50000, reply.destinationPort)
        assertEquals(0x4242, DnsCodec.transactionId(reply.payload))
        assertTrue(DnsCodec.isResponse(reply.payload))
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.responseCode(reply.payload))
    }

    @Test
    fun zeroIpModeAnswersWithZeroAddress() {
        engine.blockMode = BlockResponseMode.ZERO_IP
        val packet = dnsPacket("ads.example.com")
        val verdict = engine.process(packet, packet.size) as Verdict.Blocked
        val reply = IpPacketCodec.parseUdp(verdict.packet, verdict.packet.size)!!
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.responseCode(reply.payload))
        assertEquals(1, u16(reply.payload, 6))
    }

    @Test
    fun allowedQueryIsForwardedAndReplyIsRebuilt() {
        val packet = dnsPacket("www.example.com", id = 0x4242)
        val verdict = engine.process(packet, packet.size) as Verdict.Forward
        assertEquals("www.example.com", verdict.host)
        assertEquals(Decision.ALLOWED, verdict.decision)
        assertEquals(0x4242, verdict.context.originalId)
        assertArrayEquals(DnsCodec.encodeQuery(0x4242, "www.example.com", DnsType.A), verdict.payload)

        val upstream = engine.prepareUpstreamQuery(verdict, 0x0101)
        assertEquals(0x0101, DnsCodec.transactionId(upstream))

        // Fake an upstream answer: same message with QR set, one bogus answer appended.
        val response = ByteArray(upstream.size + 16)
        System.arraycopy(upstream, 0, response, 0, upstream.size)
        response[2] = (response[2].toInt() or 0x80).toByte()
        put16(response, 6, 1)
        var p = upstream.size
        response[p++] = 0xC0.toByte(); response[p++] = 12
        put16(response, p, DnsType.A); p += 2
        put16(response, p, DnsCodec.CLASS_IN); p += 2
        put32(response, p, 60L); p += 4
        put16(response, p, 4); p += 2
        response[p++] = 93; response[p++] = 184.toByte(); response[p++] = 216.toByte(); response[p] = 34

        val replyPacket = engine.buildReply(verdict.context, response, response.size)
        assertTrue(IpPacketCodec.verifyChecksums(replyPacket))
        val reply = IpPacketCodec.parseUdp(replyPacket, replyPacket.size)!!
        assertArrayEquals(resolver4, reply.sourceAddress)
        assertArrayEquals(client4, reply.destinationAddress)
        assertEquals(50000, reply.destinationPort)
        assertEquals(0x4242, DnsCodec.transactionId(reply.payload))
        assertTrue(DnsCodec.isResponse(reply.payload))
        assertEquals(response.size, reply.payload.size)
    }

    @Test
    fun ipv6QueriesAreHandled() {
        val packet = dnsPacket("ads.example.com", type = DnsType.AAAA, ipVersion = 6)
        val verdict = engine.process(packet, packet.size) as Verdict.Blocked
        val reply = IpPacketCodec.parseUdp(verdict.packet, verdict.packet.size)!!
        assertEquals(6, reply.ipVersion)
        assertArrayEquals(client6, reply.destinationAddress)
        assertTrue(IpPacketCodec.verifyChecksums(verdict.packet))
    }

    @Test
    fun nonDnsTrafficIsDroppedOrReset() {
        val ntp = dnsPacket("www.example.com", port = 123)
        assertTrue(engine.process(ntp, ntp.size) is Verdict.Drop)

        val garbage = ByteArray(64)
        assertTrue(engine.process(garbage, garbage.size) is Verdict.Drop)

        val notDns = IpPacketCodec.buildUdp(4, client4, resolver4, 50000, 53, "not dns at all".toByteArray())
        assertTrue(engine.process(notDns, notDns.size) is Verdict.Drop)

        val syn = ByteArray(40)
        syn[0] = 0x45
        put16(syn, 2, 40)
        syn[9] = 6
        System.arraycopy(client4, 0, syn, 12, 4)
        System.arraycopy(resolver4, 0, syn, 16, 4)
        put16(syn, 20, 51000)
        put16(syn, 22, 853)
        syn[32] = 0x50
        syn[33] = 0x02
        val reply = engine.process(syn, syn.size) as Verdict.Reply
        assertEquals(0x14, reply.packet[33].toInt() and 0xFF)
    }

    @Test
    fun matcherCanBeSwappedAtRuntime() {
        val packet = dnsPacket("www.example.com")
        assertTrue(engine.process(packet, packet.size) is Verdict.Forward)
        engine.matcher = DomainMatcher(setOf("example.com"))
        assertTrue(engine.process(packet, packet.size) is Verdict.Blocked)
    }
}
