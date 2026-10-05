package com.winschneid.adblocker.core.dns

import com.winschneid.adblocker.core.u16
import com.winschneid.adblocker.core.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsCodecTest {

    @Test
    fun parsesSimpleQueryAndLowercasesName() {
        val bytes = DnsCodec.encodeQuery(0x1234, "Ads.Example.COM", DnsType.A)
        val query = DnsCodec.parseQuery(bytes)!!
        assertEquals(0x1234, query.id)
        assertEquals("ads.example.com", query.question.name)
        assertEquals(DnsType.A, query.question.type)
        assertEquals(DnsCodec.CLASS_IN, query.question.clazz)
        assertTrue(query.recursionDesired)
        assertEquals(bytes.size, query.questionEnd)
    }

    @Test
    fun rejectsResponsesNonStandardOpcodesAndTruncatedMessages() {
        val bytes = DnsCodec.encodeQuery(1, "example.com", DnsType.A)

        val response = bytes.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte()
        assertNull(DnsCodec.parseQuery(response))

        val notify = bytes.copyOf()
        notify[2] = (notify[2].toInt() or (4 shl 3)).toByte()
        assertNull(DnsCodec.parseQuery(notify))

        assertNull(DnsCodec.parseQuery(bytes, 20)) // cut inside the name
        assertNull(DnsCodec.parseQuery(bytes, bytes.size - 2)) // missing class
        assertNull(DnsCodec.parseQuery(ByteArray(5)))

        val noQuestion = bytes.copyOf()
        noQuestion[5] = 0
        assertNull(DnsCodec.parseQuery(noQuestion))
    }

    @Test
    fun readsCompressedNames() {
        val base = DnsCodec.encodeQuery(1, "example.com", DnsType.A)
        val message = ByteArray(base.size + 6)
        System.arraycopy(base, 0, message, 0, base.size)
        var p = base.size
        message[p++] = 3
        message[p++] = 'w'.code.toByte()
        message[p++] = 'w'.code.toByte()
        message[p++] = 'w'.code.toByte()
        message[p++] = 0xC0.toByte()
        message[p] = DnsCodec.HEADER_LENGTH.toByte()

        val (name, end) = DnsCodec.readName(message, base.size, message.size)!!
        assertEquals("www.example.com", name)
        assertEquals(message.size, end)
    }

    @Test
    fun rejectsMalformedNames() {
        assertNull(DnsCodec.readName(byteArrayOf(0xC0.toByte(), 0x00), 0, 2)) // pointer to itself
        assertNull(DnsCodec.readName(byteArrayOf(0x40, 0x00), 0, 2)) // reserved label type
        assertNull(DnsCodec.readName(byteArrayOf(5, 'a'.code.toByte()), 0, 2)) // label longer than data
        assertNull(DnsCodec.readName(byteArrayOf(1, 'a'.code.toByte()), 0, 2)) // missing terminator
    }

    @Test
    fun buildsNxdomainResponse() {
        val query = DnsCodec.parseQuery(DnsCodec.encodeQuery(0xBEEF, "ads.example.com", DnsType.A))!!
        val response = DnsCodec.buildBlockedResponse(query, BlockResponseMode.NXDOMAIN)

        assertEquals(0xBEEF, DnsCodec.transactionId(response))
        assertTrue(DnsCodec.isResponse(response))
        assertEquals(DnsCodec.RCODE_NXDOMAIN, DnsCodec.responseCode(response))
        assertEquals(0x8183, u16(response, 2)) // QR | RD | RA | NXDOMAIN
        assertEquals(1, u16(response, 4))
        assertEquals(0, u16(response, 6))
        assertEquals(1, u16(response, 8)) // the SOA that makes the answer cacheable
        assertEquals(0, u16(response, 10))
        assertArrayEquals(
            query.raw.copyOfRange(DnsCodec.HEADER_LENGTH, query.raw.size),
            response.copyOfRange(DnsCodec.HEADER_LENGTH, query.questionEnd),
        )
        assertSoa(response, query.questionEnd, DnsCodec.DEFAULT_NEGATIVE_TTL_SECONDS)
    }

    @Test
    fun negativeResponsesCarrySoaWithRequestedTtl() {
        val query = DnsCodec.parseQuery(DnsCodec.encodeQuery(3, "ads.example.com", DnsType.HTTPS))!!
        for (mode in BlockResponseMode.entries) {
            val response = DnsCodec.buildBlockedResponse(query, mode, negativeTtlSeconds = 42)
            assertEquals(0, u16(response, 6))
            assertEquals(1, u16(response, 8))
            assertSoa(response, query.questionEnd, 42)
        }
    }

    /** Checks that a well-formed SOA record owned by the question name starts at [offset] and ends the message. */
    private fun assertSoa(response: ByteArray, offset: Int, expectedTtl: Long) {
        assertEquals(0xC00C, u16(response, offset))
        assertEquals(DnsType.SOA, u16(response, offset + 2))
        assertEquals(DnsCodec.CLASS_IN, u16(response, offset + 4))
        assertEquals(expectedTtl, u32(response, offset + 6))
        val rdLength = u16(response, offset + 10)
        val rdata = offset + 12
        assertEquals(response.size, rdata + rdLength)

        val (mname, afterMname) = DnsCodec.readName(response, rdata, response.size)!!
        val (rname, afterRname) = DnsCodec.readName(response, afterMname, response.size)!!
        assertTrue(mname.endsWith(".invalid"))
        assertTrue(rname.endsWith(".invalid"))
        assertEquals(response.size, afterRname + 5 * 4) // SERIAL, REFRESH, RETRY, EXPIRE, MINIMUM
        assertEquals(expectedTtl, u32(response, afterRname + 4 * 4)) // MINIMUM is the negative-caching TTL
    }

    @Test
    fun nxdomainResponseDoesNotClaimRecursionWhenNotRequested() {
        val query = DnsCodec.parseQuery(DnsCodec.encodeQuery(1, "ads.example.com", DnsType.A, recursionDesired = false))!!
        val response = DnsCodec.buildBlockedResponse(query, BlockResponseMode.NXDOMAIN)
        assertEquals(0x8083, u16(response, 2))
    }

    @Test
    fun buildsZeroIpResponses() {
        val a = DnsCodec.parseQuery(DnsCodec.encodeQuery(7, "ads.example.com", DnsType.A))!!
        val responseA = DnsCodec.buildBlockedResponse(a, BlockResponseMode.ZERO_IP, ttlSeconds = 60)
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.responseCode(responseA))
        assertEquals(1, u16(responseA, 6))
        val answer = a.questionEnd
        assertEquals(0xC00C, u16(responseA, answer))
        assertEquals(DnsType.A, u16(responseA, answer + 2))
        assertEquals(DnsCodec.CLASS_IN, u16(responseA, answer + 4))
        assertEquals(60L, u32(responseA, answer + 6))
        assertEquals(4, u16(responseA, answer + 10))
        assertArrayEquals(ByteArray(4), responseA.copyOfRange(answer + 12, answer + 16))
        assertEquals(answer + 16, responseA.size)
        assertEquals(0, u16(responseA, 8))

        val aaaa = DnsCodec.parseQuery(DnsCodec.encodeQuery(8, "ads.example.com", DnsType.AAAA))!!
        val responseAaaa = DnsCodec.buildBlockedResponse(aaaa, BlockResponseMode.ZERO_IP)
        assertEquals(16, u16(responseAaaa, aaaa.questionEnd + 10))
        assertEquals(aaaa.questionEnd + 28, responseAaaa.size)

        val https = DnsCodec.parseQuery(DnsCodec.encodeQuery(9, "ads.example.com", DnsType.HTTPS))!!
        val responseHttps = DnsCodec.buildBlockedResponse(https, BlockResponseMode.ZERO_IP)
        assertEquals(0, u16(responseHttps, 6))
        assertEquals(DnsCodec.RCODE_NOERROR, DnsCodec.responseCode(responseHttps))
        assertSoa(responseHttps, https.questionEnd, DnsCodec.DEFAULT_NEGATIVE_TTL_SECONDS) // NODATA is cacheable too
    }

    @Test
    fun typeNamesAreHumanReadable() {
        assertEquals("A", DnsType.name(DnsType.A))
        assertEquals("AAAA", DnsType.name(DnsType.AAAA))
        assertEquals("HTTPS", DnsType.name(DnsType.HTTPS))
        assertEquals("TYPE99", DnsType.name(99))
    }
}
