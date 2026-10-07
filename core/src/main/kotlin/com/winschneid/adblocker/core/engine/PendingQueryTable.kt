package com.winschneid.adblocker.core.engine

import java.util.Random

/**
 * Tracks queries forwarded upstream. Every outstanding query gets a fresh random transaction id so that
 * answers can be matched even when several apps reuse the same id; the original id is restored on reply.
 * Thread-safe.
 */
class PendingQueryTable(
    private val timeoutMillis: Long = 10_000L,
    private val random: Random = Random(),
    private val maxEntries: Int = 4096,
) {
    private class Entry(val context: ReplyContext, val createdAt: Long)

    private val entries = HashMap<Int, Entry>()

    val size: Int
        get() = synchronized(this) { entries.size }

    /** Registers [context] and returns the transaction id to use upstream. */
    fun register(context: ReplyContext, now: Long = System.currentTimeMillis()): Int = synchronized(this) {
        if (entries.size >= maxEntries) {
            expire(now)
            if (entries.size >= maxEntries) evictOldest()
        }
        var id = random.nextInt(0x10000)
        while (entries.containsKey(id)) id = (id + 1) and 0xFFFF
        entries[id] = Entry(context, now)
        id
    }

    /** Removes and returns the context registered under [id], or null if unknown / already expired. */
    fun remove(id: Int): ReplyContext? = synchronized(this) { entries.remove(id)?.context }

    /** Drops entries older than the timeout. Returns how many were removed. */
    fun expire(now: Long = System.currentTimeMillis()): Int = synchronized(this) {
        var removed = 0
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().createdAt > timeoutMillis) {
                iterator.remove()
                removed++
            }
        }
        removed
    }

    private fun evictOldest() {
        val oldest = entries.entries.minByOrNull { it.value.createdAt } ?: return
        entries.remove(oldest.key)
    }
}
