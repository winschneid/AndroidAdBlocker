package com.winschneid.adblocker.core.engine

import com.winschneid.adblocker.core.net.UdpDatagram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.Random

class ConstantRandom(private val value: Int) : Random() {
    override fun nextInt(bound: Int): Int = value % bound
}

class PendingQueryTableTest {
    private fun context(id: Int) = ReplyContext(
        UdpDatagram(4, byteArrayOf(10, 0, 0, 1), byteArrayOf(10, 0, 0, 2), 40000, 53, ByteArray(0)),
        id,
    )

    @Test
    fun registersAndRemoves() {
        val table = PendingQueryTable(random = ConstantRandom(7))
        val context = context(1)
        val id = table.register(context, now = 1000L)
        assertEquals(7, id)
        assertEquals(1, table.size)
        assertSame(context, table.remove(id))
        assertEquals(0, table.size)
        assertNull(table.remove(id))
    }

    @Test
    fun avoidsIdCollisions() {
        val table = PendingQueryTable(random = ConstantRandom(0xFFFF))
        val first = table.register(context(1), now = 0L)
        val second = table.register(context(2), now = 0L)
        assertEquals(0xFFFF, first)
        assertEquals(0, second) // wrapped around
        assertNotEquals(first, second)
        assertEquals(2, table.size)
    }

    @Test
    fun expiresOldEntries() {
        val table = PendingQueryTable(timeoutMillis = 1000L, random = Random(1))
        val old = table.register(context(1), now = 0L)
        val fresh = table.register(context(2), now = 900L)
        assertEquals(1, table.expire(now = 1500L))
        assertNull(table.remove(old))
        assertEquals(2, table.remove(fresh)!!.originalId)
    }

    @Test
    fun evictsOldestWhenFull() {
        val table = PendingQueryTable(timeoutMillis = 60_000L, random = Random(1), maxEntries = 2)
        val a = table.register(context(1), now = 0L)
        val b = table.register(context(2), now = 10L)
        val c = table.register(context(3), now = 20L)
        assertEquals(2, table.size)
        assertNull(table.remove(a))
        assertEquals(2, table.remove(b)!!.originalId)
        assertEquals(3, table.remove(c)!!.originalId)
    }
}
