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

    const val FLAG_QR = 0x8000
    const val FLAG_RD = 0x0100
    const val FLAG_RA = 0x0080

    const val RCODE_NOERROR = 0
    const val RCODE_NXDOMAIN = 3

    private const val MAX_NAME_LENGTH = 253
    private const val MAX_POINTER_HOPS = 16

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

    /** Builds the response for a blocked [query]. */
    fun buildBlockedResponse(
        query: DnsQuery,
        mode: BlockResponseMode,
        ttlSeconds: Long = DEFAULT_BLOCK_TTL_SECONDS,
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
        val answerLength = if (rdata != null) 2 + 2 + 2 + 4 + 2 + rdata.size else 0

        val out = ByteArray(HEADER_LENGTH + questionLength + answerLength)
        put16(out, 0, query.id)
        put16(out, 2, FLAG_QR or (query.flags and FLAG_RD) or FLAG_RA or rcode)
        put16(out, 4, 1) // QDCOUNT
        put16(out, 6, if (rdata != null) 1 else 0) // ANCOUNT; NSCOUNT and ARCOUNT stay 0
        System.arraycopy(query.raw, HEADER_LENGTH, out, HEADER_LENGTH, questionLength)
        if (rdata != null) {
            var p = HEADER_LENGTH + questionLength
            out[p++] = 0xC0.toByte() // compression pointer to the question name
            out[p++] = HEADER_LENGTH.toByte()
            put16(out, p, question.type); p += 2
            put16(out, p, question.clazz); p += 2
            put32(out, p, ttlSeconds); p += 4
            put16(out, p, rdata.size); p += 2
            System.arraycopy(rdata, 0, out, p, rdata.size)
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
        val labels = name.trimEnd('.').split('.')
        val nameLength = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(HEADER_LENGTH + nameLength + 4)
        put16(out, 0, id)
        put16(out, 2, if (recursionDesired) FLAG_RD else 0)
        put16(out, 4, 1)
        var p = HEADER_LENGTH
        for (label in labels) {
            require(label.length in 1..63) { "Invalid label '$label' in $name" }
            out[p++] = label.length.toByte()
            for (ch in label) out[p++] = ch.code.toByte()
        }
        out[p++] = 0
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
