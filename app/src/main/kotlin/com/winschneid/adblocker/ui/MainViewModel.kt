package com.winschneid.adblocker.ui

import android.app.Application
import android.provider.Settings as SystemSettings
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winschneid.adblocker.Graph
import com.winschneid.adblocker.R
import com.winschneid.adblocker.core.filter.BlocklistSource
import com.winschneid.adblocker.data.UserSettings
import com.winschneid.adblocker.vpn.AdBlockVpnService
import com.winschneid.adblocker.vpn.QueryLogEntry
import com.winschneid.adblocker.vpn.VpnStateHolder
import com.winschneid.adblocker.vpn.VpnStats
import com.winschneid.adblocker.vpn.VpnStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A localizable one-off message for the snackbar. */
class UiMessage(@StringRes val resId: Int, val args: List<Any> = emptyList())

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val settings = Graph.settings
    private val blocklists = Graph.blocklists

    val vpnStatus: StateFlow<VpnStatus> = VpnStateHolder.status
    val vpnError: StateFlow<String?> = VpnStateHolder.error
    val stats: StateFlow<VpnStats> = VpnStateHolder.stats
    val userSettings: StateFlow<UserSettings> = settings.flow
    val sources: StateFlow<List<BlocklistSource>> = blocklists.sources
    val userAllow: StateFlow<Set<String>> = blocklists.userAllow
    val userDeny: StateFlow<Set<String>> = blocklists.userDeny
    val updating: StateFlow<Boolean> = blocklists.updating

    val blockedDomainCount: StateFlow<Int> = blocklists.matcher
        .map { it.blockedCount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, blocklists.matcher.value.blockedCount)

    val hasDownloadedLists: StateFlow<Boolean> = blocklists.sources
        .map { list -> list.any { it.enabled && it.lastUpdated > 0L } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, blocklists.hasDownloadedLists)

    val log: StateFlow<List<QueryLogEntry>> = VpnStateHolder.logVersion
        .map { VpnStateHolder.snapshotLog() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VpnStateHolder.snapshotLog())

    private val privateDnsModeState = MutableStateFlow(readPrivateDnsMode())
    /** "off", "opportunistic", "hostname" or null when unknown. */
    val privateDnsMode: StateFlow<String?> = privateDnsModeState.asStateFlow()

    private val messagesFlow = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<UiMessage> = messagesFlow.asSharedFlow()

    init {
        viewModelScope.launch { blocklists.autoUpdateIfDue() }
    }

    fun refreshEnvironment() {
        privateDnsModeState.value = readPrivateDnsMode()
    }

    fun onVpnPermissionGranted() {
        VpnStateHolder.clearError()
        AdBlockVpnService.start(app)
    }

    fun onVpnPermissionDenied() {
        messagesFlow.tryEmit(UiMessage(R.string.error_vpn_permission))
    }

    fun stopVpn() {
        AdBlockVpnService.stop(app)
    }

    fun setSourceEnabled(id: String, enabled: Boolean) {
        blocklists.setSourceEnabled(id, enabled)
    }

    fun updateAllLists() {
        viewModelScope.launch {
            val report = blocklists.updateAll()
            messagesFlow.tryEmit(UiMessage(R.string.lists_update_done, listOf(report.updated)))
            for ((name, error) in report.failures) {
                messagesFlow.tryEmit(UiMessage(R.string.lists_update_failed, listOf(name, error)))
            }
        }
    }

    fun updateList(id: String) {
        viewModelScope.launch {
            val error = blocklists.update(id)
            if (error != null) {
                val name = blocklists.sources.value.firstOrNull { it.id == id }?.name ?: id
                messagesFlow.tryEmit(UiMessage(R.string.lists_update_failed, listOf(name, error)))
            }
        }
    }

    /** Returns true when the source was accepted (the download starts right away). */
    fun addSource(name: String, url: String): Boolean {
        val source = blocklists.addSource(name, url)
        if (source == null) {
            messagesFlow.tryEmit(UiMessage(R.string.lists_invalid_url))
            return false
        }
        updateList(source.id)
        return true
    }

    fun removeSource(id: String) {
        blocklists.removeSource(id)
    }

    /** Returns true when the domain was valid and added. */
    fun addRule(domain: String, allow: Boolean): Boolean {
        val normalized = blocklists.addUserRule(domain, allow)
        if (normalized == null) {
            messagesFlow.tryEmit(UiMessage(R.string.rules_invalid_domain))
            return false
        }
        messagesFlow.tryEmit(UiMessage(R.string.rules_added, listOf(normalized)))
        return true
    }

    fun removeRule(domain: String, allow: Boolean) {
        blocklists.removeUserRule(domain, allow)
    }

    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        settings.update(transform)
    }

    fun clearLog() {
        VpnStateHolder.clearLog()
    }

    private fun readPrivateDnsMode(): String? = try {
        SystemSettings.Global.getString(app.contentResolver, "private_dns_mode")
    } catch (e: RuntimeException) {
        null
    }
}
