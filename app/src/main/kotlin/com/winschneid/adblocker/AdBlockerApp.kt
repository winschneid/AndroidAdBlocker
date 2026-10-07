package com.winschneid.adblocker

import android.app.Application
import com.winschneid.adblocker.vpn.NotificationHelper

class AdBlockerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        NotificationHelper.createChannels(this)
    }
}
