package com.winschneid.adblocker.data

import android.content.Context
import android.util.Log
import com.winschneid.adblocker.core.filter.BlocklistSource
import com.winschneid.adblocker.core.filter.BuiltinBlocklist
import com.winschneid.adblocker.core.filter.DefaultBlocklists
import com.winschneid.adblocker.core.filter.DomainMatcher
import com.winschneid.adblocker.core.filter.HostsParser
import com.winschneid.adblocker.core.filter.ListDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class UpdateReport(val updated: Int, val failures: List<Pair<String, String>>)

/**
 * Owns the blocklist sources, the downloaded list files, the user's allow/deny rules and the combined
 * [DomainMatcher] built from all of them.
 */
class BlocklistRepository(
    context: Context,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val listsDir = File(appContext.filesDir, "blocklists")
    private val downloader = ListDownloader()

    private val sourcesState = MutableStateFlow(loadSources())
    val sources: StateFlow<List<BlocklistSource>> = sourcesState.asStateFlow()

    private val allowState = MutableStateFlow(loadRules(KEY_ALLOW))
    val userAllow: StateFlow<Set<String>> = allowState.asStateFlow()

    private val denyState = MutableStateFlow(loadRules(KEY_DENY))
    val userDeny: StateFlow<Set<String>> = denyState.asStateFlow()

    private val matcherState = MutableStateFlow(
        DomainMatcher(
            blocked = if (settings.current.useBuiltinList) BuiltinBlocklist.domains else emptySet(),
            userAllow = allowState.value,
            userDeny = denyState.value,
        ),
    )
    val matcher: StateFlow<DomainMatcher> = matcherState.asStateFlow()

    private val updatingState = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = updatingState.asStateFlow()

    private val updateMutex = Mutex()
    private val rebuildRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            rebuildRequests.collectLatest {
                delay(REBUILD_DEBOUNCE_MILLIS)
                rebuildMatcher()
            }
        }
        scope.launch {
            settings.flow.map { it.useBuiltinList }.distinctUntilChanged().drop(1).collect { requestRebuild() }
        }
        requestRebuild()
    }

    val hasDownloadedLists: Boolean
        get() = sourcesState.value.any { it.enabled && it.lastUpdated > 0L }

    fun requestRebuild() {
        rebuildRequests.tryEmit(Unit)
    }

    private suspend fun rebuildMatcher() = withContext(Dispatchers.IO) {
        val domains = HashSet<String>(INITIAL_CAPACITY)
        if (settings.current.useBuiltinList) domains.addAll(BuiltinBlocklist.domains)
        for (source in sourcesState.value) {
            if (!source.enabled) continue
            val file = fileFor(source.id)
            if (!file.exists()) continue
            try {
                file.bufferedReader().use { reader -> HostsParser.parse(reader) { domains.add(it) } }
            } catch (e: IOException) {
                Log.w(TAG, "Could not read ${file.name}", e)
            }
        }
        matcherState.value = DomainMatcher(domains, allowState.value, denyState.value)
        Log.i(TAG, "Blocklist rebuilt with ${domains.size} domains")
    }

    /** Downloads every (enabled) source. */
    suspend fun updateAll(onlyEnabled: Boolean = true): UpdateReport = updateMutex.withLock {
        updateAllLocked(onlyEnabled)
    }

    private suspend fun updateAllLocked(onlyEnabled: Boolean): UpdateReport {
        updatingState.value = true
        try {
            var updated = 0
            val failures = ArrayList<Pair<String, String>>()
            for (source in sourcesState.value) {
                if (onlyEnabled && !source.enabled) continue
                val error = downloadSource(source)
                if (error == null) updated++ else failures.add(source.name to error)
            }
            settings.update { it.copy(lastListUpdateAttempt = System.currentTimeMillis()) }
            if (updated > 0) requestRebuild()
            return UpdateReport(updated, failures)
        } finally {
            updatingState.value = false
        }
    }

    /** Downloads a single source. Returns null on success or an error message. */
    suspend fun update(sourceId: String): String? = updateMutex.withLock {
        val source = sourcesState.value.firstOrNull { it.id == sourceId } ?: return@withLock "Unknown list"
        updatingState.value = true
        try {
            val error = downloadSource(source)
            if (error == null) requestRebuild()
            error
        } finally {
            updatingState.value = false
        }
    }

    private suspend fun downloadSource(source: BlocklistSource): String? = withContext(Dispatchers.IO) {
        try {
            val result = downloader.download(source.url, fileFor(source.id))
            modifySource(source.id) {
                it.copy(lastUpdated = System.currentTimeMillis(), entryCount = result.entries, lastError = null)
            }
            Log.i(TAG, "Downloaded ${source.name}: ${result.entries} entries, ${result.bytes} bytes")
            null
        } catch (e: IOException) {
            val message = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Failed to download ${source.name}: $message")
            modifySource(source.id) { it.copy(lastError = message) }
            message
        }
    }

    /** Runs "update all" when lists are stale (older than a day) or have never been downloaded. */
    suspend fun autoUpdateIfDue() {
        if (!isAutoUpdateDue()) return
        updateMutex.withLock {
            // Re-check under the lock: another caller may have just finished the same update.
            if (isAutoUpdateDue()) updateAllLocked(onlyEnabled = true)
        }
    }

    private fun isAutoUpdateDue(): Boolean {
        val current = settings.current
        if (!current.autoUpdateLists) return false
        val stale = System.currentTimeMillis() - current.lastListUpdateAttempt > AUTO_UPDATE_INTERVAL_MILLIS
        val missing = sourcesState.value.any { it.enabled && it.lastUpdated == 0L }
        return stale || missing
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        modifySource(id) { it.copy(enabled = enabled) }
        requestRebuild()
    }

    /** Adds a custom source; returns null if the URL is invalid or already present. */
    fun addSource(name: String, url: String): BlocklistSource? {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://")) return null
        if (trimmedUrl.length <= 8 || trimmedUrl.any { it.isWhitespace() }) return null
        if (sourcesState.value.any { it.url == trimmedUrl }) return null
        val source = BlocklistSource(
            id = "custom-" + UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { trimmedUrl.removePrefix("https://").removePrefix("http://") },
            url = trimmedUrl,
        )
        sourcesState.update { it + source }
        persistSources()
        return source
    }

    fun removeSource(id: String) {
        val source = sourcesState.value.firstOrNull { it.id == id } ?: return
        if (source.builtIn) return
        sourcesState.update { list -> list.filterNot { it.id == id } }
        persistSources()
        fileFor(id).delete()
        requestRebuild()
    }

    /** Adds a user rule; returns the normalized domain or null if [domain] is not valid. */
    fun addUserRule(domain: String, allow: Boolean): String? {
        val normalized = HostsParser.normalizeDomain(domain) ?: return null
        val state = if (allow) allowState else denyState
        state.update { it + normalized }
        persistRules(if (allow) KEY_ALLOW else KEY_DENY, state.value)
        requestRebuild()
        return normalized
    }

    fun removeUserRule(domain: String, allow: Boolean) {
        val state = if (allow) allowState else denyState
        state.update { it - domain }
        persistRules(if (allow) KEY_ALLOW else KEY_DENY, state.value)
        requestRebuild()
    }

    private fun modifySource(id: String, transform: (BlocklistSource) -> BlocklistSource) {
        sourcesState.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persistSources()
    }

    private fun fileFor(id: String): File = File(listsDir, id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")

    private fun loadRules(key: String): Set<String> = prefs.getStringSet(key, null)?.toSortedSet() ?: emptySet()

    private fun persistRules(key: String, rules: Set<String>) {
        prefs.edit().putStringSet(key, HashSet(rules)).apply()
    }

    private fun loadSources(): List<BlocklistSource> {
        val defaults = DefaultBlocklists.sources
        val json = prefs.getString(KEY_SOURCES, null) ?: return defaults
        val stored = try {
            parseSources(JSONArray(json))
        } catch (e: JSONException) {
            Log.w(TAG, "Could not parse stored sources", e)
            emptyList()
        }
        val storedById = stored.associateBy { it.id }
        val merged = ArrayList<BlocklistSource>()
        for (default in defaults) {
            val saved = storedById[default.id]
            merged.add(
                if (saved == null) {
                    default
                } else {
                    default.copy(
                        enabled = saved.enabled,
                        lastUpdated = saved.lastUpdated,
                        entryCount = saved.entryCount,
                        lastError = saved.lastError,
                    )
                },
            )
        }
        for (source in stored) {
            if (!source.builtIn && defaults.none { it.id == source.id }) merged.add(source)
        }
        return merged
    }

    private fun parseSources(array: JSONArray): List<BlocklistSource> {
        val result = ArrayList<BlocklistSource>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            result.add(
                BlocklistSource(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    url = obj.optString("url", ""),
                    enabled = obj.optBoolean("enabled", true),
                    builtIn = obj.optBoolean("builtIn", false),
                    description = obj.optString("description", ""),
                    lastUpdated = obj.optLong("lastUpdated", 0L),
                    entryCount = obj.optInt("entryCount", 0),
                    lastError = if (obj.isNull("lastError")) null else obj.optString("lastError"),
                ),
            )
        }
        return result
    }

    private fun persistSources() {
        val array = JSONArray()
        for (source in sourcesState.value) {
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("url", source.url)
                    .put("enabled", source.enabled)
                    .put("builtIn", source.builtIn)
                    .put("description", source.description)
                    .put("lastUpdated", source.lastUpdated)
                    .put("entryCount", source.entryCount)
                    .put("lastError", source.lastError ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private companion object {
        const val TAG = "BlocklistRepository"
        const val PREFS_NAME = "blocklists"
        const val KEY_SOURCES = "sources"
        const val KEY_ALLOW = "user_allow"
        const val KEY_DENY = "user_deny"
        const val INITIAL_CAPACITY = 200_000
        const val REBUILD_DEBOUNCE_MILLIS = 150L
        const val AUTO_UPDATE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
