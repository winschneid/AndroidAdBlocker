package com.winschneid.adblocker.core.net

import com.winschneid.adblocker.core.put16
import com.winschneid.adblocker.core.put32
import com.winschneid.adblocker.core.u16
import com.winschneid.adblocker.core.u32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketCodecTest {
    private val v4Source = byteArrayOf(10, 111, 222.toByte(), 1)
    private val v4Destination = byteArrayOf(10, 111, 222.toByte(), 2)
    private val v6Source = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 1 }
    private val v6Destination = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 2 }
    private val payload = "hello dns".toByteArray()

    @Test
    fun ipv4RoundTrip() {
        val packet = IpPacketCodec.buildUdp(4, v4Source, v4Destination, 40000, 53, payload)
        assertEquals(20 + 8 + payload.size, packet.size)
        assertEquals(0x45, packet[0].toInt() and 0xFF)
        assertEquals(packet.size, u16(packet, 2))
        assertTrue(IpPacketCodec.verifyChecksums(packet))

        val datagram = IpPacketCodec.parseUdp(packet, packet.size)!!
        assertEquals(4, datagram.ipVersion)
        assertArrayEquals(v4Source, datagram.sourceAddress)
        assertArrayEquals(v4Destination, datagram.destinationAddress)
        assertEquals(40000, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun ipv6RoundTrip() {
        val packet = IpPacketCodec.buildUdp(6, v6Source, v6Destination, 40000, 53, payload)
        assertEquals(40 + 8 + payload.size, packet.size)
        assertEquals(0x60, packet[0].toInt() and 0xFF)
        assertEquals(8 + payload.size, u16(packet, 4))
        assertEquals(17, packet[6].toInt())
        assertTrue(IpPacketCodec.verifyChecksums(packet))

        val datagram = IpPacketCodec.parseUdp(packet, packet.size)!!
        assertEquals(6, datagram.ipVersion)
        assertArrayEquals(v6Source, datagram.sourceAddress)
        assertArrayEquals(v6Destination, datagram.destinationAddress)
        assertEquals(40000, datagram.sourcePort)
        assertEquals(53, datagram.destinationPort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun replySwapsEndpoints() {
        val request = IpPacketCodec.parseUdp(IpPacketCodec.buildUdp(4, v4Source, v4Destination, 40000, 53, payload), 20 + 8 + payload.size)!!
        val reply = request.reply("response".toByteArray())
        assertTrue(IpPacketCodec.verifyChecksums(reply))
        val parsed = IpPacketCodec.parseUdp(reply, reply.size)!!
        assertArrayEquals(v4Destination, parsed.sourceAddress)
        assertArrayEquals(v4Source, parsed.destinationAddress)
        assertEquals(53, parsed.sourcePort)
        assertEquals(40000, parsed.destinationPort)
        assertArrayEquals("response".toByteArray(), parsed.payload)
    }

    @Test
    fun parsesIpv4HeaderWithOptions() {
        val plain = IpPacketCodec.buildUdp(4, v4Source, v4Destination, 1000, 53, payload)
        val withOptions = ByteArray(plain.size + 4)
        System.arraycopy(plain, 0, withOptions, 0, 20)
        System.arraycopy(plain, 20, withOptions, 24, plain.size - 20)
        withOptions[0] = 0x46 // IHL = 6
        put16(withOptions, 2, withOptions.size)
        val datagram = IpPacketCodec.parseUdp(withOptions, withOptions.size)!!
        assertEquals(1000, datagram.sourcePort)
        assertArrayEquals(payload, datagram.payload)
    }

    @Test
    fun rejectsFragmentsTruncationAndOtherProtocols() {
        val packet = IpPacketCodec.buildUdp(4, v4Source, v4Destination, 40000, 53, payload)

        val fragment = packet.copyOf()
        fragment[6] = 0x20 // "more fragments"
        assertNull(IpPacketCodec.parseUdp(fragment, fragment.size))

        val offsetFragment = packet.copyOf()
        put16(offsetFragment, 6, 0x0001)
        assertNull(IpPacketCodec.parseUdp(offsetFragment, offsetFragment.size))

        assertNull(IpPacketCodec.parseUdp(packet, packet.size - 1)) // declared length exceeds buffer

        val tcp = packet.copyOf()
        tcp[9] = 6
        assertNull(IpPacketCodec.parseUdp(tcp, tcp.size))

        assertNull(IpPacketCodec.parseUdp(ByteArray(3), 3))
        assertNull(IpPacketCodec.parseUdp(ByteArray(60), 60)) // version 0

        val shortUdp = packet.copyOf()
        put16(shortUdp, 24, 4) // UDP length < 8
        assertNull(IpPacketCodec.parseUdp(shortUdp, shortUdp.size))
    }

    @Test
    fun checksumDetectsCorruption() {
        val packet = IpPacketCodec.buildUdp(4, v4Source, v4Destination, 40000, 53, payload)
        packet[30] = (packet[30].toInt() xor 1).toByte()
        assertFalse(IpPacketCodec.verifyChecksums(packet))

        val v6 = IpPacketCodec.buildUdp(6, v6Source, v6Destination, 40000, 53, payload)
        v6[50] = (v6[50].toInt() xor 1).toByte()
        assertFalse(IpPacketCodec.verifyChecksums(v6))
    }

    private fun tcpSyn(ipVersion: Int, sourcePort: Int, destinationPort: Int, sequence: Long): ByteArray {
        val headerLength = if (ipVersion == 4) 20 else 40
        val packet = ByteArray(headerLength + 20)
        if (ipVersion == 4) {
            packet[0] = 0x45
            put16(packet, 2, packet.size)
            packet[9] = 6
            System.arraycopy(v4Source, 0, packet, 12, 4)
            System.arraycopy(v4Destination, 0, packet, 16, 4)
        } else {
            packet[0] = 0x60
            put16(packet, 4, 20)
            packet[6] = 6
            System.arraycopy(v6Source, 0, packet, 8, 16)
            System.arraycopy(v6Destination, 0, packet, 24, 16)
        }
        val t = headerLength
        put16(packet, t, sourcePort)
        put16(packet, t + 2, destinationPort)
        put32(packet, t + 4, sequence)
        packet[t + 12] = 0x50
        packet[t + 13] = 0x02 // SYN
        return packet
    }

    @Test
    fun buildsTcpResetForSyn() {
        val syn = tcpSyn(4, 51000, 853, 1000L)
        val reset = IpPacketCodec.buildTcpReset(syn, syn.size)!!
        assertEquals(40, reset.size)
        assertTrue(IpPacketCodec.verifyChecksums(reset))
        assertEquals(6, reset[9].toInt())
        assertArrayEquals(v4Destination, reset.copyOfRange(12, 16))
        assertArrayEquals(v4Source, reset.copyOfRange(16, 20))
        assertEquals(853, u16(reset, 20))
        assertEquals(51000, u16(reset, 22))
        assertEquals(0L, u32(reset, 24))
        assertEquals(1001L, u32(reset, 28))
        assertEquals(0x50, reset[32].toInt() and 0xFF)
        assertEquals(0x14, reset[33].toInt() and 0xFF) // RST | ACK
    }

    @Test
    fun buildsIpv6TcpReset() {
        val syn = tcpSyn(6, 51000, 53, 0xFFFFFFFFL)
        val reset = IpPacketCodec.buildTcpReset(syn, syn.size)!!
        assertEquals(60, reset.size)
        assertTrue(IpPacketCodec.verifyChecksums(reset))
        assertArrayEquals(v6Destination, reset.copyOfRange(8, 24))
        assertArrayEquals(v6Source, reset.copyOfRange(24, 40))
        assertEquals(0L, u32(reset, 48)) // sequence wrapped around
    }

    @Test
    fun doesNotResetNonSynOrRstSegments() {
        val syn = tcpSyn(4, 51000, 853, 1L)
        assertNotNull(IpPacketCodec.buildTcpReset(syn, syn.size))

        val ack = syn.copyOf()
        ack[33] = 0x10
        assertNull(IpPacketCodec.buildTcpReset(ack, ack.size))

        val rst = syn.copyOf()
        rst[33] = 0x06
        assertNull(IpPacketCodec.buildTcpReset(rst, rst.size))

        val udp = IpPacketCodec.buildUdp(4, v4Source, v4Destination, 1, 2, payload)
        assertNull(IpPacketCodec.buildTcpReset(udp, udp.size))
    }
}
