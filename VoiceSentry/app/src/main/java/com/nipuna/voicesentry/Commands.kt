package com.nipuna.voicesentry

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import kotlin.math.max

object Commands {
    data class Result(val label: String, val ok: Boolean)

    private var torchOn = false

    private fun has(r: String, pattern: String) = Regex(pattern).containsMatchIn(r)
    private fun wantsOff(r: String) = has(r, "\\b(off|disable|stop|close|kill)\\b")

    // "open" and the ways the speech model mishears it.
    private const val OPEN_WORDS =
        "open|opened|opens|opening|oven|owen|upon|opan|opun|launch|start|run"
    private val OPEN_RE = Regex("\\b($OPEN_WORDS)\\b")

    // Words ignored when working out the app name.
    private val STOP_WORDS = setOf(
        "the", "my", "app", "application", "please", "now", "a", "an", "to", "up", "and", "for", "me",
    )

    // Common mishearings from the speech model -> the word we really want.
    private val ALIASES = listOf(
        Regex("\\b(an|on|un|in) ?(look|luck|lok|lack|lark|lug)\\b") to "unlock",
        Regex("\\bunlocked\\b") to "unlock",
        Regex("\\b(a |the )?(look|luck|lok|lack|lark|lug|locked)\\b") to "lock",
        Regex("\\b(flash light|flashlite|flash lite)\\b") to "flashlight",
    )

    // Words the fuzzy matcher is allowed to snap to.
    private val KEYWORDS = listOf(
        "unlock", "lock", "wifi", "bluetooth", "torch", "flashlight", "screenshot",
        "mute", "home", "back", "recent", "pause", "resume", "next", "skip",
        "previous", "brighter", "dimmer", "louder", "quieter", "airplane", "brightness",
    )

    private fun fixHearing(r: String): String {
        var s = r
        for ((re, to) in ALIASES) s = s.replace(re, to)
        return s.split(" ").joinToString(" ") { w ->
            if (w.length < 4) w
            else {
                val k = KEYWORDS.minByOrNull { lev(w, it) }!!
                val tol = if (w.length >= 6) 2 else 1
                if (w != k && lev(w, k) <= tol) k else w
            }
        }
    }

    /** Runs a root script and reports the real result, not a guess. */
    private fun root(cmd: String, okLabel: String, timeoutMs: Long = 6000): Result {
        val (code, out) = RootShell.run(cmd, timeoutMs)
        return if (code == 0) Result(okLabel, true)
        else Result("Root failed ($code): ${out.take(80)}", false)
    }

