package com.winschneid.adblocker.core.dns

import com.winschneid.adblocker.core.put16
import com.winschneid.adblocker.core.put32
import com.winschneid.adblocker.core.u16

/** DNS record types (RFC 1035, RFC 3596, RFC 9460). */
object DnsType {
    const val A = 1
    const val NS = 2
    const val CNAME = 5
    const val SOA = 6
    const val PTR = 12
    const val MX = 15
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33
    const val SVCB = 64
    const val HTTPS = 65
    const val ANY = 255

    fun name(type: Int): String = when (type) {
        A -> "A"
        NS -> "NS"
        CNAME -> "CNAME"
        SOA -> "SOA"
        PTR -> "PTR"
        MX -> "MX"
        TXT -> "TXT"
        AAAA -> "AAAA"
        SRV -> "SRV"
        SVCB -> "SVCB"
        HTTPS -> "HTTPS"
        ANY -> "ANY"
        else -> "TYPE$type"
    }
}

data class DnsQuestion(val name: String, val type: Int, val clazz: Int)

/** A parsed DNS query: the header plus its first question. */
class DnsQuery(
    val id: Int,
    val flags: Int,
    val question: DnsQuestion,
    /** The raw message; only the first [questionEnd] bytes are guaranteed to be meaningful. */
    val raw: ByteArray,
    /** Offset one past the end of the question section. */
    val questionEnd: Int,
) {
    val recursionDesired: Boolean get() = flags and DnsCodec.FLAG_RD != 0
}

/** How a blocked query is answered. */
enum class BlockResponseMode {
    /** Reply NXDOMAIN ("no such domain"). Clients fail immediately and cache the negative answer. */
    NXDOMAIN,

    /** Reply with 0.0.0.0 / :: like a classic hosts file. */
    ZERO_IP,
}

/**
 * Minimal DNS wire-format codec: parses queries and builds synthetic answers for blocked names.
 * Anything that is not a plain, standard query is rejected (returns null) so the caller can forward it unchanged.
 */
object DnsCodec {
    const val HEADER_LENGTH = 12
    const val CLASS_IN = 1
    const val DEFAULT_BLOCK_TTL_SECONDS = 300L

    /**
     * How long clients may cache a block response that carries no address (NXDOMAIN / NODATA).
     * Kept short so that a domain the user has just allowed starts resolving again almost immediately.
     */
    const val DEFAULT_NEGATIVE_TTL_SECONDS = 10L

    const val FLAG_QR = 0x8000
    const val FLAG_RD = 0x0100
    const val FLAG_RA = 0x0080

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val MAX_NAME_LENGTH = 253
    private const val MAX_POINTER_HOPS = 16
    private const val MAX_ANSWERS_INSPECTED = 32

    /** NAME (compression pointer) + TYPE + CLASS + TTL + RDLENGTH. */
    private const val RECORD_HEADER_LENGTH = 2 + 2 + 2 + 4 + 2

    /** Pointer to the question name, which always starts right after the header. */
    private const val QUESTION_NAME_POINTER = 0xC000 or HEADER_LENGTH

    // The synthetic SOA only exists to make negative answers cacheable; ".invalid" can never be a real zone.
    private val SOA_MNAME = encodeName("adblocker.invalid")
    private val SOA_RNAME = encodeName("nobody.invalid")
    private const val SOA_SERIAL = 1L
    private const val SOA_REFRESH = 3600L
    private const val SOA_RETRY = 600L
    private const val SOA_EXPIRE = 86400L

    fun parseQuery(data: ByteArray, length: Int = data.size): DnsQuery? {
        if (length < HEADER_LENGTH || length > data.size) return null
        val id = u16(data, 0)
        val flags = u16(data, 2)
        if (flags and FLAG_QR != 0) return null // this is a response, not a query
        if ((flags ushr 11) and 0x0F != 0) return null // only the standard QUERY opcode
        if (u16(data, 4) < 1) return null // QDCOUNT
        val (name, afterName) = readName(data, HEADER_LENGTH, length) ?: return null
        if (afterName + 4 > length) return null
        val type = u16(data, afterName)
        val clazz = u16(data, afterName + 2)
        return DnsQuery(id, flags, DnsQuestion(name, type, clazz), data, afterName + 4)
    }

