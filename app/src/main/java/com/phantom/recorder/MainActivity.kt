package com.phantom.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val Yellow = Color(0xFFFFC107)
private val CardBg = Color(0xFFF2F2F2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { PhantomApp() } }
    }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d.%d".format(s / 60, s % 60, (ms % 1000) / 100)
}

@Composable
fun PhantomApp(vm: PhantomViewModel = viewModel()) {
    val ctx = LocalContext.current
    val view = LocalView.current
    var showLibrary by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val startStop: () -> Unit = {
        if (vm.recording) {
            vm.stopRecording(); Haptics.stop(ctx)
        } else if (vm.startRecording()) {
            showLibrary = false; Haptics.start(ctx)
        } else Toast.makeText(ctx, "Could not start recording", Toast.LENGTH_SHORT).show()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startStop() else Toast.makeText(ctx, "Microphone permission is required", Toast.LENGTH_LONG).show()
    }
    val onRecord = {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (vm.recording || granted) startStop() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Immersive mode + keep screen on while recording
    LaunchedEffect(vm.recording) {
        val c = WindowCompat.getInsetsController((ctx as Activity).window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vm.recording) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = vm.recording
    }

    // Level-reactive haptic pulses
    LaunchedEffect(Unit) {
        var last = 0L
        snapshotFlow { vm.level }.collect { l ->
            val now = SystemClock.elapsedRealtime()
            if ((vm.recording || vm.playing) && l > 0.55f && now - last > 140) { Haptics.tick(ctx); last = now }
        }
    }

    val glow by animateFloatAsState(vm.level, tween(120), label = "glow")
    val blink by rememberInfiniteTransition(label = "blink")
        .animateFloat(1f, 0.2f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")

    Column(
        Modifier.fillMaxSize().background(Color.Black).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(Yellow.copy(alpha = 0.04f + 0.25f * glow), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.3f), radius = size.width * 1.1f
                )
            )
        }
    ) {
        // Header
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Phantom", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (vm.recording) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(9.dp).alpha(blink).background(Color.Red, CircleShape))
            }
            Spacer(Modifier.weight(1f))
            Box {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { Haptics.tick(ctx); menu = true },
                    Alignment.Center
                ) { Glyph(G.CHEVRON, Color.White, Modifier.size(20.dp)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text((if (vm.highQuality) "✓ " else "") + "High · 256 kbps") },
                        onClick = { vm.highQuality = true; Haptics.click(ctx); menu = false })
                    DropdownMenuItem(
                        text = { Text((if (!vm.highQuality) "✓ " else "") + "Standard · 96 kbps") },
                        onClick = { vm.highQuality = false; Haptics.click(ctx); menu = false })
                }
            }
        }

        // Turntable + record button
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Turntable(active = vm.recording || vm.playing, level = vm.level)
            RecordButton(
                vm.recording, vm.level, onRecord,
                Modifier.align(Alignment.TopCenter).padding(top = 4.dp)
            )
        }

        // Bottom card
        Column(
            Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp).navigationBarsPadding()
                .fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(CardBg).padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CornerButton({ Haptics.click(ctx); vm.refresh(); showLibrary = !showLibrary }) {
                    Glyph(G.WAVE, Color.Black, filled = showLibrary)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        vm.current?.nameWithoutExtension ?: "Phantom", color = Color.Black,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1
                    )
                    val sub = when {
                        vm.recording -> "REC ${fmt(vm.elapsedMs)} · ${vm.marks.size} marks"
                        vm.current != null -> "${fmt(vm.elapsedMs)} · ${vm.marks.size} marks"
                        else -> "Tap the equalizer to record"
                    }
                    Text(sub, color = Color(0xFF8A8A8A), fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1)
                }
                CornerButton({
                    if (vm.addMark()) Haptics.mark(ctx) else {
                        Haptics.tick(ctx)
                        Toast.makeText(ctx, "Bookmarks can be added while recording", Toast.LENGTH_SHORT).show()
                    }
                }) { Glyph(G.MARK, Color.Black, filled = vm.marks.isNotEmpty()) }
            }

            if (showLibrary) {
                if (vm.files.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) {
                        Text("No recordings yet", color = Color(0xFF8A8A8A))
                    }
                } else LazyColumn(Modifier.fillMaxWidth().height(150.dp)) {
                    items(vm.files, key = { it.name }) { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { Haptics.click(ctx); vm.play(f); showLibrary = false }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(f.nameWithoutExtension, color = Color.Black, fontWeight = FontWeight.SemiBold)
                                Text("%.1f MB".format(f.length() / 1_048_576f), color = Color(0xFF8A8A8A), fontSize = 12.sp)
                            }
                            Text("✕", color = Color(0xFF8A8A8A), fontSize = 18.sp,
                                modifier = Modifier.clickable { Haptics.stop(ctx); vm.delete(f) }.padding(8.dp))
                        }
                    }
                }
            } else {
                Waveform(vm, Modifier.fillMaxWidth().height(150.dp).padding(vertical = 8.dp))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CornerButton({ vm.cycleVolume(); Haptics.click(ctx) }) {
                    Glyph(G.VOL, Color.Black, level = vm.volumeStep)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Glyph(G.EARBUD, Color.Black, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(outputLabel(), color = Color(0xFFAAAAAA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                CornerButton({
                    Haptics.click(ctx)
                    if (!vm.togglePlay()) Toast.makeText(ctx, "Nothing to play yet", Toast.LENGTH_SHORT).show()
                }) { Glyph(if (vm.playing) G.PAUSE else G.NOTE, Color.Black) }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(130.dp).height(5.dp).clip(CircleShape).background(Color.Black))
        }
    }
}

@Composable
fun CornerButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(58.dp).drawBehind {
            drawCircle(
                Color(0x40000000), style = Stroke(
                    1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))
                )
            )
        }.clip(CircleShape).clickable(onClick = onClick),
        Alignment.Center
    ) { content() }
}

