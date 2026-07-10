package com.sbitx.remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sbitx.remote.net.SbitxClient
import com.sbitx.remote.service.RadioService

class MainActivity : ComponentActivity() {

    private var service: RadioService? by mutableStateOf(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RadioService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) micPermission.launch(Manifest.permission.RECORD_AUDIO)

        val svcIntent = Intent(this, RadioService::class.java)
        startService(svcIntent)
        bindService(svcIntent, conn, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(service)
                }
            }
        }
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }
}

@Composable
fun AppRoot(service: RadioService?) {
    val client = service?.client?.collectAsState()?.value
    val state = client?.state?.collectAsState()?.value ?: SbitxClient.ConnState.DISCONNECTED

    if (state == SbitxClient.ConnState.CONNECTED && client != null && service != null) {
        RadioPanel(client, service)
    } else {
        ConnectScreen(service, state, client)
    }
}

@Composable
fun ConnectScreen(service: RadioService?, state: SbitxClient.ConnState, client: SbitxClient?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sbitx", Context.MODE_PRIVATE) }

    var viaTailscale by remember { mutableStateOf(prefs.getBoolean("viaTailscale", false)) }
    var localHost by remember { mutableStateOf(prefs.getString("localHost", "192.168.1.11")!!) }
    var tsHost by remember { mutableStateOf(prefs.getString("tsHost", "")!!) }
    var port by remember { mutableStateOf(prefs.getString("port", "8443")!!) }
    var pin by remember { mutableStateOf(prefs.getString("pin", "")!!) }
    var useTls by remember { mutableStateOf(prefs.getBoolean("useTls", true)) }
    val lastError = client?.lastError?.collectAsState()?.value

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("sBitx Remote", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

        // --- Connection method selector ---
        Text("Connect via", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !viaTailscale, onClick = { viaTailscale = false },
                label = { Text("Local network") }, modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = viaTailscale, onClick = { viaTailscale = true },
                label = { Text("Tailscale (internet)") }, modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        if (viaTailscale) {
            OutlinedTextField(tsHost, { tsHost = it },
                label = { Text("Tailscale IP / MagicDNS name") },
                placeholder = { Text("100.x.y.z or sbitx.tailxxxx.ts.net") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Make sure the Tailscale app is connected on this phone",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            OutlinedTextField(localHost, { localHost = it },
                label = { Text("Radio local IP") },
                placeholder = { Text("192.168.1.11") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(port, { port = it }, label = { Text("Port (8443)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pin, { pin = it }, label = { Text("PIN") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(useTls, { useTls = it })
            Text("Use TLS (required by drexjj firmware, port 8443)")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val host = (if (viaTailscale) tsHost else localHost).trim()
                prefs.edit()
                    .putBoolean("viaTailscale", viaTailscale)
                    .putString("localHost", localHost.trim())
                    .putString("tsHost", tsHost.trim())
                    .putString("port", port.trim())
                    .putString("pin", pin.trim())
                    .putBoolean("useTls", useTls)
                    .apply()
                service?.connect(host, port.trim().toIntOrNull() ?: 8443, useTls, pin.trim())
            },
            enabled = service != null && state != SbitxClient.ConnState.CONNECTING &&
                (if (viaTailscale) tsHost.isNotBlank() else localHost.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (state == SbitxClient.ConnState.CONNECTING || state == SbitxClient.ConnState.LOGIN_SENT) "Connecting…" else "Connect") }

        if (state == SbitxClient.ConnState.AUTH_FAILED) {
            Spacer(Modifier.height(12.dp))
            Text("Login failed — check your PIN", color = MaterialTheme.colorScheme.error)
        }
        if (state == SbitxClient.ConnState.ERROR) {
            Spacer(Modifier.height(12.dp))
            Text(
                lastError ?: "Connection failed — check host/port and that you're on the same network or Tailscale",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Status: $state   •   v0.5", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RadioPanel(client: SbitxClient, service: RadioService) {
    val fields by client.fields.collectAsState()

    val freq = fields["FREQ"]?.toLongOrNull() ?: 0L
    val mode = fields["MODE"] ?: "?"
    val mic = fields["MIC"]?.toIntOrNull() ?: 25
    val vol = fields["AUDIO"]?.toIntOrNull() ?: 50
    val drive = fields["DRIVE"]?.toIntOrNull() ?: 40
    val bw = fields["BW"]?.toIntOrNull() ?: 2400
    val smeter = fields["SMETER"] ?: "0 0"
    var txActive by remember { mutableStateOf(false) }
    val freqNow = rememberUpdatedState(freq)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {

        // ================= FIXED HEADER: freq readout + tuning knob =================
        var selectedMult by remember { mutableStateOf(100L) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FreqDigits(freq, selectedMult) { selectedMult = it }
                Text("$mode   S: $smeter   step: ${stepName(selectedMult)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            TuningKnob(
                modifier = Modifier.size(92.dp),
                onDelta = { steps ->
                    val f = (freqNow.value + steps * selectedMult).coerceIn(500_000L, 30_000_000L)
                    client.setFrequency(f)
                }
            )
        }
        Spacer(Modifier.height(4.dp))

        // ================= SCROLLABLE CONTROLS =================
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(-1000L, -100L, +100L, +1000L).forEach { d ->
                    OutlinedButton(
                        onClick = { client.setFrequency(freq + d) },
                        contentPadding = PaddingValues(horizontal = 10.dp)
                    ) { Text(if (d > 0) "+${d / 100}" else "${d / 100}") }
                }
            }
            FreqEntryRow { client.setFrequency(it) }
            Spacer(Modifier.height(6.dp))

            // ---- Band (dropdown) + Mode ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BandDropdown(Modifier.weight(1f)) { client.setBand(it) }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("LSB", "USB", "CW", "AM", "FT8").forEach { m ->
                    FilterChip(selected = mode == m, onClick = { client.setMode(m) },
                        label = { Text(m) })
                }
            }
            Spacer(Modifier.height(6.dp))

            // ---- FT8 console (visible in FT8 mode) ----
            if (mode == "FT8" || mode == "FT4") {
                Ft8Console(client)
                Spacer(Modifier.height(6.dp))
            }

            // ---- Sliders ----
            Spacer(Modifier.height(0.dp))
            LabeledSlider("Mic gain", mic, 0..100) { client.setMicGain(it) }
            LabeledSlider("Volume", vol, 0..100) { client.setVolume(it) }
            LabeledSlider("Drive (power)", drive, 1..100) { client.setDrive(it) }
            LabeledSlider("Bandwidth", bw, 300..5000, step = 100) { client.setBandwidth(it) }

            OutlinedButton(onClick = { service.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            Spacer(Modifier.height(8.dp))
        }

        // ================= FIXED PTT (always visible) =================
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .padding(top = 4.dp)
                .background(
                    if (txActive) Color(0xFFB71C1C) else Color(0xFF1B5E20),
                    RoundedCornerShape(16.dp)
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            txActive = true
                            service.pttDown()
                            tryAwaitRelease()
                            txActive = false
                            service.pttUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (txActive) "ON AIR — release to RX" else "HOLD TO TALK (PTT)",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Rotary tuning knob: drag around the center; each 12 degrees = one 100 Hz step. */
@Composable
fun TuningKnob(modifier: Modifier = Modifier, onDelta: (Int) -> Unit) {
    var angle by remember { mutableFloatStateOf(0f) }
    var accum by remember { mutableFloatStateOf(0f) }
    val knobColor = MaterialTheme.colorScheme.surfaceVariant
    val rimColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurface

    androidx.compose.foundation.Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val c = Offset(size.width / 2f, size.height / 2f)
                val p0 = change.previousPosition - c
                val p1 = change.position - c
                var d = Math.toDegrees(
                    (atan2(p1.y, p1.x) - atan2(p0.y, p0.x)).toDouble()
                ).toFloat()
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                angle += d
                accum += d
                val stepDeg = 12f
                while (accum >= stepDeg) { onDelta(+1); accum -= stepDeg }
                while (accum <= -stepDeg) { onDelta(-1); accum += stepDeg }
                change.consume()
            }
        }
    ) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = knobColor, radius = r, center = c)
        drawCircle(color = rimColor, radius = r, center = c,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.08f))
        // knurl ticks
        for (i in 0 until 12) {
            val a = Math.toRadians((angle + i * 30f).toDouble())
            val inner = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.72f)
            val outer = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.88f)
            drawLine(rimColor.copy(alpha = 0.5f), inner, outer, strokeWidth = r * 0.04f)
        }
        // position dot
        val a = Math.toRadians(angle.toDouble() - 90.0)
        val dot = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.55f)
        drawCircle(color = dotColor, radius = r * 0.10f, center = dot)
    }
}

/** Band selector as a dropdown menu. */
@Composable
fun BandDropdown(modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("Band") }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Band: $selected  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("80M", "40M", "30M", "20M", "17M", "15M", "12M", "10M").forEach { b ->
                DropdownMenuItem(text = { Text(b) }, onClick = {
                    selected = b; expanded = false; onSelect(b)
                })
            }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, step: Int = 1, onSet: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text("$label: ${local.toInt()}", fontSize = 13.sp)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSet((local.toInt() / step) * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.height(30.dp)
        )
    }
}

