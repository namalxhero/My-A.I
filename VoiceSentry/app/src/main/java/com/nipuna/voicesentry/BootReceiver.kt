package com.nipuna.voicesentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val p = Prefs(context)
        if (!p.autoBoot || p.voiceprint == null) return
        // Root can open the app from the background; the app then starts the mic service while visible.
        RootShell.fire(
            "nohup sh -c 'sleep 20; am start -n ${context.packageName}/.MainActivity --ez autostart true' " +
                ">/dev/null 2>&1 &",
        )
    }
}
