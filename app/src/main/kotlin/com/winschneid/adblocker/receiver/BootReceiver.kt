package com.winschneid.adblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.winschneid.adblocker.Graph
import com.winschneid.adblocker.vpn.AdBlockVpnService

/** Restores the VPN after a reboot or an app update when the user opted in and consent is still valid. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Graph.init(context)
        if (!Graph.settings.current.autoStartOnBoot) return
        if (VpnService.prepare(context) != null) return // consent missing: the user must open the app
        AdBlockVpnService.start(context)
    }
}
