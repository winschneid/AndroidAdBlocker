package com.winschneid.adblocker.vpn

import com.winschneid.adblocker.core.filter.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class VpnStatus { STOPPED, STARTING, RUNNING }

data class VpnStats(val totalQueries: Long = 0L, val blockedQueries: Long = 0L, val startedAt: Long = 0L)

data class QueryLogEntry(val time: Long, val host: String, val type: Int, val decision: Decision)

/** Process-wide, observable state of the VPN service (status, counters and the recent query log). */
object VpnStateHolder {
    const val LOG_CAPACITY = 500

    private val statusState = MutableStateFlow(VpnStatus.STOPPED)
    val status: StateFlow<VpnStatus> = statusState.asStateFlow()

    private val errorState = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = errorState.asStateFlow()

    private val statsState = MutableStateFlow(VpnStats())
    val stats: StateFlow<VpnStats> = statsState.asStateFlow()

    /** Bumped whenever the log changes; UI maps it to [snapshotLog]. */
    private val logVersionState = MutableStateFlow(0L)
    val logVersion: StateFlow<Long> = logVersionState.asStateFlow()

    private val log = ArrayDeque<QueryLogEntry>()
    private val total = AtomicLong()
    private val blocked = AtomicLong()

    @Volatile
    private var startedAt = 0L

    fun setStatus(status: VpnStatus) {
        statusState.value = status
        if (status != VpnStatus.STOPPED) errorState.value = null
    }

    fun setError(message: String) {
        errorState.value = message
        statusState.value = VpnStatus.STOPPED
    }

    fun clearError() {
        errorState.value = null
    }

    fun markStarted() {
        startedAt = System.currentTimeMillis()
        total.set(0)
        blocked.set(0)
        statsState.value = VpnStats(0L, 0L, startedAt)
    }

    fun record(host: String, type: Int, decision: Decision, keepLog: Boolean) {
        val totalCount = total.incrementAndGet()
        val blockedCount = if (decision.blocked) blocked.incrementAndGet() else blocked.get()
        statsState.value = VpnStats(totalCount, blockedCount, startedAt)
        if (keepLog) {
            synchronized(log) {
                if (log.size >= LOG_CAPACITY) log.removeFirst()
                log.addLast(QueryLogEntry(System.currentTimeMillis(), host, type, decision))
            }
            logVersionState.update { it + 1 }
        }
    }

    /** Most recent entry first. */
    fun snapshotLog(): List<QueryLogEntry> = synchronized(log) { log.reversed() }

    fun clearLog() {
        synchronized(log) { log.clear() }
        logVersionState.update { it + 1 }
    }
}
