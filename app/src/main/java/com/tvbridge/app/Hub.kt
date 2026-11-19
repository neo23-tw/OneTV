package com.tvbridge.app

import android.content.Context

object Hub {
    @Volatile private var activityRef: java.lang.ref.WeakReference<MainActivity>? = null
    var activity: MainActivity?
        get() = activityRef?.get()
        set(value) { activityRef = value?.let { java.lang.ref.WeakReference(it) } }

    lateinit var live: LiveRepository
        private set

    @Synchronized fun start(context: Context) {
        try { LegacyTls.install(context) } catch (_: Throwable) {}
        val app = context.applicationContext
        if (!::live.isInitialized) {
            live = LiveRepository(app)
        }
    }

    @Synchronized fun stop() {
        // No-op or cleanup if needed
    }
}
