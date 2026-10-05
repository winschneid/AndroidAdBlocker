package com.winschneid.adblocker.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.winschneid.adblocker.Graph
import com.winschneid.adblocker.R
import com.winschneid.adblocker.core.engine.DnsProxyEngine
import com.winschneid.adblocker.data.UpstreamDns
import com.winschneid.adblocker.data.UserSettings
import com.winschneid.adblocker.tile.AdBlockTileService
import com.winschneid.adblocker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * A DNS-only VPN: the TUN interface routes nothing but our fake resolver address, so every DNS query of
 * every app lands here while all other traffic keeps flowing directly over the real network.
 */
class AdBlockVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observers: Job? = null
    private var session: VpnSession? = null
    private var sessionIpv6 = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var systemDnsServers: List<InetAddress> = emptyList()

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }
            ACTION_RESTART -> {
                restartVpn()
                START_STICKY
            }
            else -> {
                startVpn()
                START_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by the system or another VPN app")
        stopVpn()
    }

    override fun onDestroy() {
        observers?.cancel()
        session?.close()
        session = null
        unregisterNetworkCallback()
        serviceScope.cancel()
        if (VpnStateHolder.status.value != VpnStatus.STOPPED) VpnStateHolder.setStatus(VpnStatus.STOPPED)
        AdBlockTileService.requestUpdate(this)
        super.onDestroy()
    }

    private fun startVpn() {
        if (session != null) {
            refreshNotification()
            return
        }
        VpnStateHolder.setStatus(VpnStatus.STARTING)
        startForegroundCompat(NotificationHelper.buildStatusNotification(this, VpnStateHolder.stats.value, running = false))

        if (prepare(this) != null) {
            fail(getString(R.string.error_vpn_permission))
            return
        }
        val settings = Graph.settings.current
        val tun: ParcelFileDescriptor = try {
            establishTun(settings) ?: throw IllegalStateException("establish() returned null")
        } catch (e: Exception) {
            Log.e(TAG, "Could not establish the VPN interface", e)
            fail(e.message ?: e.javaClass.simpleName)
            return
        }

        val engine = DnsProxyEngine(Graph.blocklists.matcher.value, settings.blockMode)
        val newSession = try {
            VpnSession(this, tun, engine, upstreamsFor(settings), settings.queryLogEnabled)
        } catch (e: IOException) {
            Log.e(TAG, "Could not create the VPN session", e)
            tun.close()
            fail(e.message ?: e.javaClass.simpleName)
            return
        }
        session = newSession
        sessionIpv6 = settings.ipv6Enabled
        VpnStateHolder.markStarted()
        newSession.start()
        VpnStateHolder.setStatus(VpnStatus.RUNNING)
        startObservers(engine, newSession)
        refreshNotification()
        AdBlockTileService.requestUpdate(this)
        serviceScope.launch { Graph.blocklists.autoUpdateIfDue() }
        Log.i(TAG, "VPN started; upstreams=${newSession.upstreams}")
    }

    private fun startObservers(engine: DnsProxyEngine, activeSession: VpnSession) {
        observers?.cancel()
        observers = serviceScope.launch {
            launch {
                Graph.blocklists.matcher.collect { engine.matcher = it }
            }
            launch {
                Graph.settings.flow.collect { settings ->
                    engine.blockMode = settings.blockMode
                    activeSession.upstreams = upstreamsFor(settings)
                    activeSession.logEnabled = settings.queryLogEnabled
                    if (settings.ipv6Enabled != sessionIpv6 && session === activeSession) {
                        Log.i(TAG, "IPv6 setting changed; restarting the VPN")
                        startService(Intent(this@AdBlockVpnService, AdBlockVpnService::class.java).setAction(ACTION_RESTART))
                    }
                }
            }
            launch {
                var lastShown = VpnStateHolder.stats.value
                while (isActive) {
                    delay(NOTIFICATION_REFRESH_MILLIS)
                    val current = VpnStateHolder.stats.value
                    if (current != lastShown) {
                        lastShown = current
                        refreshNotification()
                    }
                }
            }
        }
    }

    private fun restartVpn() {
        observers?.cancel()
        observers = null
        session?.close()
        session = null
        startVpn()
    }

    private fun stopVpn() {
        observers?.cancel()
        observers = null
        session?.close()
        session = null
        VpnStateHolder.setStatus(VpnStatus.STOPPED)
        AdBlockTileService.requestUpdate(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        VpnStateHolder.setError(message)
        AdBlockTileService.requestUpdate(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun establishTun(settings: UserSettings): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(MTU)
            .addAddress(VPN4_ADDRESS, 24)
            .addDnsServer(DNS4_ADDRESS)
            .addRoute(DNS4_ADDRESS, 32)
            .setBlocking(true)
        if (settings.ipv6Enabled) {
            builder.addAddress(VPN6_ADDRESS, 120)
                .addDnsServer(DNS6_ADDRESS)
                .addRoute(DNS6_ADDRESS, 128)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        val configure = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        builder.setConfigureIntent(configure)
        return builder.establish()
    }

    private fun upstreamsFor(settings: UserSettings): List<InetSocketAddress> {
        val addresses: List<InetAddress> = when (settings.upstream) {
            UpstreamDns.SYSTEM -> systemDnsServers.filterNot(::isOurAddress).ifEmpty { parseAddresses(UpstreamDns.CLOUDFLARE.addresses) }
            UpstreamDns.CUSTOM -> parseAddresses(settings.customUpstream.split(',', ' ', ';', '\n')).ifEmpty { parseAddresses(UpstreamDns.CLOUDFLARE.addresses) }
            else -> parseAddresses(settings.upstream.addresses)
        }
        return addresses.map { InetSocketAddress(it, DNS_PORT) }
    }

    private fun isOurAddress(address: InetAddress): Boolean {
        val host = address.hostAddress ?: return false
        return host == DNS4_ADDRESS || host == DNS6_ADDRESS || host == VPN4_ADDRESS || host == VPN6_ADDRESS
    }

    private fun parseAddresses(values: List<String>): List<InetAddress> = values.mapNotNull { parseAddress(it) }

    /** Accepts only numeric IP literals so no name lookup is ever triggered here. */
    private fun parseAddress(value: String): InetAddress? {
        val text = value.trim()
        if (text.isEmpty()) return null
        val numeric = text.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
        if (!numeric || (!text.contains('.') && !text.contains(':'))) return null
        return try {
            InetAddress.getByName(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        manager.activeNetwork?.let { network ->
            manager.getLinkProperties(network)?.dnsServers?.let { systemDnsServers = it }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                systemDnsServers = linkProperties.dnsServers
                session?.upstreams = upstreamsFor(Graph.settings.current)
            }
        }
        try {
            manager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not unregister network callback", e)
        }
    }

    private fun refreshNotification() {
        val running = session != null
        NotificationHelper.update(this, NotificationHelper.buildStatusNotification(this, VpnStateHolder.stats.value, running))
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AdBlockVpnService"
        const val ACTION_START = "com.winschneid.adblocker.action.START"
        const val ACTION_STOP = "com.winschneid.adblocker.action.STOP"
        const val ACTION_RESTART = "com.winschneid.adblocker.action.RESTART"

        private const val MTU = 1500
        private const val DNS_PORT = 53
        private const val VPN4_ADDRESS = "10.111.222.1"
        private const val DNS4_ADDRESS = "10.111.222.2"
        private const val VPN6_ADDRESS = "fd00:ad:b10c::1"
        private const val DNS6_ADDRESS = "fd00:ad:b10c::2"
        private const val NOTIFICATION_REFRESH_MILLIS = 10_000L

        /** Starts the VPN. The caller must have obtained VPN consent via [VpnService.prepare] first. */
        fun start(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdBlockVpnService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Not running and we are in the background: nothing to stop.
                Log.w(TAG, "stop() ignored: ${e.message}")
            }
        }
    }
}
