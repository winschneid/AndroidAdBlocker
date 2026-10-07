package com.winschneid.adblocker.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.winschneid.adblocker.Graph
import com.winschneid.adblocker.R
import com.winschneid.adblocker.ui.MainActivity
import com.winschneid.adblocker.vpn.AdBlockVpnService
import com.winschneid.adblocker.vpn.VpnStateHolder
import com.winschneid.adblocker.vpn.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that toggles the ad blocker. */
class AdBlockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusUpdates: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        Graph.init(this)
        // Starting and stopping the VPN is asynchronous, so the state right after a tap is still the old one.
        // Follow the status for as long as the tile is visible (the first emission is the current state).
        statusUpdates?.cancel()
        statusUpdates = scope.launch { VpnStateHolder.status.collect { refresh() } }
    }

    override fun onStopListening() {
        statusUpdates?.cancel()
        statusUpdates = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStateHolder.status.value) {
            VpnStatus.RUNNING, VpnStatus.STARTING -> AdBlockVpnService.stop(this)
            VpnStatus.STOPPED -> {
                if (VpnService.prepare(this) == null) {
                    AdBlockVpnService.start(this)
                } else {
                    openApp()
                }
            }
        }
        refresh()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val status = VpnStateHolder.status.value
        tile.state = when (status) {
            VpnStatus.RUNNING -> Tile.STATE_ACTIVE
            VpnStatus.STARTING -> Tile.STATE_UNAVAILABLE
            VpnStatus.STOPPED -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (status == VpnStatus.RUNNING) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, AdBlockTileService::class.java))
            } catch (e: RuntimeException) {
                // The tile is not added to Quick Settings; nothing to refresh.
            }
        }
    }
}
