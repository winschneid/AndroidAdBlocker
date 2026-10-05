package com.winschneid.adblocker.data

import android.content.Context
import android.content.SharedPreferences
import com.winschneid.adblocker.core.dns.BlockResponseMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Upstream resolver choices. SYSTEM uses the DNS servers of the underlying network. */
enum class UpstreamDns(val addresses: List<String>) {
    SYSTEM(emptyList()),
    CLOUDFLARE(listOf("1.1.1.1", "1.0.0.1")),
    GOOGLE(listOf("8.8.8.8", "8.8.4.4")),
    QUAD9(listOf("9.9.9.9", "149.112.112.112")),
    ADGUARD(listOf("94.140.14.14", "94.140.15.15")),
    CUSTOM(emptyList()),
}

data class UserSettings(
    val upstream: UpstreamDns = UpstreamDns.SYSTEM,
    val customUpstream: String = "",
    val blockMode: BlockResponseMode = BlockResponseMode.NXDOMAIN,
    /** Also block names whose answer is an alias (CNAME) of a blocked name. Opt-in: it can break sites. */
    val blockAliases: Boolean = false,
    val ipv6Enabled: Boolean = true,
    val autoStartOnBoot: Boolean = true,
    val autoUpdateLists: Boolean = true,
    val queryLogEnabled: Boolean = true,
    val useBuiltinList: Boolean = true,
    /** Epoch millis of the last automatic/manual "update all" run. */
    val lastListUpdateAttempt: Long = 0L,
)

/** SharedPreferences-backed settings exposed as a StateFlow. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<UserSettings> = state.asStateFlow()
    val current: UserSettings get() = state.value

    fun update(transform: (UserSettings) -> UserSettings) {
        synchronized(this) {
            val updated = transform(state.value)
            save(updated)
            state.value = updated
        }
    }

    private fun load(): UserSettings = UserSettings(
        upstream = enumValue(prefs.getString(KEY_UPSTREAM, null), UpstreamDns.SYSTEM),
        customUpstream = prefs.getString(KEY_CUSTOM_UPSTREAM, "") ?: "",
        blockMode = enumValue(prefs.getString(KEY_BLOCK_MODE, null), BlockResponseMode.NXDOMAIN),
        blockAliases = prefs.getBoolean(KEY_BLOCK_ALIASES, false),
        ipv6Enabled = prefs.getBoolean(KEY_IPV6, true),
        autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, true),
        autoUpdateLists = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        queryLogEnabled = prefs.getBoolean(KEY_QUERY_LOG, true),
        useBuiltinList = prefs.getBoolean(KEY_BUILTIN_LIST, true),
        lastListUpdateAttempt = prefs.getLong(KEY_LAST_UPDATE, 0L),
    )

    private fun save(settings: UserSettings) {
        prefs.edit()
            .putString(KEY_UPSTREAM, settings.upstream.name)
            .putString(KEY_CUSTOM_UPSTREAM, settings.customUpstream)
            .putString(KEY_BLOCK_MODE, settings.blockMode.name)
            .putBoolean(KEY_BLOCK_ALIASES, settings.blockAliases)
            .putBoolean(KEY_IPV6, settings.ipv6Enabled)
            .putBoolean(KEY_AUTO_START, settings.autoStartOnBoot)
            .putBoolean(KEY_AUTO_UPDATE, settings.autoUpdateLists)
            .putBoolean(KEY_QUERY_LOG, settings.queryLogEnabled)
            .putBoolean(KEY_BUILTIN_LIST, settings.useBuiltinList)
            .putLong(KEY_LAST_UPDATE, settings.lastListUpdateAttempt)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T {
        if (name == null) return default
        return try {
            enumValueOf<T>(name)
        } catch (e: IllegalArgumentException) {
            default
        }
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_UPSTREAM = "upstream"
        const val KEY_CUSTOM_UPSTREAM = "custom_upstream"
        const val KEY_BLOCK_MODE = "block_mode"
        const val KEY_BLOCK_ALIASES = "block_aliases"
        const val KEY_IPV6 = "ipv6"
        const val KEY_AUTO_START = "auto_start"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_QUERY_LOG = "query_log"
        const val KEY_BUILTIN_LIST = "builtin_list"
        const val KEY_LAST_UPDATE = "last_list_update"
    }
}
