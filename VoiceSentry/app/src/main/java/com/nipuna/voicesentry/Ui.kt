package com.nipuna.voicesentry

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg1 = Color(0xFF070B14)
private val Bg2 = Color(0xFF131A33)
private val Accent = Color(0xFF5EEAD4)
private val Violet = Color(0xFFA78BFA)
private val Ok = Color(0xFF34D399)
private val Bad = Color(0xFFF87171)
private val Dim = Color(0xFF94A3B8)

@Composable
fun SentryApp() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    val running by VoiceState.running.collectAsStateWithLifecycle()
    val status by VoiceState.status.collectAsStateWithLifecycle()
    val level by VoiceState.level.collectAsStateWithLifecycle()
    val log by VoiceState.log.collectAsStateWithLifecycle()

    fun micGranted() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    var hasMic by remember { mutableStateOf(micGranted()) }
    var enrolled by remember { mutableStateOf(prefs.voiceprint != null) }
    var rootOk by remember { mutableStateOf<Boolean?>(null) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMic = micGranted()
    }
    fun askPerms() {
        permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }

    LaunchedEffect(Unit) {
        rootOk = withContext(Dispatchers.IO) { RootShell.run("id -u").second.trim() == "0" }
    }

    fun toggle() {
        when {
            !hasMic -> askPerms()
            running -> ctx.startService(Intent(ctx, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
            !enrolled -> Toast.makeText(ctx, "Record your voice first", Toast.LENGTH_SHORT).show()
            else -> ContextCompat.startForegroundService(ctx, Intent(ctx, VoiceService::class.java))
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, secondary = Violet, background = Bg1, surface = Bg2,
            onSurface = Color.White, onBackground = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg1, Bg2)))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice Sentry", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Only your voice gives orders", fontSize = 13.sp, color = Dim)
                    }
                    RootPill(rootOk)
                }

                Orb(active = running, level = level, status = if (running) status else "Tap to start", onClick = { toggle() })

                if (!hasMic) {
                    Button(onClick = { askPerms() }, colors = ButtonDefaults.buttonColors(containerColor = Violet)) {
                        Text("Allow microphone")
                    }
                }

                EnrollCard(prefs, ctx, hasMic, enrolled, onNeedMic = { askPerms() }, onEnrolled = { enrolled = true })
                SecurityCard(prefs)
                CommandsCard(prefs)
                LogCard(log)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RootPill(ok: Boolean?) {
    val (txt, col) = when (ok) {
        true -> "Root ✓" to Ok
        false -> "No root" to Bad
        null -> "Checking…" to Dim
    }
    Row(
        Modifier
            .background(col.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(col, CircleShape))
        Spacer(Modifier.size(6.dp))
        Text(txt, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Orb(active: Boolean, level: Float, status: String, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val pulse by t.animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
    )
    val lv by animateFloatAsState((level * 9f).coerceIn(0f, 1f), label = "lv")
    val c1 = if (active) Accent else Color(0xFF475569)
    val c2 = if (active) Violet else Color(0xFF334155)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(250.dp)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val base = size.minDimension / 2
                if (active) {
                    for (i in 0..2) {
                        val p = (pulse + i / 3f) % 1f
                        drawCircle(
                            color = c1.copy(alpha = (1f - p) * 0.35f),
                            radius = base * (0.55f + 0.45f * p),
                            center = c,
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                }
                val r = base * 0.46f * (1f + lv * 0.30f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(c1.copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 1.7f),
                    radius = r * 1.7f, center = c,
                )
                drawCircle(
                    brush = Brush.linearGradient(listOf(c1, c2), start = Offset(c.x - r, c.y - r), end = Offset(c.x + r, c.y + r)),
                    radius = r, center = c,
                )
            }
            Text(if (active) "ON" else "START", color = Bg1, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        }
        Text(status, color = if (active) Accent else Dim, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GlassCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x14FFFFFF), RoundedCornerShape(24.dp))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun EnrollCard(
    prefs: Prefs, ctx: Context, hasMic: Boolean, enrolled: Boolean,
    onNeedMic: () -> Unit, onEnrolled: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(-1) }
    var lvl by remember { mutableFloatStateOf(0f) }
    var msg by remember { mutableStateOf(if (enrolled) "Your voice is enrolled ✓" else "Not enrolled yet") }
    val wake = prefs.wakeWord
    val prompts = listOf(
        "$wake, unlock my phone",
        "$wake, open YouTube",
        "$wake, lock the screen",
        "$wake, turn on the wifi",
        "$wake, volume up",
    )

    GlassCard("Your voice") {
        Text(msg, color = if (msg.startsWith("⚠")) Bad else Dim, fontSize = 14.sp)
        if (step in prompts.indices) {
            Text("Say clearly:", color = Dim, fontSize = 12.sp)
            Text("“${prompts[step]}”", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            LinearProgressIndicator(
                progress = { (lvl * 8f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Accent,
                trackColor = Color(0x22FFFFFF),
            )
            Text("Sample ${step + 1} / ${prompts.size}", color = Dim, fontSize = 12.sp)
        }
        Button(
            enabled = step == -1,
            onClick = {
                if (!hasMic) { onNeedMic(); return@Button }
                scope.launch {
                    ctx.startService(Intent(ctx, VoiceService::class.java).setAction(VoiceService.ACTION_STOP))
                    delay(800)
                    val samples = mutableListOf<FloatArray>()
                    for (i in prompts.indices) {
                        step = i
                        delay(700)
                        samples.add(withContext(Dispatchers.IO) { Audio.record(4f) { lvl = it } })
                    }
                    step = prompts.size
                    msg = "Building your voiceprint…"
                    val res = withContext(Dispatchers.Default) {
                        try { Enroll.build(ctx, samples) } catch (e: Throwable) { null }
                    }
                    if (res == null) {
                        msg = "⚠ Could not hear you well. Try again in a quieter place."
                    } else {
                        prefs.voiceprint = res.first
                        onEnrolled()
                        val q = (res.second * 100).toInt()
                        msg = if (res.second < 0.6f) "⚠ Saved, but quality is low ($q%). Re-record closer to the mic."
                        else "Your voice is enrolled ✓  (quality $q%)"
                    }
                    step = -1
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg1),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (enrolled) "Re-record my voice" else "Record my voice", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SecurityCard(prefs: Prefs) {
    var pin by remember { mutableStateOf(prefs.pin) }
    var wake by remember { mutableStateOf(prefs.wakeWord) }
    var reqWake by remember { mutableStateOf(prefs.requireWake) }
    var thr by remember { mutableFloatStateOf(prefs.threshold) }
    var strict by remember { mutableStateOf(prefs.strictUnlock) }
    var boot by remember { mutableStateOf(prefs.autoBoot) }

    GlassCard("Security & behaviour") {
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isLetterOrDigit).take(16); prefs.pin = pin },
            label = { Text("Lock-screen PIN (stays on this phone)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = wake,
            onValueChange = { wake = it.take(20); prefs.wakeWord = it.trim().ifEmpty { "jarvis" } },
            label = { Text("Wake word") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow("Require wake word", "Prevents normal talk like “lock” from triggering", reqWake) {
            reqWake = it; prefs.requireWake = it
        }
        Column {
            Text("Voice match strictness: ${(thr * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
            Slider(value = thr, onValueChange = { thr = it; prefs.threshold = it }, valueRange = 0.30f..0.80f)
            Text("Higher = safer but may miss you. Lower = easier but less safe.", color = Dim, fontSize = 12.sp)
        }
        ToggleRow("Stricter match for unlock", "Unlock needs +8% higher voice match", strict) {
            strict = it; prefs.strictUnlock = it
        }
        ToggleRow("Start after reboot", "Needs one manual unlock after boot first", boot) {
            boot = it; prefs.autoBoot = it
        }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Text(sub, color = Dim, fontSize = 12.sp)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun CommandsCard(prefs: Prefs) {
    var open by remember { mutableStateOf(false) }
    val w = prefs.wakeWord
    GlassCard("Commands") {
        Text(
            if (open) "Hide" else "Show what you can say",
            color = Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            val lines = listOf(
                "$w, unlock" to "wake + PIN",
                "$w, lock" to "screen off",
                "$w, open <app name>" to "any installed app",
                "$w, wifi on / off" to "",
                "$w, bluetooth on / off" to "",
                "$w, mobile data on / off" to "",
                "$w, airplane mode on / off" to "",
                "$w, flashlight on / off" to "",
                "$w, volume up / down / mute" to "",
                "$w, brighter / dimmer" to "",
                "$w, screenshot" to "",
                "$w, go home / back / recents" to "",
                "$w, play / pause / next" to "media keys",
                "$w, stop listening" to "",
            )
            for ((a, b) in lines) {
                Row(Modifier.fillMaxWidth()) {
                    Text(a, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (b.isNotEmpty()) Text(b, color = Dim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun LogCard(log: List<LogEntry>) {
    GlassCard("Activity") {
        if (log.isEmpty()) {
            Text("Nothing heard yet.", color = Dim, fontSize = 13.sp)
        }
        for (e in log.take(10)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(8.dp)
                        .background(if (e.ok) Ok else Dim, CircleShape),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.result, color = if (e.ok) Color.White else Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("“${e.heard}”", color = Dim, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(e.time, color = Dim, fontSize = 11.sp)
                    if (e.score != 0f) Text("match ${(e.score * 100).toInt()}%", color = Dim, fontSize = 11.sp)
                }
            }
        }
    }
}
