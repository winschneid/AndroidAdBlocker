package com.winschneid.adblocker.core.net

import com.winschneid.adblocker.core.put16
import com.winschneid.adblocker.core.put32
import com.winschneid.adblocker.core.u16
import com.winschneid.adblocker.core.u32
import java.util.concurrent.atomic.AtomicInteger

/** A UDP datagram extracted from a raw IPv4/IPv6 packet. */
class UdpDatagram(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
) {
    /** Builds a complete IP packet carrying [responsePayload] from this datagram's destination back to its source. */
    fun reply(responsePayload: ByteArray): ByteArray = IpPacketCodec.buildUdp(
        ipVersion = ipVersion,
        source = destinationAddress,
        destination = sourceAddress,
        sourcePort = destinationPort,
        destinationPort = sourcePort,
        payload = responsePayload,
    )
}

/**
 * Just enough IPv4/IPv6 + UDP/TCP handling for a DNS-only TUN interface:
 * parse UDP datagrams, build UDP replies, and answer stray TCP SYNs with a RST.
 */
object IpPacketCodec {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20

    private const val TCP_FLAG_SYN = 0x02
    private const val TCP_FLAG_RST = 0x04
    private const val TCP_FLAG_ACK = 0x10

    private val identification = AtomicInteger((System.nanoTime() and 0xFFFF).toInt())

    fun ipVersion(packet: ByteArray, length: Int): Int =
        if (length < 1) 0 else (packet[0].toInt() and 0xF0) ushr 4

    private class IpHeader(
        val version: Int,
        val source: ByteArray,
        val destination: ByteArray,
        val protocol: Int,
        val payloadOffset: Int,
        val payloadEnd: Int,
    )

    private fun parseIpHeader(packet: ByteArray, length: Int): IpHeader? {
        if (length > packet.size) return null
        return when (ipVersion(packet, length)) {
            4 -> {
                if (length < IPV4_HEADER) return null
                val ihl = (packet[0].toInt() and 0x0F) * 4
                if (ihl < IPV4_HEADER || ihl > length) return null
                val totalLength = u16(packet, 2)
                if (totalLength < ihl || totalLength > length) return null
                if (u16(packet, 6) and 0x3FFF != 0) return null // fragments are not supported
                IpHeader(
                    version = 4,
                    source = packet.copyOfRange(12, 16),
                    destination = packet.copyOfRange(16, 20),
                    protocol = packet[9].toInt() and 0xFF,
                    payloadOffset = ihl,
                    payloadEnd = totalLength,
                )
            }
            6 -> {
                if (length < IPV6_HEADER) return null
                val payloadLength = u16(packet, 4)
                if (IPV6_HEADER + payloadLength > length) return null
                // Extension headers are not supported; "next header" must be the transport protocol itself.
                IpHeader(
                    version = 6,
                    source = packet.copyOfRange(8, 24),
                    destination = packet.copyOfRange(24, 40),
                    protocol = packet[6].toInt() and 0xFF,
                    payloadOffset = IPV6_HEADER,
                    payloadEnd = IPV6_HEADER + payloadLength,
                )
            }
            else -> null
        }
    }