@Composable
fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "p")
    val bg by animateColorAsState(if (recording) Yellow.copy(alpha = 0.22f) else Color(0xFF1A1A1A), label = "bg")
    val scale by animateFloatAsState(if (recording) 1f + level * 0.12f else 1f, tween(80), label = "s")
    Box(
        modifier.size(64.dp).drawBehind {
            if (recording) for (o in listOf(0f, 0.5f)) {
                val t = (pulse + o) % 1f
                drawCircle(Yellow.copy(alpha = (1f - t) * 0.5f), 32.dp.toPx() + t * 80.dp.toPx(), style = Stroke(2.dp.toPx()))
            }
        }.graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape).background(bg).border(1.dp, Color(0xFF2A2A2A), CircleShape).clickable(onClick = onClick),
        Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val base = listOf(0.45f, 1f, 0.7f)
            base.forEachIndexed { i, b ->
                val h = if (recording) {
                    val wob = abs(sin(2 * PI * (pulse * (i + 2)) + i)).toFloat()
                    (0.25f + 0.75f * (b * 0.4f + level * wob * 0.9f)).coerceIn(0.2f, 1f)
                } else b
                val bw = size.width * 0.24f
                val x = size.width * (0.04f + i * 0.36f)
                drawRoundRect(Yellow, Offset(x, size.height * (1 - h)), Size(bw, size.height * h), CornerRadius(bw / 2))
            }
        }
    }
}

@Composable
fun Turntable(active: Boolean, level: Float) {
    val act by rememberUpdatedState(active)
    val lvl by rememberUpdatedState(level)
    var angle by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                val target = if (act) 110f + lvl * 380f else 0f
                speed += (target - speed) * min(1f, dt * 3f)
                angle = (angle + speed * dt) % 360f
            }
            last = t
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = min(size.width * 0.46f, size.height * 0.5f)
        val cy = size.height * 0.5f
        rotate(angle, Offset(0f, cy)) { disc(Offset(0f, cy), r, lvl) }
        rotate(-angle, Offset(size.width, cy)) { disc(Offset(size.width, cy), r, lvl) }
        // needle
        val cx = size.width / 2
        val ny = size.height * 0.78f
        val needle = Path().apply {
            moveTo(cx - 9.dp.toPx(), ny); lineTo(cx + 9.dp.toPx(), ny); lineTo(cx, ny + 46.dp.toPx()); close()
        }
        drawPath(needle, Color(0xFF3A3A3A))
        drawCircle(Color(0xFF3A3A3A), 5.dp.toPx(), Offset(cx - 38.dp.toPx(), ny + 54.dp.toPx()), style = Stroke(1.dp.toPx()))
        drawCircle(Color(0xFF3A3A3A), 5.dp.toPx(), Offset(cx + 38.dp.toPx(), ny + 54.dp.toPx()), style = Stroke(1.dp.toPx()))
    }
}

