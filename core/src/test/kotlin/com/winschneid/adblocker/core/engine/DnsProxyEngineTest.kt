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
    private val aliasEngine = DnsProxyEngine(DomainMatcher(setOf("ads.example.com")), blockAliases = true)

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

    /** The upstream answer for a forwarded query: `<question> CNAME [target]`, then `[target] A 192.0.2.1`. */
    private fun cnameAnswer(forwardedQuery: ByteArray, target: String): ByteArray {
        val targetName = DnsCodec.encodeQuery(0, target, DnsType.A).let { it.copyOfRange(DnsCodec.HEADER_LENGTH, it.size - 4) }
        val out = java.io.ByteArrayOutputStream()
        out.write(forwardedQuery)
        out.write(byteArrayOf(0xC0.toByte(), 12, 0, DnsType.CNAME.toByte(), 0, 1, 0, 0, 0, 60, 0, targetName.size.toByte()))
        out.write(targetName)
        out.write(byteArrayOf(0xC0.toByte(), (forwardedQuery.size + 12).toByte(), 0, DnsType.A.toByte(), 0, 1, 0, 0, 0, 60, 0, 4))
        out.write(byteArrayOf(192.toByte(), 0, 2, 1))
        val response = out.toByteArray()
        response[2] = (response[2].toInt() or 0x80).toByte()
        put16(response, 6, 2)
        return response
    }

    private fun forward(engine: DnsProxyEngine, host: String): Pair<Verdict.Forward, ByteArray> {
        val packet = dnsPacket(host, id = 0x4242)
        val verdict = engine.process(packet, packet.size) as Verdict.Forward
        return verdict to engine.prepareUpstreamQuery(verdict, 0x0101)
    }

    @Test
    fun answerThatIsAnAliasOfABlockedNameIsBlocked() {
        val (verdict, upstream) = forward(aliasEngine, "alias.publisher.example")
        assertEquals(Decision.ALLOWED, verdict.decision)

        val response = cnameAnswer(upstream, "x.ads.example.com")
        val reply = aliasEngine.handleUpstreamResponse(verdict.context, response, response.size)
        assertEquals("x.ads.example.com", reply.blockedAlias)
        assertTrue(IpPacketCodec.verifyChecksums(reply.packet))
        val payload = IpPacketCodec.parseUdp(reply.packet, reply.packet.size)!!.payload
        assertEquals(0x4242, DnsCodec.transactionId(payload)) // the client's id, not the upstream one
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.responseCode(payload))
        assertEquals(0, u16(payload, 6)) // none of the upstream records leak through
        assertEquals("alias.publisher.example", DnsCodec.readName(payload, DnsCodec.HEADER_LENGTH, payload.size)!!.first)
    }

    @Test
    fun aliasOfAnUnlistedNameIsDelivered() {
        val (verdict, upstream) = forward(aliasEngine, "www.publisher.example")
        val response = cnameAnswer(upstream, "cdn.publisher.example")
        val reply = aliasEngine.handleUpstreamResponse(verdict.context, response, response.size)
        assertEquals(null, reply.blockedAlias)
        val payload = IpPacketCodec.parseUdp(reply.packet, reply.packet.size)!!.payload
        assertEquals(0x4242, DnsCodec.transactionId(payload))
        assertEquals(2, u16(payload, 6))
        assertEquals(response.size, payload.size)
    }

    @Test
    fun userAllowRulesOverrideCnameBlocking() {
        // Allowing the alias itself: its answer is never inspected.
        val aliasAllowed = DnsProxyEngine(DomainMatcher(setOf("ads.example.com"), userAllow = setOf("alias.publisher.example")), blockAliases = true)
        val (first, firstUpstream) = forward(aliasAllowed, "alias.publisher.example")
        assertEquals(Decision.ALLOWED_BY_USER, first.decision)
        val firstResponse = cnameAnswer(firstUpstream, "x.ads.example.com")
        assertEquals(null, aliasAllowed.handleUpstreamResponse(first.context, firstResponse, firstResponse.size).blockedAlias)

        // Allowing the target: the alias resolves again as well.
        val targetAllowed = DnsProxyEngine(DomainMatcher(setOf("ads.example.com"), userAllow = setOf("x.ads.example.com")), blockAliases = true)
        val (second, secondUpstream) = forward(targetAllowed, "alias.publisher.example")
        val secondResponse = cnameAnswer(secondUpstream, "x.ads.example.com")
        assertEquals(null, targetAllowed.handleUpstreamResponse(second.context, secondResponse, secondResponse.size).blockedAlias)
    }

    @Test
    fun knownAliasIsBlockedWithoutAskingUpstreamAgain() {
        var now = 1_000L
        val engine = DnsProxyEngine(DomainMatcher(setOf("ads.example.com")), blockAliases = true, clock = { now })
        val (verdict, upstream) = forward(engine, "alias.publisher.example")
        val response = cnameAnswer(upstream, "x.ads.example.com")
        assertEquals("x.ads.example.com", engine.handleUpstreamResponse(verdict.context, response, response.size).blockedAlias)

        // The retry (any record type) is answered locally and reported as an alias block.
        val retry = dnsPacket("alias.publisher.example", type = DnsType.HTTPS, id = 0x0707)
        val blocked = engine.process(retry, retry.size) as Verdict.Blocked
        assertEquals(Decision.BLOCKED_BY_CNAME, blocked.decision)
        assertEquals("x.ads.example.com", blocked.blockedAlias)
        val payload = IpPacketCodec.parseUdp(blocked.packet, blocked.packet.size)!!.payload
        assertEquals(0x0707, DnsCodec.transactionId(payload))
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.responseCode(payload))

        // Other names are unaffected, and the verdict expires so that a changed alias is noticed.
        val other = dnsPacket("www.publisher.example")
        assertTrue(engine.process(other, other.size) is Verdict.Forward)
        now += 5 * 60 * 1000L
        assertTrue(engine.process(retry, retry.size) is Verdict.Forward)
    }

    @Test
    fun changingTheRulesForgetsAliasVerdicts() {
        val engine = DnsProxyEngine(DomainMatcher(setOf("ads.example.com")), blockAliases = true)
        val (verdict, upstream) = forward(engine, "alias.publisher.example")
        val response = cnameAnswer(upstream, "x.ads.example.com")
        engine.handleUpstreamResponse(verdict.context, response, response.size)
        val retry = dnsPacket("alias.publisher.example")
        assertTrue(engine.process(retry, retry.size) is Verdict.Blocked)

        // The user allows the target: the alias must resolve again right away, not after the cache expires.
        engine.matcher = DomainMatcher(setOf("ads.example.com"), userAllow = setOf("x.ads.example.com"))
        val (again, againUpstream) = forward(engine, "alias.publisher.example")
        val againResponse = cnameAnswer(againUpstream, "x.ads.example.com")
        assertEquals(null, engine.handleUpstreamResponse(again.context, againResponse, againResponse.size).blockedAlias)
        assertTrue(engine.process(retry, retry.size) is Verdict.Forward)
    }

    @Test
    fun aliasBlockingIsOptIn() {
        // Off by default: the alias of a blocked name is delivered like any other answer.
        val (verdict, upstream) = forward(engine, "alias.publisher.example")
        val response = cnameAnswer(upstream, "x.ads.example.com")
        assertEquals(null, engine.handleUpstreamResponse(verdict.context, response, response.size).blockedAlias)

        engine.blockAliases = true
        val (second, secondUpstream) = forward(engine, "alias.publisher.example")
        val secondResponse = cnameAnswer(secondUpstream, "x.ads.example.com")
        assertEquals("x.ads.example.com", engine.handleUpstreamResponse(second.context, secondResponse, secondResponse.size).blockedAlias)
        val retry = dnsPacket("alias.publisher.example")
        assertTrue(engine.process(retry, retry.size) is Verdict.Blocked)

        // Turning it off again also ignores what was remembered while it was on.
        engine.blockAliases = false
        assertTrue(engine.process(retry, retry.size) is Verdict.Forward)

        // A name that is listed itself is blocked either way.
        val listed = dnsPacket("x.ads.example.com")
        assertTrue(engine.process(listed, listed.size) is Verdict.Blocked)
    }

    @Test
    fun cnameBlockingFollowsTheBlockMode() {
        aliasEngine.blockMode = BlockResponseMode.ZERO_IP
        val (verdict, upstream) = forward(aliasEngine, "alias.publisher.example")
        val response = cnameAnswer(upstream, "ads.example.com")
        val reply = aliasEngine.handleUpstreamResponse(verdict.context, response, response.size)
        assertEquals("ads.example.com", reply.blockedAlias)
        val payload = IpPacketCodec.parseUdp(reply.packet, reply.packet.size)!!.payload
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.responseCode(payload))
        assertEquals(1, u16(payload, 6))
        assertArrayEquals(ByteArray(4), payload.copyOfRange(payload.size - 4, payload.size))
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
