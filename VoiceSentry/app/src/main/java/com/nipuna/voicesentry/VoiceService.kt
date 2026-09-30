package com.nipuna.voicesentry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioRecord
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class VoiceService : Service() {

    companion object {
        const val ACTION_STOP = "com.nipuna.voicesentry.STOP"
        private const val CHANNEL = "sentry"

        /** The voice match is never allowed below this, whatever the slider says. */
        private const val MIN_OWNER_MATCH = 0.58f

        /** A command after the wake word can be at most this many words. */
        private const val MAX_COMMAND_WORDS = 8
    }

    @Volatile private var active = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY

        createChannel()
        ServiceCompat.startForeground(this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        active = true
        VoiceState.running.value = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sentry:mic").also { it.acquire() }

        worker = Thread {
            try {
                loop()
            } catch (e: Throwable) {
                VoiceState.add("engine", "Error: ${e.message}", 0f, false)
            } finally {
                if (active) stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        try { worker?.join(900) } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        VoiceState.running.value = false
        VoiceState.status.value = "Stopped"
        VoiceState.level.value = 0f
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Voice Sentry", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, VoiceService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice Sentry is listening")
            .setContentText("Only your voice can give commands")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buzz(ms: Long) {
        try {
            getSystemService(Vibrator::class.java)
                .vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun loop() {
        val prefs = Prefs(this)
        val ref = prefs.voiceprint
        if (ref == null) {
            VoiceState.add("setup", "Record your voice first", 0f, false)
            return
        }

        VoiceState.status.value = "Loading models…"
        RootShell.run(
            "dumpsys deviceidle whitelist +$packageName; " +
                "appops set $packageName RUN_ANY_IN_BACKGROUND allow; " +
                "appops set $packageName RUN_IN_BACKGROUND allow",
        )

        val vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.45f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 8f,
                ),
                sampleRate = Audio.SR,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        val spk = SpeakerModel(this)
        val asr = AsrModel(this)

        val rec: AudioRecord = Audio.newRecord(4f)
        val shorts = ShortArray(512)
        val pre = ArrayDeque<FloatArray>()
        var floor = 0.004f
        var awakeUntil = 0L
        var frameNo = 0
        val rejects = ArrayDeque<Long>()
        var backoffUntil = 0L

        try {
            rec.startRecording()
            VoiceState.status.value = "Listening"

            while (active) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n <= 0) continue
                val f = FloatArray(n)
                var e = 0.0
                for (i in 0 until n) {
                    f[i] = shorts[i] / 32768f
                    e += (f[i] * f[i]).toDouble()
                }
                val rms = sqrt(e / n).toFloat()
                if (++frameNo % 3 == 0) VoiceState.level.value = rms

                // Cheap energy gate: the heavy VAD model only runs when something loud enough happens.
                val inSpeech = vad.isSpeechDetected() || !vad.empty()
                if (!inSpeech) {
                    // Gate follows the room noise but is capped, so speech in a noisy place is never blocked.
                    val gate = (floor * 2.2f).coerceIn(0.008f, 0.02f)
                    if (rms < gate) {
                        floor = floor * 0.995f + rms * 0.005f
                        pre.addLast(f)
                        if (pre.size > 10) pre.removeFirst()
                        continue
                    }
                    // Loud but not (yet) speech: let the noise floor creep up slowly.
                    floor = floor * 0.999f + rms * 0.001f
                    for (p in pre) vad.acceptWaveform(p)
                    pre.clear()
                }
                vad.acceptWaveform(f)

                while (!vad.empty()) {
                    val seg = vad.front().samples
                    vad.pop()
                    if (seg.size < 6400) continue

                    // Battery saver: after many rejected voices in a row (TV, crowd), only look at clearly close/loud speech.
                    val t0 = System.currentTimeMillis()
                    if (t0 < backoffUntil) {
                        var se = 0.0
                        for (x in seg) se += (x * x).toDouble()
                        if (sqrt(se / seg.size) < 0.03) {
                            VoiceState.status.value = "Listening"
                            continue
                        }
                    }

                    // 1) Whose voice is it? Anyone but the owner is dropped silently.
                    //    The bar is the slider value but never below MIN_OWNER_MATCH, and noise never lowers it.
                    VoiceState.status.value = "Verifying voice…"
                    val score = cosine(spk.embed(seg), ref)
                    val bar = max(prefs.threshold, MIN_OWNER_MATCH)
                    if (score < bar) {
                        rejects.addLast(t0)
                        while (rejects.isNotEmpty() && t0 - rejects.first() > 30000) rejects.removeFirst()
                        if (rejects.size >= 6) backoffUntil = t0 + 20000
                        VoiceState.add("(speech)", if (floor > 0.012f) "Noisy room / other voice" else "Other voice ignored", score, false)
                        VoiceState.status.value = "Listening"
                        continue
                    }

                    // 2) What did the owner say? (heavier, only for the owner)
                    VoiceState.status.value = "Understanding…"
                    val text = asr.transcribe(seg)
                    val t = norm(text)
                    if (t.isEmpty()) {
                        VoiceState.status.value = "Listening"
                        continue
                    }

                    // 3) Wake word (must be at the start of the sentence)
                    var rest: String? = t
                    if (prefs.requireWake) {
                        rest = null
                        // Several spellings allowed, separated by commas: "leo, neil, lio"
                        val wakes = prefs.wakeWord.split(",").map { norm(it) }.filter { it.isNotEmpty() }
                        val now = System.currentTimeMillis()
                        val tail = findWake(t, wakes)?.trim()
                        if (tail != null) {
                            if (tail.isEmpty()) {
                                awakeUntil = now + 6000
                                buzz(30)
                                VoiceState.add(text, "Yes? (say your command)", score, true)
                            } else {
                                rest = tail
                            }
                        } else if (now < awakeUntil) {
                            rest = t
                            awakeUntil = 0
                        } else {
                            VoiceState.add(text, "No wake word", score, false)
                        }
                    }

                    // 4) A command is short. Long sentences are ordinary talk, not commands.
                    if (rest != null && rest.split(" ").size > MAX_COMMAND_WORDS) {
                        VoiceState.add(text, "Too long, not a command", score, false)
                        rest = null
                    }

                    if (rest != null) {
                        val res = Commands.execute(this, prefs, rest, score)
                        buzz(if (res.ok) 45 else 120)
                        VoiceState.add(text, res.label, score, res.ok)
                    }
                    VoiceState.status.value = "Listening"
                }
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            spk.release()
            asr.release()
            try { vad.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Returns the text after the wake word, or null if the wake word was not heard.
     * The wake word must be within the first 3 words of the sentence.
     */
    private fun findWake(t: String, wakes: List<String>): String? {
        if (wakes.isEmpty()) return t
        val words = t.split(" ").filter { it.isNotEmpty() }

        // Exact match first (multi-word wake words are fine here).
        for (w in wakes) {
            val ww = w.split(" ")
            for (i in 0..min(2, words.size - ww.size)) {
                if (words.subList(i, i + ww.size) == ww) {
                    return words.drop(i + ww.size).joinToString(" ")
                }
            }
        }

        // Then a close match for single-word wake words (small mishearings).
        for (w in wakes) {
            if (w.contains(' ')) continue
            val tol = if (w.length >= 6) 2 else 1
            for (i in 0 until min(3, words.size)) {
                if (lev(words[i], w) <= tol) return words.drop(i + 1).joinToString(" ")
            }
        }
        return null
    }
}
