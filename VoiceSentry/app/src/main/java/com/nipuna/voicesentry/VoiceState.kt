package com.nipuna.voicesentry

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: String,
    val heard: String,
    val result: String,
    val score: Float,
    val ok: Boolean,
)

object VoiceState {
    val running = MutableStateFlow(false)
    val status = MutableStateFlow("Stopped")
    val level = MutableStateFlow(0f)
    val log = MutableStateFlow<List<LogEntry>>(emptyList())

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(heard: String, result: String, score: Float, ok: Boolean) {
        val e = LogEntry(fmt.format(Date()), heard, result, score, ok)
        log.value = (listOf(e) + log.value).take(30)
    }
}