private fun DrawScope.disc(c: Offset, r: Float, level: Float) {
    drawCircle(Color(0xFF151515), r, c)
    if (level > 0.02f) drawCircle(Yellow.copy(alpha = 0.10f * level), r, c)
    // edge ticks
    drawCircle(Color(0xFF2E2E2E), r * 0.985f, c, style = Stroke(r * 0.03f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f))))
    // light band + cut-out arcs (reads as rotation)
    val bandR = r * 0.62f
    val tl = Offset(c.x - bandR, c.y - bandR)
    drawArc(Color(0xFF3A3A3A), -150f, 55f, false, tl, Size(bandR * 2, bandR * 2), style = Stroke(r * 0.2f))
    drawArc(Color.Black, -80f, 40f, false, tl, Size(bandR * 2, bandR * 2), style = Stroke(r * 0.2f))
    drawArc(Color(0xFF3A3A3A), 30f, 55f, false, tl, Size(bandR * 2, bandR * 2), style = Stroke(r * 0.2f))
    drawArc(Color.Black, 100f, 40f, false, tl, Size(bandR * 2, bandR * 2), style = Stroke(r * 0.2f))
    // hub
    drawCircle(Color(0xFF050505), r * 0.26f, c)
    drawCircle(Color(0xFF2A2A2A), r * 0.26f, c, style = Stroke(1.5f))
}

@Composable
fun Waveform(vm: PhantomViewModel, modifier: Modifier) {
    Canvas(modifier) {
        val step = 6.dp.toPx(); val bw = 3.dp.toPx()
        val px = size.width * 0.64f; val cy = size.height / 2
        var gx = 0f
        while (gx < size.width) {
            drawLine(Color(0x1A000000), Offset(gx, 0f), Offset(gx, size.height), 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f)))
            gx += step * 2
        }
        val s = vm.samples; val cur = vm.cursor
        val first = max(0, cur - 1 - (px / step).toInt() - 1)
        val last = min(s.lastIndex, cur + ((size.width - px) / step).toInt() + 1)
        for (i in first..last) {
            val x = px + (i - cur + 1) * step
            val bh = max(3.dp.toPx(), s[i] * size.height * 0.92f)
            drawRoundRect(
                if (i < cur) Color(0xFF111111) else Color(0xFFC9C9C9),
                Offset(x - bw / 2, cy - bh / 2), Size(bw, bh), CornerRadius(bw / 2)
            )
        }
        for (m in vm.marks) {
            val x = px + ((m / SAMPLE_MS).toInt() - cur + 1) * step
            if (x in 0f..size.width) drawLine(Yellow, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
        // playhead
        drawLine(Color.Black, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        val t = 5.dp.toPx()
        drawPath(Path().apply { moveTo(px - t, 0f); lineTo(px + t, 0f); lineTo(px, t * 1.4f); close() }, Color.Black)
        drawPath(Path().apply { moveTo(px - t, size.height); lineTo(px + t, size.height); lineTo(px, size.height - t * 1.4f); close() }, Color.Black)
    }
}

private fun readOutput(c: Context): String {
    val am = c.getSystemService(AudioManager::class.java)
    val devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        ?.let { return it.productName?.toString()?.lowercase()?.takeIf { n -> n.isNotBlank() } ?: "bluetooth" }
    if (devs.any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET })
        return "headphones"
    return "speaker"
}

@Composable
fun outputLabel(): String {
    val ctx = LocalContext.current
    var label by remember { mutableStateOf(readOutput(ctx)) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { label = readOutput(ctx) }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { label = readOutput(ctx) }
        }
        am.registerAudioDeviceCallback(cb, null)
        onDispose { am.unregisterAudioDeviceCallback(cb) }
    }
    return label
}
