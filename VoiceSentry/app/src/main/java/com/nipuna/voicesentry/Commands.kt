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

    fun execute(ctx: Context, p: Prefs, raw: String, score: Float): Result {
        val r = norm(raw)
            .replace("wi fi", "wifi").replace("wife i", "wifi").replace("why fi", "wifi")
            .replace("blue tooth", "bluetooth")
        if (r.isBlank()) return Result("Empty command", false)

        return when {
            has(r, "\\b(unlock|un lock|open (my |the )?phone|wake up)\\b") -> unlock(ctx, p, score)

            has(r, "^(open|launch|start|run) ") ->
                openApp(ctx, r.replaceFirst(Regex("^(open|launch|start|run) "), ""))

            has(r, "\\b(lock|block|sleep|screen off|turn off (the |my )?screen)\\b") -> {
                RootShell.run("input keyevent 223")
                Result("Screen locked", true)
            }

            has(r, "\\bwifi\\b") -> {
                val off = wantsOff(r)
                RootShell.run("svc wifi ${if (off) "disable" else "enable"}")
                Result("Wi-Fi ${if (off) "off" else "on"}", true)
            }

            has(r, "\\bbluetooth\\b") -> {
                val off = wantsOff(r)
                RootShell.run("svc bluetooth ${if (off) "disable" else "enable"}")
                Result("Bluetooth ${if (off) "off" else "on"}", true)
            }

            has(r, "\\b(mobile data|data)\\b") -> {
                val off = wantsOff(r)
                RootShell.run("svc data ${if (off) "disable" else "enable"}")
                Result("Mobile data ${if (off) "off" else "on"}", true)
            }

            has(r, "\\b(airplane|flight mode)\\b") -> {
                val off = wantsOff(r)
                RootShell.run("cmd connectivity airplane-mode ${if (off) "disable" else "enable"}")
                Result("Airplane mode ${if (off) "off" else "on"}", true)
            }

            has(r, "\\b(torch|flashlight|flash light|flash)\\b") -> torch(ctx, !wantsOff(r))

            has(r, "\\b(volume up|louder|turn it up)\\b") -> {
                repeat(3) { RootShell.run("input keyevent 24") }
                Result("Volume up", true)
            }
            has(r, "\\b(volume down|quieter|softer|turn it down)\\b") -> {
                repeat(3) { RootShell.run("input keyevent 25") }
                Result("Volume down", true)
            }
            has(r, "\\bmute\\b") -> {
                RootShell.run("input keyevent 164")
                Result("Muted", true)
            }

            has(r, "\\b(brighter|brightness up|increase brightness)\\b") -> brightness(+60)
            has(r, "\\b(dimmer|dim|brightness down|decrease brightness)\\b") -> brightness(-60)

            has(r, "\\bscreenshot\\b") -> {
                RootShell.run("input keyevent 120")
                Result("Screenshot taken", true)
            }

            has(r, "\\b(go home|home)\\b") -> {
                RootShell.run("input keyevent 3")
                Result("Home", true)
            }
            has(r, "\\b(go back|back)\\b") -> {
                RootShell.run("input keyevent 4")
                Result("Back", true)
            }
            has(r, "\\b(recent|recents|overview)\\b") -> {
                RootShell.run("input keyevent 187")
                Result("Recent apps", true)
            }

            has(r, "\\b(pause|play|resume)\\b") -> {
                RootShell.run("input keyevent 85")
                Result("Play / pause", true)
            }
            has(r, "\\b(next|skip)\\b") -> {
                RootShell.run("input keyevent 87")
                Result("Next track", true)
            }
            has(r, "\\b(previous|last song)\\b") -> {
                RootShell.run("input keyevent 88")
                Result("Previous track", true)
            }

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
        RootShell.run(
            "input keyevent 224; sleep 0.5; input swipe $x $y1 $x $y2 250; sleep 0.7; " +
                "input text $pin; sleep 0.2; input keyevent 66",
            timeoutMs = 9000,
        )
        return Result("Phone unlocked", true)
    }

    private fun sim(a: String, q: String): Float {
        if (a == q) return 1f
        if (q.length >= 3 && (a.startsWith(q) || q.startsWith(a))) return 0.9f
        if (q.length >= 3 && a.contains(q)) return 0.8f
        return 1f - lev(a, q).toFloat() / max(a.length, q.length).coerceAtLeast(1)
    }

    private fun openApp(ctx: Context, name: String): Result {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { Pair(norm(it.loadLabel(pm).toString()), it.activityInfo.packageName) }
        val q = norm(name).removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        if (q.isEmpty()) return Result("Which app?", false)
        var best: Pair<String, String>? = null
        var bs = 0f
        for (a in apps) {
            val s = sim(a.first, q)
            if (s > bs) { bs = s; best = a }
        }
        val b = best
        if (b == null || bs < 0.6f) return Result("No app matching \"$q\"", false)
        RootShell.run("monkey -p ${b.second} -c android.intent.category.LAUNCHER 1")
        return Result("Opened ${b.first}", true)
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
        RootShell.run("settings put system screen_brightness_mode 0; settings put system screen_brightness $next")
        return Result("Brightness ${next * 100 / 255}%", true)
    }
}
