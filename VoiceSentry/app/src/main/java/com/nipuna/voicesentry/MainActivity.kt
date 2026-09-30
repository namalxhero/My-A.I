package com.nipuna.voicesentry

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.getBooleanExtra("autostart", false) == true && Prefs(this).voiceprint != null) {
            ContextCompat.startForegroundService(this, Intent(this, VoiceService::class.java))
            window.decorView.postDelayed({ finish() }, 1500)
        }

        setContent { SentryApp() }
    }
}
