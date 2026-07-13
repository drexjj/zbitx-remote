package com.sbitx.remote.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client for the sBitx (drexjj fork, v5.x) web remote protocol.
 *
 * Protocol (derived from src/webserver.c in github.com/drexjj/sbitx):
 *  - Single WebSocket at ws://<host>:8080/websocket (or wss://<host>:8443/websocket)
 *  - TEXT frames, client -> radio:  "<cookie>\n<field>=<value>"  (max 99 chars)
 *      - login:            "nullsession\nlogin=<PIN>"
 *      - any radio command: field/value passed to remote_execute(), e.g. "freq=7100000"
 *      - keywords: refresh / spectrum / audio / logbook / macros_list
 *  - TEXT frames, radio -> client: "LABEL value" lines (e.g. "FREQ 7100000",
 *      "SMETER 5 3", "STATUS 2026/07/07 12:00:00Z", "login <cookie>", "login error",
 *      "quit <reason>", spectrum frames starting with "RX "/"TX ")
 *  - BINARY frames, radio -> client: int16 little-endian PCM, 16 kHz mono (RX audio)
 *  - BINARY frames, client -> radio: int16 little-endian PCM, 8 kHz mono (browser/phone mic)
 */
class SbitxClient(
    private val host: String,
    private val port: Int = 8080,
    private val useTls: Boolean = false,
) {
    enum class ConnState { DISCONNECTED, CONNECTING, LOGIN_SENT, CONNECTED, AUTH_FAILED, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: WebSocket? = null
    private var cookie: String = "nullsession"
    private var audioPollJob: Job? = null

    /** Human-readable reason for the last ERROR/DISCONNECT, for the UI. */
    val lastError = MutableStateFlow<String?>(null)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(2, TimeUnit.SECONDS)   // server pings every 2s and drops after 5s idle
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply {
            if (useTls) {
                // The sBitx (drexjj) firmware ships a self-signed certificate on
                // port 8443 and 302-redirects all non-localhost HTTP traffic to it,
                // so remote clients MUST use TLS and MUST accept that cert.
                // Transport privacy over the internet is provided by Tailscale.
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val ssl = SSLContext.getInstance("TLS")
                ssl.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                sslSocketFactory(ssl.socketFactory, trustAll)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    /** Connection state for the UI. */
    val state = MutableStateFlow(ConnState.DISCONNECTED)

    /** Parsed "LABEL value" field updates (FREQ, MODE, MIC, DRIVE, SMETER, ...). */
    private val _fields = MutableStateFlow<Map<String, String>>(emptyMap())
    val fields: StateFlow<Map<String, String>> = _fields

    /** Raw RX PCM (int16 LE @ 16 kHz mono) frames from the radio. */
    private val _rxAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val rxAudio: SharedFlow<ByteArray> = _rxAudio

    /** Parsed console lines: FT8/CW decodes, TX confirmations, logs. */
    data class ConsoleLine(val kind: String, val line: Int, val text: String)

    private val _console = MutableSharedFlow<ConsoleLine>(extraBufferCapacity = 256)
    val console: SharedFlow<ConsoleLine> = _console

    // e.g. <WSJTX-RX l="42">102400 -15 0.2 1440 ~ CQ YH1AB OI33</WSJTX-RX>
    private val consoleTag = Regex(
        """<([A-Z0-9\-]+)(?:\s+l="(\d+)")?>(.*?)</\1>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Spectrum frames ("RX ..." / "TX ..." ASCII-encoded bins). */
    private val _spectrum = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val spectrum: SharedFlow<String> = _spectrum

    fun connect(pin: String) {
        if (state.value == ConnState.CONNECTING || state.value == ConnState.CONNECTED) return
        state.value = ConnState.CONNECTING
        val scheme = if (useTls) "wss" else "ws"
        val req = Request.Builder().url("$scheme://$host:$port/websocket").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                cookie = "nullsession"
                webSocket.send("nullsession\nlogin=$pin")
                state.value = ConnState.LOGIN_SENT
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                _rxAudio.tryEmit(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError.value = when {
                    response?.code == 302 -> "Radio redirected to HTTPS - enable TLS and use port 8443"
                    else -> t.message ?: "Connection failed"
                }
                state.value = ConnState.ERROR
                teardown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                teardown()
            }
        })
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        teardown()
    }

    private fun teardown() {
        audioPollJob?.cancel()
        audioPollJob = null
        ws = null
        if (state.value != ConnState.AUTH_FAILED && state.value != ConnState.ERROR)
            state.value = ConnState.DISCONNECTED
    }

    /** Send any sBitx command, e.g. sendCommand("freq", "7100000") or sendCommand("tx"). */
    fun sendCommand(field: String, value: String = "") {
        val w = ws ?: return
        val msg = "$cookie\n$field=$value"
        if (msg.length <= 99) w.send(msg)     // server rejects >99 chars
    }

    // --- Convenience wrappers for standard radio features ---
    fun setFrequency(hz: Long) = sendCommand("freq", hz.toString())
    fun setMode(mode: String) = sendCommand("mode", mode)          // USB/LSB/CW/CWR/AM/FT8/...
    fun setMicGain(g: Int) = sendCommand("mic", g.coerceIn(0, 100).toString())
    // NOTE: must be uppercase - lowercase "audio" is the webserver's reserved
    // keyword for requesting the RX audio stream and never reaches the field.
    fun setVolume(v: Int) = sendCommand("AUDIO", v.coerceIn(0, 100).toString())
    fun setDrive(d: Int) = sendCommand("drive", d.coerceIn(1, 100).toString())
    fun setBandwidth(hz: Int) = sendCommand("bw", hz.toString())
    fun setAgc(agc: String) = sendCommand("agc", agc)              // OFF/SLOW/MED/FAST
    fun setIfGain(g: Int) = sendCommand("if", g.toString())
    fun setBand(band: String) = sendCommand(band, "")              // "80M", "40M", "20M", ...
    fun setVfo(vfo: String) = sendCommand("vfo", vfo)              // A / B
    fun setStep(step: String) = sendCommand("step", step)          // 10K/1K/100H/10H
    fun setRit(on: Boolean) = sendCommand("rit", if (on) "ON" else "OFF")
    fun setSplit(on: Boolean) = sendCommand("split", if (on) "ON" else "OFF")

    fun ptt(on: Boolean) = sendCommand(if (on) "tx" else "rx")

    /** AINR: RNNoise neural noise reduction (vis4573/sbitx firmware). */
    fun setAinr(on: Boolean) = sendCommand("ainr", if (on) "ON" else "OFF")

    /** AINR strength 0-100 (step 5). Takes ~1 s to apply on the radio. */
    fun setAinrStrength(n: Int) =
        sendCommand("ainrs", ((n.coerceIn(0, 100) / 5) * 5).toString())

    /** AINR VAD relax depth 0-50 (step 5). */
    fun setAinrRelax(n: Int) =
        sendCommand("ainrv", ((n.coerceIn(0, 50) / 5) * 5).toString())

    fun refresh() = sendCommand("refresh")

    /** Send a raw console line (no field=value), e.g. "key CQ VU3UBP MK68". */
    fun sendRaw(line: String) {
        val w = ws ?: return
        val msg = "$cookie\n$line"
        if (msg.length <= 99) w.send(msg)
    }

    /** Queue an FT8 message for transmission in the next time slot. */
    fun ft8Transmit(message: String) = sendRaw("key " + message.trim() + "\n")

    /** FT8 auto-operate mode: OFF, CQRESP (answer CQs), ANS (answer replies). */
    fun setFt8Auto(modeStr: String) = sendCommand("FTX_AUTO", modeStr)

    /** Stream one chunk of mic PCM (int16 LE @ 8 kHz mono) while transmitting. */
    fun sendMicAudio(pcm: ByteArray) {
        ws?.send(pcm.toByteString())
    }

    private fun handleText(text: String) {
        // Multiple logical messages may arrive; the console can also contain newlines.
        when {
            text.startsWith("login ") -> {
                val v = text.removePrefix("login ").trim()
                if (v == "error") {
                    state.value = ConnState.AUTH_FAILED
                    ws?.close(1000, "auth failed")
                } else {
                    cookie = v
                    state.value = ConnState.CONNECTED
                    refresh()
                    startAudioPolling()
                }
            }
            text.startsWith("quit ") -> {
                state.value = ConnState.DISCONNECTED
                ws?.close(1000, "server quit")
            }
            text.startsWith("RX ") || text.startsWith("TX ") -> _spectrum.tryEmit(text)
            text.startsWith("CONSOLE ") -> {
                val payload = text.removePrefix("CONSOLE ")
                for (m in consoleTag.findAll(payload)) {
                    val kind = m.groupValues[1]
                    val line = m.groupValues[2].toIntOrNull() ?: -1
                    val body = m.groupValues[3]
                        .replace("&lt;", "<").replace("&gt;", ">")
                        .replace("&amp;", "&").trim()
                    if (body.isNotEmpty()) _console.tryEmit(ConsoleLine(kind, line, body))
                }
            }
            else -> {
                // Generic "LABEL value" field update
                val sp = text.indexOf(' ')
                if (sp > 0) {
                    val label = text.substring(0, sp)
                    val value = text.substring(sp + 1)
                    _fields.value = _fields.value + (label to value)
                } else if (text.isNotBlank()) {
                    _fields.value = _fields.value + (text to "")
                }
            }
        }
    }

    /**
     * The radio only pushes RX audio in response to "audio" requests
     * (see get_audio() in webserver.c), so poll continuously while connected.
     */
    private fun startAudioPolling() {
        audioPollJob?.cancel()
        audioPollJob = scope.launch {
            while (state.value == ConnState.CONNECTED) {
                sendCommand("audio")
                delay(50)
            }
        }
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }
}
