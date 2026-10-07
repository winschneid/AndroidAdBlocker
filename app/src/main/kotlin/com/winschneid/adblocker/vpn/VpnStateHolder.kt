package com.winschneid.adblocker.vpn

import com.winschneid.adblocker.core.filter.Decision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class VpnStatus { STOPPED, STARTING, RUNNING }

data class VpnStats(val totalQueries: Long = 0L, val blockedQueries: Long = 0L, val startedAt: Long = 0L)

data class QueryLogEntry(
    val time: Long,
    val host: String,
    val type: Int,
    val decision: Decision,
    /** For [Decision.BLOCKED_BY_CNAME]: the blocked name the answer pointed to. */
    val blockedAlias: String? = null,
)

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
    private val statsLock = Any()
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

    fun record(host: String, type: Int, decision: Decision, keepLog: Boolean, blockedAlias: String? = null) {
        total.incrementAndGet()
        if (decision.blocked) blocked.incrementAndGet()
        publishStats()
        if (keepLog) {
            synchronized(log) {
                if (log.size >= LOG_CAPACITY) log.removeFirst()
                log.addLast(QueryLogEntry(System.currentTimeMillis(), host, type, decision, blockedAlias))
            }
            logVersionState.update { it + 1 }
        }
    }

    /**
     * A query that was forwarded (and already recorded as allowed) got a block response after all, because its
     * answer was an alias of the blocked name [target]. Counts it as blocked and corrects its log entry.
     */
    fun recordCloaked(host: String, type: Int, target: String, keepLog: Boolean) {
        blocked.incrementAndGet()
        publishStats()
        if (keepLog) {
            synchronized(log) {
                val index = log.indexOfLast { it.host == host && it.type == type && it.decision == Decision.ALLOWED }
                if (index >= 0) log[index] = log[index].copy(decision = Decision.BLOCKED_BY_CNAME, blockedAlias = target)
            }
            logVersionState.update { it + 1 }
        }
    }

    /** Queries are recorded on the TUN thread and corrected on the upstream thread; publish one at a time. */
    private fun publishStats() {
        synchronized(statsLock) { statsState.value = VpnStats(total.get(), blocked.get(), startedAt) }
    }

    /** Most recent entry first. */
    fun snapshotLog(): List<QueryLogEntry> = synchronized(log) { log.reversed() }

    fun clearLog() {
        synchronized(log) { log.clear() }
        logVersionState.update { it + 1 }
    }
}