/** Tap-to-select frequency digits; the knob then tunes the selected digit's place. */
@Composable
fun FreqDigits(freq: Long, selectedMult: Long, onSelect: (Long) -> Unit) {
    val f = freq.coerceIn(0L, 99_999_999L)
    val digits = "%08d".format(f)
    val mults = listOf(
        10_000_000L, 1_000_000L, 100_000L, 10_000L, 1_000L, 100L, 10L, 1L
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { i, ch ->
            if (i == 2 || i == 5) {
                Text(".", fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold)
            }
            val sel = mults[i] == selectedMult
            Text(
                ch.toString(),
                fontSize = 30.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (sel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (sel) TextDecoration.Underline else null,
                modifier = Modifier
                    .clickable { onSelect(mults[i]) }
                    .padding(horizontal = 1.dp)
            )
        }
    }
}

fun stepName(mult: Long): String = when (mult) {
    10_000_000L -> "10 MHz"; 1_000_000L -> "1 MHz"
    100_000L -> "100 kHz"; 10_000L -> "10 kHz"; 1_000L -> "1 kHz"
    100L -> "100 Hz"; 10L -> "10 Hz"; else -> "1 Hz"
}

/**
 * Live FT8 activity streamed from the radio (decoding happens on the sBitx).
 * Tap a decode to copy its callsign into the message box; Send queues the
 * message for the next FT8 time slot via the firmware's "key" command.
 */
@Composable
fun Ft8Console(client: SbitxClient) {
    val lines = remember { mutableStateListOf<SbitxClient.ConsoleLine>() }
    val listState = rememberLazyListState()
    var msg by remember { mutableStateOf("") }

    LaunchedEffect(client) {
        client.console.collect { cl ->
            if (cl.kind.startsWith("WSJTX")) {
                lines.add(cl)
                if (lines.size > 200) lines.removeAt(0)
                listState.animateScrollToItem(lines.size - 1)
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF10141A), RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        Text("FT8 activity", fontSize = 12.sp, color = Color(0xFF81C784))
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(180.dp)
        ) {
            items(lines.size) { i ->
                val l = lines[i]
                val color = when (l.kind) {
                    "WSJTX-TX" -> Color(0xFFEF5350)   // our transmissions
                    "WSJTX-Q" -> Color(0xFFFFB74D)    // queued for TX
                    else -> Color(0xFFE0E0E0)          // received decodes
                }
                Text(
                    l.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // pull the most likely callsign into the message box
                            val call = l.text.split(" ")
                                .lastOrNull { it.any(Char::isDigit) && it.any(Char::isLetter) && it.length in 3..10 }
                            if (call != null) msg = call
                        }
                        .padding(vertical = 1.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                msg, { msg = it.uppercase() },
                label = { Text("FT8 message (e.g. CQ VU3UBP MK68)", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { if (msg.isNotBlank()) { client.ft8Transmit(msg) } },
                enabled = msg.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
fun FreqEntryRow(onGo: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it }, label = { Text("Frequency in kHz (e.g. 7100)") },
            singleLine = true, modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            text.toDoubleOrNull()?.let { onGo((it * 1000).toLong()) }
        }) { Text("Go") }
    }
}

fun formatFreq(hz: Long): String {
    if (hz <= 0) return "-- . --- . ---"
    val mhz = hz / 1_000_000
    val khz = (hz / 1_000) % 1_000
    val h = hz % 1_000
    return "%d.%03d.%03d".format(mhz, khz, h)
}
