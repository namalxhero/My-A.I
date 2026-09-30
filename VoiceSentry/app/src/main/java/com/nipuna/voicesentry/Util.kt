package com.nipuna.voicesentry

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object RootShell {
    /** Runs a command as root. Returns (exitCode, output). */
    fun run(cmd: String, timeoutMs: Long = 5000): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                Pair(-1, out.trim())
            } else {
                Pair(p.exitValue(), out.trim())
            }
        } catch (e: Exception) {
            Pair(-1, e.message ?: "su failed")
        }
    }

    /** Starts a root command and does not wait for it. */
    fun fire(cmd: String) {
        try {
            ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        } catch (_: Exception) {
        }
    }
}

fun normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += (x * x).toDouble()
    val n = max(sqrt(s).toFloat(), 1e-9f)
    return FloatArray(v.size) { v[it] / n }
}

/** Cosine similarity of two already-normalized vectors. */
fun cosine(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    val n = min(a.size, b.size)
    for (i in 0 until n) s += a[i] * b[i]
    return s
}

fun norm(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

fun lev(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

object Audio {
    const val SR = 16000

    private val effects = mutableListOf<AudioEffect>()

    @SuppressLint("MissingPermission")
    fun newRecord(seconds: Float = 4f): AudioRecord {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = max(min, (SR * 2 * seconds).toInt())
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bytes,
        )
        // Hardware noise suppression (when the phone has it) helps a lot in noisy places.
        try {
            effects.forEach { try { it.release() } catch (_: Exception) {} }
            effects.clear()
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) }
            }
        } catch (_: Exception) {}
        return rec
    }

    /** Records a fixed number of seconds (blocking). */
    fun record(seconds: Float, onLevel: (Float) -> Unit): FloatArray {
        val total = (SR * seconds).toInt()
        val out = FloatArray(total)
        val rec = newRecord()
        val buf = ShortArray(512)
        var got = 0
        try {
            rec.startRecording()
            while (got < total) {
                val n = rec.read(buf, 0, min(buf.size, total - got))
                if (n <= 0) continue
                var e = 0.0
                for (i in 0 until n) {
                    val f = buf[i] / 32768f
                    out[got + i] = f
                    e += (f * f).toDouble()
                }
                got += n
                onLevel(sqrt(e / n).toFloat())
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
        return out
    }
}

object Enroll {
    /** Builds an averaged voiceprint. Returns (voiceprint, quality 0..1) or null if the samples were unusable. */
    fun build(ctx: Context, samples: List<FloatArray>): Pair<FloatArray, Float>? {
        val sm = SpeakerModel(ctx)
        try {
            val usable = samples.filter { s ->
                var e = 0.0
                for (x in s) e += (x * x).toDouble()
                sqrt(e / max(1, s.size)) > 0.004
            }
            val embs = usable.map { sm.embed(it) }
            if (embs.size < 3) return null
            val dim = embs[0].size
            val c = FloatArray(dim)
            for (e in embs) for (i in 0 until dim) c[i] += e[i]
            val cn = normalize(c)
            val q = embs.map { cosine(it, cn) }.average().toFloat()
            return Pair(cn, q)
        } finally {
            sm.release()
        }
    }
}
