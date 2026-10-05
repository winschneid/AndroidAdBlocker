package com.winschneid.adblocker

import android.content.Context
import com.winschneid.adblocker.data.AppSettings
import com.winschneid.adblocker.data.BlocklistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny service locator for the app-wide singletons (no DI framework needed for an app this size). */
object Graph {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var initialized = false

    lateinit var settings: AppSettings
        private set

    lateinit var blocklists: BlocklistRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            settings = AppSettings(app)
            blocklists = BlocklistRepository(app, settings, scope)
            initialized = true
        }
    }
}