    /**
     * Reads a (possibly compressed) domain name starting at [offset].
     * Returns the lower-cased name and the offset just after the name in the original stream, or null if malformed.
     */
    fun readName(data: ByteArray, offset: Int, length: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = offset
        var end = -1
        var hops = 0
        while (true) {
            if (pos >= length) return null
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= length) return null
                    if (++hops > MAX_POINTER_HOPS) return null
                    val pointer = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (pointer >= pos) return null // pointers must point backwards
                    if (end < 0) end = pos + 2
                    pos = pointer
                }
                len and 0xC0 != 0 -> return null // reserved label types
                else -> {
                    if (pos + 1 + len > length) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..len) {
                        val c = data[pos + i].toInt() and 0xFF
                        sb.append(if (c in 'A'.code..'Z'.code) (c + 32).toChar() else c.toChar())
                    }
                    if (sb.length > MAX_NAME_LENGTH) return null
                    pos += 1 + len
                }
            }
        }
        return Pair(sb.toString(), if (end < 0) pos else end)
    }

    /**
     * Builds the response for a blocked [query].
     *
     * Answers without an address record (NXDOMAIN, or NODATA for non-address types in [BlockResponseMode.ZERO_IP])
     * carry a synthetic SOA record in the authority section. Without it neither the Android resolver nor browsers
     * cache the negative answer (RFC 2308), and every retry of a blocked name would hit the VPN again.
     */
    fun buildBlockedResponse(
        query: DnsQuery,
        mode: BlockResponseMode,
        ttlSeconds: Long = DEFAULT_BLOCK_TTL_SECONDS,
        negativeTtlSeconds: Long = DEFAULT_NEGATIVE_TTL_SECONDS,
    ): ByteArray {
        val question = query.question
        val questionLength = query.questionEnd - HEADER_LENGTH
        val rdata: ByteArray? = if (mode == BlockResponseMode.ZERO_IP) {
            when (question.type) {
                DnsType.A -> ByteArray(4)
                DnsType.AAAA -> ByteArray(16)
                else -> null // NODATA for other types
            }
        } else {
            null
        }
        val rcode = if (mode == BlockResponseMode.NXDOMAIN) RCODE_NXDOMAIN else RCODE_NOERROR
        val answerLength = if (rdata != null) RECORD_HEADER_LENGTH + rdata.size else 0
        val soaRdataLength = SOA_MNAME.size + SOA_RNAME.size + 5 * 4
        val authorityLength = if (rdata == null) RECORD_HEADER_LENGTH + soaRdataLength else 0

        val out = ByteArray(HEADER_LENGTH + questionLength + answerLength + authorityLength)
        put16(out, 0, query.id)
        put16(out, 2, FLAG_QR or (query.flags and FLAG_RD) or FLAG_RA or rcode)
        put16(out, 4, 1) // QDCOUNT
        put16(out, 6, if (rdata != null) 1 else 0) // ANCOUNT
        put16(out, 8, if (rdata == null) 1 else 0) // NSCOUNT; ARCOUNT stays 0
        System.arraycopy(query.raw, HEADER_LENGTH, out, HEADER_LENGTH, questionLength)
        var p = HEADER_LENGTH + questionLength
        if (rdata != null) {
            p = putRecordHeader(out, p, question.type, question.clazz, ttlSeconds, rdata.size)
            System.arraycopy(rdata, 0, out, p, rdata.size)
        } else {
            p = putRecordHeader(out, p, DnsType.SOA, question.clazz, negativeTtlSeconds, soaRdataLength)
            System.arraycopy(SOA_MNAME, 0, out, p, SOA_MNAME.size); p += SOA_MNAME.size
            System.arraycopy(SOA_RNAME, 0, out, p, SOA_RNAME.size); p += SOA_RNAME.size
            put32(out, p, SOA_SERIAL); p += 4
            put32(out, p, SOA_REFRESH); p += 4
            put32(out, p, SOA_RETRY); p += 4
            put32(out, p, SOA_EXPIRE); p += 4
            put32(out, p, negativeTtlSeconds) // MINIMUM: the negative-caching TTL
        }
        return out
    }

    /**
     * Returns the targets of the CNAME records in the answer section of [response] (lower-cased, in message
     * order). The result is empty when the message is not a response or has no CNAME; parsing simply stops at
     * the first malformed record, so a truncated answer yields the targets seen up to that point.
     */
    fun cnameTargets(response: ByteArray, length: Int = response.size): List<String> {
        if (length < HEADER_LENGTH || length > response.size || !isResponse(response, length)) return emptyList()
        var pos = HEADER_LENGTH
        repeat(u16(response, 4)) { // skip the question section
            val afterName = readName(response, pos, length)?.second ?: return emptyList()
            pos = afterName + 4
        }
        var targets: MutableList<String>? = null
        repeat(minOf(u16(response, 6), MAX_ANSWERS_INSPECTED)) {
            val afterName = readName(response, pos, length)?.second ?: return targets.orEmpty()
            val rdata = afterName + 10 // TYPE, CLASS, TTL and RDLENGTH precede the record data
            if (rdata > length) return targets.orEmpty()
            val end = rdata + u16(response, afterName + 8)
            if (end > length) return targets.orEmpty()
            if (u16(response, afterName) == DnsType.CNAME) {
                val target = readName(response, rdata, length)?.first
                if (target != null) (targets ?: ArrayList<String>(2).also { targets = it }).add(target)
            }
            pos = end
        }
        return targets.orEmpty()
    }

    /** Writes a resource record header owned by the question name; returns the offset of its RDATA. */
    private fun putRecordHeader(out: ByteArray, offset: Int, type: Int, clazz: Int, ttlSeconds: Long, rdLength: Int): Int {
        put16(out, offset, QUESTION_NAME_POINTER)
        put16(out, offset + 2, type)
        put16(out, offset + 4, clazz)
        put32(out, offset + 6, ttlSeconds)
        put16(out, offset + 10, rdLength)
        return offset + RECORD_HEADER_LENGTH
    }

    /** Encodes [name] in uncompressed wire format (length-prefixed labels, terminated by the root label). */
    private fun encodeName(name: String): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val out = ByteArray(labels.sumOf { it.length + 1 } + 1)
        var p = 0
        for (label in labels) {
            require(label.length in 1..63) { "Invalid label '$label' in $name" }
            out[p++] = label.length.toByte()
            for (ch in label) out[p++] = ch.code.toByte()
        }
        return out
    }

    /** Encodes a simple query; used by tests and connectivity checks. */
    fun encodeQuery(
        id: Int,
        name: String,
        type: Int,
        clazz: Int = CLASS_IN,
        recursionDesired: Boolean = true,
    ): ByteArray {
        val encodedName = encodeName(name)
        val out = ByteArray(HEADER_LENGTH + encodedName.size + 4)
        put16(out, 0, id)
        put16(out, 2, if (recursionDesired) FLAG_RD else 0)
        put16(out, 4, 1)
        System.arraycopy(encodedName, 0, out, HEADER_LENGTH, encodedName.size)
        val p = HEADER_LENGTH + encodedName.size
        put16(out, p, type)
        put16(out, p + 2, clazz)
        return out
    }

    fun transactionId(message: ByteArray): Int = u16(message, 0)

    fun setTransactionId(message: ByteArray, id: Int) = put16(message, 0, id)

    fun responseCode(message: ByteArray, length: Int = message.size): Int =
        if (length >= 4) message[3].toInt() and 0x0F else -1

    fun isResponse(message: ByteArray, length: Int = message.size): Boolean =
        length >= 4 && (message[2].toInt() and 0x80) != 0
}
