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
        Text("Status: $state   •   ${com.sbitx.remote.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
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

            // ---- Sliders (paired to fit one screen) ----
            Row(Modifier.fillMaxWidth()) {
                LabeledSlider("Volume", vol, 0..100, modifier = Modifier.weight(1f)) {
                    client.setVolume(it)
                }
                Spacer(Modifier.width(12.dp))
                LabeledSlider("Drive", drive, 1..100, modifier = Modifier.weight(1f)) {
                    client.setDrive(it)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                LabeledSlider("Bandwidth", bw, 300..5000, step = 100,
                    modifier = Modifier.weight(1f)) { client.setBandwidth(it) }
                Spacer(Modifier.width(12.dp))
                // ---- AINR: only on capable firmware, voice modes only ----
                val ainr = fields["AINR"]
                val digital = mode in listOf("FT8", "FT4", "DIGI", "DIGITAL", "2TONE")
                if (ainr != null && !digital) {
                    val ainrs = fields["AINRS"]?.toIntOrNull() ?: 80
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AINR", fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = ainr == "ON",
                                onCheckedChange = { client.setAinr(it) },
                                modifier = Modifier.height(26.dp)
                            )
                        }
                        if (ainr == "ON") {
                            LabeledSlider("Strength", ainrs, 0..100, step = 5) {
                                client.setAinrStrength(it)
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }

            OutlinedButton(onClick = { service.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            Spacer(Modifier.height(8.dp))
        }

        // ================= FIXED PTT (hidden in FT8 - TX is slot-based) =================
        if (mode != "FT8" && mode != "FT4") Box(
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
fun LabeledSlider(
    label: String, value: Int, range: IntRange, step: Int = 1,
    modifier: Modifier = Modifier, onSet: (Int) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(modifier) {
        Text("$label: ${local.toInt()}", fontSize = 11.sp)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSet((local.toInt() / step) * step) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.height(26.dp)
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
 * FT8 operating panel. Decodes stream from the radio as styled fragments
 * sharing a row id; we stitch fragments into single lines and render the
 * firmware's #X span styles with the same meaning as the sBitx screen:
 * time/freq, SNR, caller, grid, country, my-call highlight.
 */
@Composable
fun Ft8Console(client: SbitxClient) {
    val fields by client.fields.collectAsState()
    val myCall = (fields["MYCALLSIGN"] ?: "").uppercase()
    val myGrid = (fields["MYGRID"] ?: "").uppercase().take(4)

    // ordered rows: rowId -> accumulated decorated text
    val rowOrder = remember { mutableStateListOf<Int>() }
    val rowText = remember { mutableStateMapOf<Int, String>() }
    val rowKind = remember { mutableStateMapOf<Int, String>() }
    var synth = remember { -1 }
    val listState = rememberLazyListState()
    var msg by remember { mutableStateOf("") }
    var dxCall by remember { mutableStateOf("") }
    var dxSnr by remember { mutableStateOf("") }
    var cqMod by remember { mutableStateOf("") }

    LaunchedEffect(client) {
        client.console.collect { cl ->
            if (!cl.kind.startsWith("WSJTX")) return@collect
            val id = if (cl.line >= 0) cl.line else synth--
            if (rowText.containsKey(id)) {
                rowText[id] = rowText[id] + cl.text
            } else {
                rowText[id] = cl.text
                rowKind[id] = cl.kind
                rowOrder.add(id)
                if (rowOrder.size > 200) {
                    val old = rowOrder.removeAt(0)
                    rowText.remove(old); rowKind.remove(old)
                }
            }
            if (rowOrder.isNotEmpty()) listState.animateScrollToItem(rowOrder.size - 1)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF10141A), RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        Text(
            if (myCall.isBlank()) "FT8  (set MYCALLSIGN on the radio!)"
            else "FT8  $myCall $myGrid" + (if (dxCall.isNotBlank()) "  →  $dxCall" else ""),
            fontSize = 12.sp, color = Color(0xFF81C784)
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(170.dp)
        ) {
            items(rowOrder.size) { i ->
                val id = rowOrder[i]
                val text = rowText[id] ?: return@items
                val kind = rowKind[id] ?: "WSJTX-RX"
                Text(
                    renderDecorated(text, kind),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val sp = decoSpans(text)
                            sp.firstOrNull { it.first == 'R' }?.let { dxCall = it.second.trim() }
                                ?: run {
                                    val plain = sp.joinToString("") { it.second }
                                    plain.split(" ").lastOrNull {
                                        it.any(Char::isDigit) && it.any(Char::isLetter) && it.length in 3..10
                                    }?.let { dxCall = it }
                                }
                            sp.firstOrNull { it.first == 'H' }?.let { dxSnr = it.second.trim() }
                        }
                        .padding(vertical = 1.dp)
                )
            }
        }

        // ---- Standard message templates ----
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                cqMod, { cqMod = it.uppercase().trim() },
                label = { Text("CQ mod", fontSize = 10.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                modifier = Modifier.width(86.dp)
            )
            Ft8Btn("CQ", myCall.isNotBlank()) {
                msg = listOf("CQ", cqMod, myCall, myGrid)
                    .filter { it.isNotBlank() }.joinToString(" ")
            }
            Ft8Btn("Call", dxCall.isNotBlank() && myCall.isNotBlank()) {
                msg = "$dxCall $myCall $myGrid".trim()
            }
            Ft8Btn("Rprt", dxCall.isNotBlank() && myCall.isNotBlank()) {
                msg = "$dxCall $myCall ${dxSnr.ifBlank { "-10" }}"
            }
            Ft8Btn("RR73", dxCall.isNotBlank() && myCall.isNotBlank()) {
                msg = "$dxCall $myCall RR73"
            }
            Ft8Btn("73", dxCall.isNotBlank() && myCall.isNotBlank()) {
                msg = "$dxCall $myCall 73"
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                msg, { msg = it.uppercase() },
                label = { Text("FT8 message", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { if (msg.isNotBlank()) client.ft8Transmit(msg) },
                enabled = msg.isNotBlank()
            ) { Text("Send") }
        }
    }
}

@Composable
fun Ft8Btn(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(40.dp)
    ) { Text(label, fontSize = 12.sp) }
}

/** Split "#G142645 16 #H-15 ..." into (styleChar, text) spans. Default style 'F'. */
fun decoSpans(text: String): List<Pair<Char, String>> {
    val out = mutableListOf<Pair<Char, String>>()
    var style = 'F'
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '#' && i + 1 < text.length && text[i + 1] in 'A'..'Z') {
            if (sb.isNotEmpty()) { out.add(style to sb.toString()); sb.clear() }
            style = text[i + 1]; i += 2
        } else { sb.append(c); i++ }
    }
    if (sb.isNotEmpty()) out.add(style to sb.toString())
    return out
}

/**
 * Render firmware span styles (see hist_disp.c ff_char):
 * G=time/freq/FT8-RX, H=SNR/FT8-TX, Q=my call, R=caller, S=grid,
 * W=already-worked grid, P=country/reply, O=queued, U/V=distance/azimuth.
 */
fun renderDecorated(text: String, kind: String):
        androidx.compose.ui.text.AnnotatedString {
    // Exact colors from the firmware's font_table (sbitx_gtk.c)
    fun colorOf(s: Char): Color = when (s) {
        'G' -> Color(0xFF00CCCC)          // time / freq / FT8 RX (0,0.8,0.8)
        'H' -> Color(0xFFFFFFFF)          // SNR (1,1,1)
        'Q' -> Color(0xFFFF3333)          // my callsign (1,0,0)
        'R' -> Color(0xFFE07818)          // caller (0.8,0.4,0)
        'S' -> Color(0xFFFFCC00)          // grid, new (1,0.8,0)
        'W' -> Color(0xFF00B300)          // grid, already worked (0,0.6,0)
        'P' -> Color(0xFF00E000)          // country / FT8 reply (0,1,0)
        'O' -> Color(0xFFFFB74D)          // queued
        'U', 'V' -> Color(0xFFFFCC00)     // distance / azimuth (1,0.8,0)
        else -> Color(0xFFB3B3B3)         // log/default (0.7,0.7,0.7)
    }
    return androidx.compose.ui.text.buildAnnotatedString {
        val kindTint = when (kind) {
            "WSJTX-TX" -> Color(0xFFEF5350)
            "WSJTX-Q" -> Color(0xFFFFB74D)
            else -> null
        }
        for ((s, t) in decoSpans(text)) {
            val bold = s == 'Q'
            pushStyle(androidx.compose.ui.text.SpanStyle(
                color = kindTint ?: colorOf(s),
                fontWeight = if (bold) FontWeight.Bold else null
            ))
            append(t)
            pop()
        }
    }
}

@Composable
fun FreqEntryRow(onGo: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it }, label = { Text("kHz", fontSize = 11.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
            modifier = Modifier.width(140.dp).height(52.dp)
        )
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = {
            text.toDoubleOrNull()?.let { onGo((it * 1000).toLong()) }
        }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Go", fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
    }
}

fun formatFreq(hz: Long): String {
    if (hz <= 0) return "-- . --- . ---"
    val mhz = hz / 1_000_000
    val khz = (hz / 1_000) % 1_000
    val h = hz % 1_000
    return "%d.%03d.%03d".format(mhz, khz, h)
}