    fun execute(ctx: Context, p: Prefs, raw: String, score: Float): Result {
        val r0 = norm(raw)
            .replace("wi fi", "wifi").replace("wife i", "wifi").replace("why fi", "wifi")
            .replace("blue tooth", "bluetooth")
        if (r0.isBlank()) return Result("Empty command", false)

        val wantsOpen = has(r0, OPEN_RE.pattern)
        // App names are left alone; everything else gets the mishearing fix.
        val r = if (wantsOpen) r0 else fixHearing(r0)

        return when {
            has(r, "\\b(unlock|un lock|open (my |the )?phone|wake up)\\b") -> unlock(ctx, p, score)

            wantsOpen -> openApp(ctx, r)

            has(r, "\\b(lock|block|sleep|screen off|turn off (the |my )?screen)\\b") ->
                root(
                    "input keyevent 223; sleep 0.4; " +
                        "if dumpsys power | grep -q 'mWakefulness=Awake'; then input keyevent 26; fi",
                    "Screen locked",
                )

            has(r, "\\bwifi\\b") -> {
                val off = wantsOff(r)
                root("svc wifi ${if (off) "disable" else "enable"}", "Wi-Fi ${if (off) "off" else "on"}")
            }

            has(r, "\\bbluetooth\\b") -> {
                val off = wantsOff(r)
                root("svc bluetooth ${if (off) "disable" else "enable"}", "Bluetooth ${if (off) "off" else "on"}")
            }

            has(r, "\\b(mobile data|data)\\b") -> {
                val off = wantsOff(r)
                root("svc data ${if (off) "disable" else "enable"}", "Mobile data ${if (off) "off" else "on"}")
            }

            has(r, "\\b(airplane|flight mode)\\b") -> {
                val off = wantsOff(r)
                root(
                    "cmd connectivity airplane-mode ${if (off) "disable" else "enable"}",
                    "Airplane mode ${if (off) "off" else "on"}",
                )
            }

            has(r, "\\b(torch|flashlight|flash light|flash)\\b") -> torch(ctx, !wantsOff(r))

            has(r, "\\b(volume up|louder|turn it up)\\b") ->
                root("input keyevent 24; input keyevent 24; input keyevent 24", "Volume up")
            has(r, "\\b(volume down|quieter|softer|turn it down)\\b") ->
                root("input keyevent 25; input keyevent 25; input keyevent 25", "Volume down")
            has(r, "\\bmute\\b") -> root("input keyevent 164", "Muted")

            has(r, "\\b(brighter|brightness up|increase brightness)\\b") -> brightness(+60)
            has(r, "\\b(dimmer|dim|brightness down|decrease brightness)\\b") -> brightness(-60)

            has(r, "\\bscreenshot\\b") -> root("input keyevent 120", "Screenshot taken")

            has(r, "\\b(go home|home)\\b") -> root("input keyevent 3", "Home")
            has(r, "\\b(go back|back)\\b") -> root("input keyevent 4", "Back")
            has(r, "\\b(recent|recents|overview)\\b") -> root("input keyevent 187", "Recent apps")

            has(r, "\\b(pause|play|resume)\\b") -> root("input keyevent 85", "Play / pause")
            has(r, "\\b(next|skip)\\b") -> root("input keyevent 87", "Next track")
            has(r, "\\b(previous|last song)\\b") -> root("input keyevent 88", "Previous track")

            has(r, "\\b(stop listening|go to sleep|shut down assistant)\\b") -> {
                ctx.startService(Intent(ctx, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
                Result("Stopped listening", true)
            }

            else -> Result("Unknown command: \"$r\"", false)
        }
    }

    private fun unlock(ctx: Context, p: Prefs, score: Float): Result {
        if (p.strictUnlock && score < p.threshold + 0.08f) {
            return Result("Unlock refused: voice match too weak", false)
        }
        val pin = p.pin.filter { it.isLetterOrDigit() }
        if (pin.isEmpty()) return Result("Set your PIN in the app first", false)
        val dm = ctx.resources.displayMetrics
        val x = dm.widthPixels / 2
        val y1 = (dm.heightPixels * 0.85).toInt()
        val y2 = (dm.heightPixels * 0.25).toInt()
        return root(
            "input keyevent 224; sleep 0.5; input swipe $x $y1 $x $y2 250; sleep 0.7; " +
                "input text $pin; sleep 0.2; input keyevent 66",
            "Phone unlocked",
            timeoutMs = 9000,
        )
    }

    /** Similarity of two names, ignoring spaces ("tik tok" == "tiktok"). */
    private fun sim(a: String, q: String): Float {
        val x = a.replace(" ", "")
        val y = q.replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return 0f
        if (x == y) return 1f
        if (x.length >= 3 && y.length >= 3) {
            if (x.startsWith(y) || y.startsWith(x)) return 0.9f
            if (x.contains(y)) return 0.8f
        }
        return 1f - lev(x, y).toFloat() / max(x.length, y.length)
    }

    private fun openApp(ctx: Context, sentence: String): Result {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).map {
            Triple(norm(it.loadLabel(pm).toString()), it.activityInfo.packageName, it.activityInfo.name)
        }

        // Everything except the "open" word and filler is the app name, wherever it sits in the sentence.
        val rest = sentence.replace(OPEN_RE, " ")
            .replace(Regex("\\b(tick tock|tik tok|tic toc|tick tok)\\b"), "tiktok")
            .split(" ").filter { it.isNotEmpty() && it !in STOP_WORDS }
        if (rest.isEmpty()) return Result("Which app?", false)

        // Try every 1 to 3 word phrase from what was said against every app name.
        val phrases = mutableListOf<String>()
        for (i in rest.indices) {
            for (len in 1..3) {
                if (i + len <= rest.size) phrases.add(rest.subList(i, i + len).joinToString(" "))
            }
        }

        var best: Triple<String, String, String>? = null
        var bs = 0f
        for (a in apps) {
            var s = 0f
            for (ph in phrases) s = max(s, sim(a.first, ph))
            // The whole app name spelled out somewhere in the sentence counts as a strong match.
            val cond = a.first.replace(" ", "")
            if (cond.length >= 3 && rest.joinToString("").contains(cond)) s = max(s, 0.95f)
            if (s > bs) { bs = s; best = a }
        }
        val b = best
        if (b == null || bs < 0.62f) return Result("No app matching \"${rest.joinToString(" ")}\"", false)

        val (code, out) = RootShell.run("am start --user 0 -n '${b.second}/${b.third}'")
        if (code == 0 && !out.contains("Error", ignoreCase = true)) return Result("Opened ${b.first}", true)

        val (code2, out2) = RootShell.run("monkey -p ${b.second} -c android.intent.category.LAUNCHER 1")
        return if (code2 == 0) Result("Opened ${b.first}", true)
        else Result("Open failed ($code2): ${(out2.ifEmpty { out }).take(80)}", false)
    }

    private fun torch(ctx: Context, on: Boolean): Result {
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Result("No flashlight found", false)
            cm.setTorchMode(id, on)
            torchOn = on
            Result("Flashlight ${if (on) "on" else "off"}", true)
        } catch (e: Exception) {
            Result("Flashlight error", false)
        }
    }

    private fun brightness(delta: Int): Result {
        val cur = RootShell.run("settings get system screen_brightness").second.toIntOrNull() ?: 128
        val next = (cur + delta).coerceIn(8, 255)
        return root(
            "settings put system screen_brightness_mode 0; settings put system screen_brightness $next",
            "Brightness ${next * 100 / 255}%",
        )
    }
}
