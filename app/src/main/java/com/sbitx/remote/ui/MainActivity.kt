package com.sbitx.remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.sbitx.remote.net.SbitxClient
import com.sbitx.remote.net.SbitxClient.ConnState
import com.sbitx.remote.service.RadioService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var service: RadioService? by mutableStateOf(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as RadioService.LocalBinder).service()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())

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
    val state = client?.state?.collectAsState()?.value ?: ConnState.DISCONNECTED

    // Stay on the radio panel while auto-reconnecting so the operator keeps context.
    if ((state == ConnState.CONNECTED || state == ConnState.RECONNECTING) &&
        client != null && service != null
    ) {
        RadioPanel(client, service, state)
    } else {
        ConnectScreen(service, state, client)
    }
}

@Composable
fun ConnectScreen(service: RadioService?, state: ConnState, client: SbitxClient?) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sbitx", Context.MODE_PRIVATE) }

    var viaTailscale by remember { mutableStateOf(prefs.getBoolean("viaTailscale", false)) }
    var localHost by remember { mutableStateOf(prefs.getString("localHost", "zbitx.local")!!) }
    var tsHost by remember { mutableStateOf(prefs.getString("tsHost", "")!!) }
    var port by remember { mutableStateOf(prefs.getString("port", "8443")!!) }
    var pin by remember { mutableStateOf(prefs.getString("pin", "")!!) }
    var useTls by remember { mutableStateOf(prefs.getBoolean("useTls", true)) }
    val lastError = client?.lastError?.collectAsState()?.value

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("zBitx Remote", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))

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
                placeholder = { Text("100.x.y.z or zbitx.tailxxxx.ts.net") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text(
                "Make sure the Tailscale app is connected on this phone",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            OutlinedTextField(localHost, { localHost = it },
                label = { Text("Radio address") },
                placeholder = { Text("zbitx.local or 192.168.1.11") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(port, { port = it }, label = { Text("Port (8443)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pin, { pin = it }, label = { Text("Passkey (SET → PASSKEY on the radio)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(useTls, { useTls = it })
            Text("Use TLS (zBitx requires it for remote clients, port 8443)",
                style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        val busy = state == ConnState.CONNECTING || state == ConnState.LOGIN_SENT
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
            enabled = service != null && !busy &&
                (if (viaTailscale) tsHost.isNotBlank() else localHost.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                when {
                    busy -> "Connecting…"
                    state == ConnState.SESSION_ENDED -> "Reconnect (takes over the session)"
                    else -> "Connect"
                }
            )
        }

        val msg = when (state) {
            ConnState.AUTH_FAILED -> "Login failed - check the passkey (SET → PASSKEY on the radio, case-sensitive)"
            ConnState.SESSION_ENDED -> lastError ?: "The radio ended the session"
            ConnState.ERROR -> lastError
                ?: "Connection failed - check address/port and that you're on the same network or Tailscale"
            else -> null
        }
        if (msg != null) {
            Spacer(Modifier.height(12.dp))
            Text(msg, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(8.dp))
        Text("Status: $state   •   ${appVersion()}",
            style = MaterialTheme.typography.bodySmall)
    }
}

private val VOICE_MODES = setOf("USB", "LSB", "AM", "2TONE")
private val CW_MODES = setOf("CW", "CWR")

@Composable
fun RadioPanel(client: SbitxClient, service: RadioService, state: ConnState) {
    val fields by client.fields.collectAsState()
    val onAir by client.onAir.collectAsState()
    val lastError by client.lastError.collectAsState()
    val micGain by service.phoneMicGain.collectAsState()
    val micError by service.micError.collectAsState()

    val radioFreq = fields["FREQ"]?.toLongOrNull() ?: 0L
    val mode = fields["MODE"] ?: "?"
    val pitch = fields["PITCH"]?.toIntOrNull() ?: 600
    var pttHeld by remember { mutableStateOf(false) }
    var selectedMult by remember { mutableStateOf(100L) }
    var freqText by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // ---- Local tuning target: the knob/waterfall move this immediately and a
    // 40 ms sender forwards only the latest value. zBitx queues remote commands
    // in a 1000-byte buffer and DISCARDS the whole queue on overflow, so an
    // unthrottled drag could silently lose tuning steps.
    var target by remember { mutableLongStateOf(0L) }
    var lastLocal by remember { mutableLongStateOf(0L) }
    var pendingFreq by remember { mutableLongStateOf(0L) }
    val recentlyTuned = System.currentTimeMillis() - lastLocal < 700
    LaunchedEffect(radioFreq) { if (System.currentTimeMillis() - lastLocal > 700) target = radioFreq }
    LaunchedEffect(client) {
        var sent = 0L
        while (true) {
            val p = pendingFreq
            if (p > 0 && p != sent) { client.setFrequency(p); sent = p }
            delay(40)
        }
    }
    val shownFreq = if (recentlyTuned && target > 0) target else radioFreq
    fun tuneTo(f: Long) {
        val v = f.coerceIn(100_000L, 30_000_000L)
        target = v; lastLocal = System.currentTimeMillis(); pendingFreq = v
    }
    fun tuneBy(d: Long) = tuneTo((if (target > 0) target else radioFreq) + d)

    if (showLog) LogbookDialog(client) { showLog = false }

    // ================= HEADER =================
    val header: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("zBitx Remote", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(appVersion(), fontSize = 10.sp, color = Color(0x88FFFFFF), maxLines = 1)
            }
            Text(fields["STATUS"]?.substringAfter(' ') ?: "", fontSize = 11.sp,
                color = Color(0x99FFFFFF), modifier = Modifier.padding(end = 6.dp))
            OutlinedButton(
                onClick = { showLog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) { Text("Log", fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(
                onClick = { service.disconnect() },
                border = BorderStroke(1.dp, Color(0xFFEF5350)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) { Text("Disconnect", fontSize = 12.sp) }
        }
        if (state == ConnState.RECONNECTING) {
            Text(
                lastError ?: "Reconnecting…",
                fontSize = 12.sp, color = Color.Black,
                modifier = Modifier.fillMaxWidth()
                    .background(Color(0xFFFFB74D), RoundedCornerShape(6.dp))
                    .padding(6.dp)
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FreqDigits(shownFreq, selectedMult) { selectedMult = it }
            Spacer(Modifier.weight(1f))
            val vfo = fields["VFO"] ?: "A"
            SmallChip("VFO $vfo", selected = vfo == "B") { client.setVfo(if (vfo == "A") "B" else "A") }
        }
        MeterRow(fields, onAir, mode, stepName(selectedMult))
        Spacer(Modifier.height(4.dp))
    }

    // ================= SCROLLABLE CONTROLS =================
    val controls: @Composable () -> Unit = {
        // ---- Frequency entry ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                freqText, { freqText = it },
                label = { Text("kHz", fontSize = 10.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                modifier = Modifier.weight(1f).height(52.dp)
            )
            TuneBtn("Go") {
                freqText.toDoubleOrNull()?.let { tuneTo((it * 1000).toLong()); freqText = "" }
            }
        }
        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BandDropdown(Modifier.weight(1f), shownFreq) { client.setBand(it) }
            ModeDropdown(Modifier.weight(1f), mode) { client.setMode(it) }
        }
        Spacer(Modifier.height(4.dp))

        // ---- Waterfall: tap to tune, drag to pan ----
        Waterfall(
            client = client,
            mode = mode,
            low = fields["LOW"]?.toIntOrNull() ?: 300,
            high = fields["HIGH"]?.toIntOrNull() ?: 3000,
            txPitch = fields["TX_PITCH"]?.toIntOrNull(),
            modifier = Modifier.fillMaxWidth().height(if (mode == "FT8") 90.dp else 120.dp),
            onTap = { off ->
                when (mode) {
                    "CW" -> tuneBy((off - pitch).toLong())
                    "CWR" -> tuneBy((off + pitch).toLong())
                    "FT8" -> if (off in 100..3000) client.setTxPitch((off / 10) * 10)
                             else tuneBy(off.toLong())
                    else -> tuneTo(((shownFreq + off) / 10) * 10)
                }
            },
            onDrag = { hz -> tuneBy(hz) }
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Span", fontSize = 11.sp)
            val span = fields["SPAN"] ?: ""
            listOf("25K", "10K", "6K", "2.5K").forEach { s ->
                SmallChip(s, selected = span == s) { client.setSpan(s) }
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (mode == "FT8") "tap = TX tone" else "tap = tune",
                fontSize = 10.sp, color = Color(0x88FFFFFF)
            )
        }
        Spacer(Modifier.height(4.dp))

        // ---- Mode-specific consoles ----
        if (mode == "FT8") {
            Ft8Console(client) { showLog = true }
            Spacer(Modifier.height(6.dp))
        }
        if (mode in CW_MODES) {
            CwConsole(client) { showLog = true }
            Spacer(Modifier.height(6.dp))
        }

        // ---- Levels ----
        Row(Modifier.fillMaxWidth()) {
            LabeledSlider("Volume", fields["AUDIO"]?.toIntOrNull() ?: 60, 0..100,
                modifier = Modifier.weight(1f)) { client.setVolume(it) }
            Spacer(Modifier.width(12.dp))
            LabeledSlider("IF gain", fields["IF"]?.toIntOrNull() ?: 60, 0..100,
                modifier = Modifier.weight(1f)) { client.setIfGain(it) }
        }
        Row(Modifier.fillMaxWidth()) {
            LabeledSlider("TX drive", fields["DRIVE"]?.toIntOrNull() ?: 40, 0..100, step = 5,
                modifier = Modifier.weight(1f)) { client.setDrive(it) }
            Spacer(Modifier.width(12.dp))
            LabeledSlider("Bandwidth", fields["BW"]?.toIntOrNull()
                    ?: ((fields["HIGH"]?.toIntOrNull() ?: 3000) - (fields["LOW"]?.toIntOrNull() ?: 300)),
                50..5000, step = 50, display = { "$it Hz" },
                modifier = Modifier.weight(1f)) { client.setBandwidth(it) }
        }
        if (mode in VOICE_MODES) {
            Row(Modifier.fillMaxWidth()) {
                FloatSlider(
                    "Phone mic", micGain, 0.25f..6f,
                    display = { "%.1fx".format(it) },
                    modifier = Modifier.weight(1f)
                ) { service.setPhoneMicGain(it) }
                Spacer(Modifier.width(12.dp))
                LabeledSlider("Compressor", fields["COMP"]?.toIntOrNull() ?: 0, 0..10,
                    display = { if (it == 0) "off" else "$it" },
                    modifier = Modifier.weight(1f)) { client.setComp(it) }
            }
        }
        if (mode in CW_MODES) {
            Row(Modifier.fillMaxWidth()) {
                LabeledSlider("WPM", fields["WPM"]?.toIntOrNull() ?: 12, 5..40,
                    modifier = Modifier.weight(1f)) { client.setWpm(it) }
                Spacer(Modifier.width(12.dp))
                LabeledSlider("Pitch", pitch, 300..1200, step = 10, display = { "$it Hz" },
                    modifier = Modifier.weight(1f)) { client.setPitch(it) }
            }
        }

        // ---- Receiver DSP + misc toggles (zBitx plugins) ----
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("ANR", "DSP", "NOTCH").forEach { lbl ->
                val on = fields[lbl] == "ON"
                SmallChip(lbl, selected = on) { client.setToggle(lbl, !on) }
            }
            val agc = fields["AGC"] ?: "SLOW"
            SmallChip("AGC $agc", selected = agc != "OFF") {
                val opts = listOf("OFF", "SLOW", "MED", "FAST")
                client.setAgc(opts[(opts.indexOf(agc) + 1).mod(opts.size)])
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val split = fields["SPLIT"] == "ON"
            SmallChip("SPLIT", selected = split) { client.setSplit(!split) }
            val lock = fields["VFOLK"] == "ON"
            SmallChip("LOCK", selected = lock) { client.setToggle("VFOLK", !lock) }
            val tuning = fields["TUNE"] == "ON"
            SmallChip(if (tuning) "TUNING…" else "TUNE ${fields["TNPWR"] ?: ""}".trim(),
                selected = tuning, warn = true) { client.tune(!tuning) }
        }
        Spacer(Modifier.height(4.dp))
    }

    // ================= KNOB =================
    val knob: @Composable (Dp) -> Unit = { size ->
        Box(contentAlignment = Alignment.Center) {
            TuningKnob(
                modifier = Modifier.size(size),
                onDelta = { steps -> if (fields["VFOLK"] != "ON") tuneBy(steps * selectedMult) }
            )
            Text("↺ ↻", fontSize = 14.sp, color = Color(0x66FFFFFF))
        }
    }

    // ================= PTT (phone modes only; CW/FT8 are keyed by the radio) =================
    val ptt: @Composable (Modifier) -> Unit = { mod ->
        val bg = when {
            onAir -> Color(0xFFB71C1C)
            pttHeld -> Color(0xFFE65100)       // pressed, radio not confirmed yet
            else -> Color(0xFF1B5E20)
        }
        Box(
            mod
                .background(bg, RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pttHeld = true
                            service.pttDown()
                            tryAwaitRelease()
                            pttHeld = false
                            service.pttUp()
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        onAir && pttHeld -> "ON AIR - release to RX"
                        onAir -> "ON AIR"
                        pttHeld -> "Keying…"
                        else -> "HOLD TO TALK (PTT)"
                    },
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                if (pttHeld) MicLevel(service)
                if (micError) Text("Phone mic unavailable - check the microphone permission",
                    color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
            }
        }
    }

    if (landscape) {
        // Controls on the left, knob + PTT on the right where the thumb is.
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                header()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.width(210.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                knob(if (mode in VOICE_MODES) 140.dp else 170.dp)
                if (mode in VOICE_MODES) ptt(Modifier.fillMaxWidth().height(110.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
            header()
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
            Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
                knob(if (mode in VOICE_MODES) 120.dp else 140.dp)
            }
            if (mode in VOICE_MODES) ptt(Modifier.fillMaxWidth().padding(top = 4.dp).height(76.dp))
        }
    }
}

/** e.g. "v1.02 (build 37)" - set by CI from the git tag and run number. */
fun appVersion(): String {
    val name = com.sbitx.remote.BuildConfig.VERSION_NAME
    val code = com.sbitx.remote.BuildConfig.VERSION_CODE
    return if (code > 1) "$name (build $code)" else name
}

/** S-meter normally; forward power and SWR while the radio is transmitting. */
@Composable
fun MeterRow(fields: Map<String, String>, onAir: Boolean, mode: String, step: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(mode, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            modifier = Modifier.padding(end = 8.dp))
        if (onAir) {
            val pwr = (fields["POWER"]?.toIntOrNull() ?: 0) / 10f
            val swr = (fields["REF"]?.toIntOrNull() ?: 10).coerceAtLeast(10) / 10f
            BarMeter((pwr / 5f).coerceIn(0f, 1f), Color(0xFFEF5350), Modifier.weight(1f))
            Text("  %.1f W  SWR %.1f".format(pwr, swr), fontSize = 12.sp,
                color = if (swr >= 3f) Color(0xFFFF5252) else Color.Unspecified)
        } else {
            val parts = (fields["SMETER"] ?: "0 0").split(' ')
            val s = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val db = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val frac = if (s < 9) s / 9f * 0.6f else 0.6f + db.coerceIn(0, 20) / 20f * 0.4f
            BarMeter(frac, if (s >= 9) Color(0xFFFFB74D) else Color(0xFF66BB6A), Modifier.weight(1f))
            Text(if (s >= 9 && db > 0) "  S9+$db" else "  S$s", fontSize = 12.sp,
                modifier = Modifier.width(56.dp))
        }
        Text("  step $step", fontSize = 11.sp, color = Color(0x99FFFFFF))
    }
}

@Composable
fun MicLevel(service: RadioService) {
    var peak by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { while (true) { peak = service.micPeak(); delay(60) } }
    BarMeter(peak, if (peak > 0.8f) Color(0xFFFFEB3B) else Color.White,
        Modifier.width(160.dp).padding(top = 4.dp))
}

private val BANDS = listOf(
    "80M" to (3_500_000L..4_000_000L), "60M" to (5_250_000L..5_450_000L),
    "40M" to (7_000_000L..7_300_000L), "30M" to (10_100_000L..10_150_000L),
    "20M" to (14_000_000L..14_350_000L), "17M" to (18_068_000L..18_168_000L),
    "15M" to (21_000_000L..21_450_000L), "12M" to (24_890_000L..24_990_000L),
    "10M" to (28_000_000L..29_700_000L),
)

/** Band selector (zBitx band buttons recall the band stack for that band). */
@Composable
fun BandDropdown(modifier: Modifier = Modifier, freq: Long, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = BANDS.firstOrNull { freq in it.second }?.first ?: "--"
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Band: $current  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BANDS.forEach { (b, _) ->
                DropdownMenuItem(text = { Text(b) }, onClick = {
                    expanded = false; onSelect(b)
                })
            }
        }
    }
}

@Composable
fun ModeDropdown(modifier: Modifier = Modifier, current: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Mode: $current  ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // r1:mode selection list in zBitx sbitx_gtk.c
            listOf("USB", "LSB", "AM", "CW", "CWR", "FT8", "DIGI").forEach { m ->
                DropdownMenuItem(text = { Text(m) }, onClick = {
                    expanded = false; onSelect(m)
                })
            }
        }
    }
}

@Composable
fun SmallChip(label: String, selected: Boolean, warn: Boolean = false, onClick: () -> Unit) {
    val on = if (warn) Color(0xFFE65100) else MaterialTheme.colorScheme.primary
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, if (selected) on else Color(0x55FFFFFF)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) on.copy(alpha = 0.25f) else Color.Transparent,
            contentColor = if (selected) Color.White else Color(0xCCFFFFFF)
        ),
        modifier = Modifier.height(30.dp)
    ) { Text(label, fontSize = 11.sp, maxLines = 1) }
}

@Composable
fun LabeledSlider(
    label: String, value: Int, range: IntRange, step: Int = 1,
    display: (Int) -> String = { it.toString() },
    modifier: Modifier = Modifier, onSet: (Int) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value.toFloat()) }
    // follow the radio unless the user is mid-drag
    LaunchedEffect(value) { if (!dragging) local = value.toFloat() }
    Column(modifier) {
        Text("$label: ${display(((local.roundToInt()) / step) * step)}", fontSize = 11.sp)
        Slider(
            value = local.coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { dragging = true; local = it },
            onValueChangeFinished = {
                dragging = false
                onSet((local.roundToInt() / step) * step)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.height(26.dp)
        )
    }
}

@Composable
fun FloatSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String, modifier: Modifier = Modifier, onSet: (Float) -> Unit
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier) {
        Text("$label: ${display(local)}", fontSize = 11.sp)
        Slider(
            value = local, onValueChange = { local = it },
            onValueChangeFinished = { onSet(local) },
            valueRange = range, modifier = Modifier.height(26.dp)
        )
    }
}

@Composable
fun TuneBtn(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(40.dp)
    ) { Text(label, fontSize = 12.sp) }
}

fun stepName(mult: Long): String = when (mult) {
    10_000_000L -> "10 MHz"; 1_000_000L -> "1 MHz"
    100_000L -> "100 kHz"; 10_000L -> "10 kHz"; 1_000L -> "1 kHz"
    100L -> "100 Hz"; 10L -> "10 Hz"; else -> "1 Hz"
}
