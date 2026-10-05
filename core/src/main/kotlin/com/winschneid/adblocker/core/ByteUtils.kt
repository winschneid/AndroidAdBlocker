package com.winschneid.adblocker.core

/** Big-endian helpers shared by the DNS and IP codecs. */
internal fun u16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

internal fun u32(data: ByteArray, offset: Int): Long =
    (u16(data, offset).toLong() shl 16) or u16(data, offset + 2).toLong()

internal fun put16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

internal fun put32(data: ByteArray, offset: Int, value: Long) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}
