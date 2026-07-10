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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
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
        Text("Status: $state   •   v0.2", style = MaterialTheme.typography.bodySmall)
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        // ---- Frequency readout + tuning ----
        Text(
            formatFreq(freq),
            fontSize = 40.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Mode: $mode    S: $smeter", textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(-1000L, -100L, +100L, +1000L).forEach { d ->
                OutlinedButton(onClick = { client.setFrequency(freq + d) }) {
                    Text(if (d > 0) "+${d / 100}" else "${d / 100}")
                }
            }
        }
        FreqEntryRow { client.setFrequency(it) }
        Spacer(Modifier.height(8.dp))

        // ---- Bands ----
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(listOf("80M", "40M", "30M", "20M", "17M", "15M", "12M", "10M")) { b ->
                OutlinedButton(onClick = { client.setBand(b) }, contentPadding = PaddingValues(4.dp)) {
                    Text(b, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // ---- Mode ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("LSB", "USB", "CW", "AM").forEach { m ->
                FilterChip(selected = mode == m, onClick = { client.setMode(m) },
                    label = { Text(m) })
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- Sliders ----
        LabeledSlider("Mic gain", mic, 0..100) { client.setMicGain(it) }
        LabeledSlider("Volume", vol, 0..100) { client.setVolume(it) }
        LabeledSlider("Drive (power)", drive, 1..100) { client.setDrive(it) }
        LabeledSlider("Bandwidth", bw, 300..5000, step = 100) { client.setBandwidth(it) }

        Spacer(Modifier.height(16.dp))

        // ---- PTT (press & hold) ----
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
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

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { service.disconnect() }, modifier = Modifier.fillMaxWidth()) {
            Text("Disconnect")
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Int, range: IntRange, step: Int = 1, onSet: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text("$label: ${local.toInt()}")
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSet((local.toInt() / step) * step) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
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