    /** Parses a UDP datagram out of a raw IP packet, or returns null if the packet is not a complete UDP datagram. */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        val ip = parseIpHeader(packet, length) ?: return null
        if (ip.protocol != PROTO_UDP) return null
        val o = ip.payloadOffset
        if (o + UDP_HEADER > ip.payloadEnd) return null
        val udpLength = u16(packet, o + 4)
        if (udpLength < UDP_HEADER || o + udpLength > ip.payloadEnd) return null
        return UdpDatagram(
            ipVersion = ip.version,
            sourceAddress = ip.source,
            destinationAddress = ip.destination,
            sourcePort = u16(packet, o),
            destinationPort = u16(packet, o + 2),
            payload = packet.copyOfRange(o + UDP_HEADER, o + udpLength),
        )
    }

    /** Builds a complete IP packet containing a UDP datagram. */
    fun buildUdp(
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val transportLength = UDP_HEADER + payload.size
        val out = newIpPacket(ipVersion, source, destination, PROTO_UDP, transportLength)
        val o = out.size - transportLength
        put16(out, o, sourcePort)
        put16(out, o + 2, destinationPort)
        put16(out, o + 4, transportLength)
        System.arraycopy(payload, 0, out, o + UDP_HEADER, payload.size)
        var sum = transportChecksum(ipVersion, source, destination, PROTO_UDP, out, o, transportLength)
        if (sum == 0) sum = 0xFFFF // RFC 768: a computed zero is transmitted as all ones
        put16(out, o + 6, sum)
        return out
    }

    /**
     * If [packet] is a TCP SYN, builds a RST/ACK reply so the client fails immediately instead of
     * waiting for a connect timeout (e.g. DNS-over-TLS probes or TCP DNS fallback aimed at our fake resolver).
     * Returns null for anything else.
     */
    fun buildTcpReset(packet: ByteArray, length: Int): ByteArray? {
        val ip = parseIpHeader(packet, length) ?: return null
        if (ip.protocol != PROTO_TCP) return null
        val o = ip.payloadOffset
        if (o + TCP_HEADER > ip.payloadEnd) return null
        val flags = packet[o + 13].toInt() and 0xFF
        if (flags and TCP_FLAG_RST != 0 || flags and TCP_FLAG_SYN == 0) return null
        val sourcePort = u16(packet, o)
        val destinationPort = u16(packet, o + 2)
        val sequence = u32(packet, o + 4)

        val out = newIpPacket(ip.version, ip.destination, ip.source, PROTO_TCP, TCP_HEADER)
        val t = out.size - TCP_HEADER
        put16(out, t, destinationPort)
        put16(out, t + 2, sourcePort)
        put32(out, t + 4, 0L) // our sequence number
        put32(out, t + 8, (sequence + 1) and 0xFFFFFFFFL) // acknowledge the SYN
        out[t + 12] = (5 shl 4).toByte() // data offset: 5 words, no options
        out[t + 13] = (TCP_FLAG_RST or TCP_FLAG_ACK).toByte()
        // window size, checksum and urgent pointer are zero at this point
        put16(out, t + 16, transportChecksum(ip.version, ip.destination, ip.source, PROTO_TCP, out, t, TCP_HEADER))
        return out
    }

    /** Verifies the IP header checksum (IPv4 only) and the transport checksum of [packet]. Mainly for tests. */
    fun verifyChecksums(packet: ByteArray, length: Int = packet.size): Boolean {
        val ip = parseIpHeader(packet, length) ?: return false
        if (ip.version == 4 && checksum(packet, 0, ip.payloadOffset, 0L) != 0) return false
        val transportLength = ip.payloadEnd - ip.payloadOffset
        if (ip.version == 4 && ip.protocol == PROTO_UDP && u16(packet, ip.payloadOffset + 6) == 0) return true // optional
        return transportChecksum(ip.version, ip.source, ip.destination, ip.protocol, packet, ip.payloadOffset, transportLength) == 0
    }

    /** Allocates a packet with a filled-in IP header; the last [transportLength] bytes are left zeroed for the caller. */
    private fun newIpPacket(
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
        protocol: Int,
        transportLength: Int,
    ): ByteArray = when (ipVersion) {
        4 -> {
            require(source.size == 4 && destination.size == 4) { "IPv4 addresses must be 4 bytes" }
            val out = ByteArray(IPV4_HEADER + transportLength)
            out[0] = 0x45 // version 4, IHL 5
            put16(out, 2, out.size)
            put16(out, 4, identification.incrementAndGet() and 0xFFFF)
            put16(out, 6, 0x4000) // don't fragment
            out[8] = 64 // TTL
            out[9] = protocol.toByte()
            System.arraycopy(source, 0, out, 12, 4)
            System.arraycopy(destination, 0, out, 16, 4)
            put16(out, 10, checksum(out, 0, IPV4_HEADER, 0L))
            out
        }
        6 -> {
            require(source.size == 16 && destination.size == 16) { "IPv6 addresses must be 16 bytes" }
            val out = ByteArray(IPV6_HEADER + transportLength)
            out[0] = 0x60 // version 6
            put16(out, 4, transportLength)
            out[6] = protocol.toByte()
            out[7] = 64 // hop limit
            System.arraycopy(source, 0, out, 8, 16)
            System.arraycopy(destination, 0, out, 24, 16)
            out
        }
        else -> throw IllegalArgumentException("Unsupported IP version: $ipVersion")
    }

    /**
     * Transport checksum including the IPv4/IPv6 pseudo header. For both families the pseudo header
     * sums to: addresses + protocol + transport length (the IPv6 32-bit fields have zero upper halves).
     */
    internal fun transportChecksum(
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
        protocol: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(ipVersion == 4 || ipVersion == 6)
        val seed = sumWords(source, 0, source.size) + sumWords(destination, 0, destination.size) +
            protocol.toLong() + length.toLong()
        return checksum(data, offset, length, seed)
    }

    /** RFC 1071 Internet checksum over [length] bytes at [offset], starting from [seed] (a sum of 16-bit words). */
    internal fun checksum(data: ByteArray, offset: Int, length: Int, seed: Long): Int {
        var sum = seed + sumWords(data, offset, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun sumWords(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += u16(data, i).toLong()
            i += 2
        }
        if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        return sum
    }
}
