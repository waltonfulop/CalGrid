package com.calgrid.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Runs [block] off the main thread while keeping the broadcast alive. */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/** Boot, app update, clock / time zone / locale changes. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        runAsync {
            SyncScheduler.ensureScheduled(context)
            WidgetUpdater.updateAll(context)
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
        )
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync {
            if (intent.action == ACTION_MIDNIGHT) SyncScheduler.scheduleMidnight(context)
            WidgetUpdater.updateAll(context)
        }
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.calgrid.action.MIDNIGHT"
        const val ACTION_BOUNDARY = "com.calgrid.action.BOUNDARY"
    }
}
