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
 * Client for the zBitx web remote protocol (src/webserver.c + web/index.html
 * in github.com/drexjj/zbitx). Everything below was checked against that code.
 *
 *  - One WebSocket at wss://<host>:8443/websocket (port 8080 plain HTTP only
 *    works from localhost; anything else gets a 302 to https://zbitx.local:8443).
 *  - TEXT client -> radio: "<cookie>\n<command>", 99 chars max.
 *      The server splits <command> at the first '=' into field/value and hands
 *      "field value" to cmd_exec(), so "freq=7074000" and "freq 7074000" are
 *      equivalent. The FIELD PART MUST BE 2+ CHARS or the server replies
 *      "quit Illformed request" and drops the socket - which is why the zBitx
 *      web UI keys the radio with "t " / "r " (note the trailing space).
 *      Reserved exact-match keywords: login, audio, spectrum, refresh, logbook,
 *      macros_list, BFO. Volume must therefore be sent as "AUDIO", never "audio".
 *  - TEXT radio -> client: "LABEL value" (FREQ, MODE, SMETER s db, POWER, REF,
 *      ZEROBEAT, STATUS, ...), "login <cookie>" / "login error",
 *      "quit <reason>", "RX <bins>" / "TX <envelope>" spectrum frames, and
 *      "CONSOLE <WSJTX-RX>..</WSJTX-RX><CW-RX>..</CW-RX>..." (XML-ish, entity
 *      escaped, no line ids, and a tag may be split across two frames).
 *  - BINARY radio -> client: int16 LE PCM, 16 kHz mono (sent only in reply to "audio").
 *  - BINARY client -> radio: int16 LE PCM, 8 kHz mono browser mic. The radio
 *      falls back to its own mic if no frame arrives for 100 ms.
 *  - zBitx keeps ONE session cookie: a login from any other device invalidates
 *      ours and the next request is answered with "quit expired".
 */
class SbitxClient(
    private val host: String,
    private val port: Int = 8443,
    private val useTls: Boolean = true,
) {
    enum class ConnState {
        DISCONNECTED, CONNECTING, LOGIN_SENT, CONNECTED,
        /** Link dropped after a good session; retrying with backoff. */
        RECONNECTING,
        AUTH_FAILED,
        /** The radio ended the session (another login, malformed request). Not retried. */
        SESSION_ENDED,
        ERROR
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var cookie: String = "nullsession"
    private var audioPollJob: Job? = null
    private var reconnectJob: Job? = null
    private var pin: String = ""

    @Volatile private var userClosed = false
    @Volatile private var everConnected = false
    @Volatile private var reconnectAttempt = 0
    /** Set when the link drops while the radio was keyed, so we can unkey on reconnect. */
    @Volatile private var unkeyOnReconnect = false

    /** True while the user is holding PTT in this app. */
    @Volatile var pttHeld = false
        private set

    /** Human-readable reason for the last ERROR / SESSION_ENDED / RECONNECTING. */
    val lastError = MutableStateFlow<String?>(null)

    private val http: OkHttpClient = OkHttpClient.Builder()
        // We poll "audio" every 50 ms, which already keeps the server's 5 s idle
        // timer happy. This ping only detects a dead link; 2 s was so aggressive
        // that a brief cellular stall tore the session down.
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .apply {
            if (useTls) {
                // zBitx ships a self-signed certificate for port 8443. Transport
                // privacy over the internet comes from Tailscale.
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

    val state = MutableStateFlow(ConnState.DISCONNECTED)

    /** Latest value of every "LABEL value" update (FREQ, MODE, MIC, DRIVE, SMETER, ...). */
    private val _fields = MutableStateFlow<Map<String, String>>(emptyMap())
    val fields: StateFlow<Map<String, String>> = _fields

    /** True while the radio reports TX spectrum frames, i.e. it is really on the air. */
    private val _onAir = MutableStateFlow(false)
    val onAir: StateFlow<Boolean> = _onAir

    /** Raw RX PCM (int16 LE @ 16 kHz mono) frames from the radio. */
    private val _rxAudio = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val rxAudio: SharedFlow<ByteArray> = _rxAudio

    /**
     * One console entry. [kind] is the zBitx tag: WSJTX-RX, WSJTX-TX, WSJTX-Q,
     * CW-RX, CW-TX, FLDIGI-RX, FLDIGI-TX, TELNET or LOG. [text] still carries
     * the firmware's "#X" colour markup (see hist_disp.c).
     */
    data class ConsoleLine(val kind: String, val text: String)

    private val _console = MutableSharedFlow<ConsoleLine>(extraBufferCapacity = 512)
    val console: SharedFlow<ConsoleLine> = _console

    private val consoleTag = Regex("""<([A-Z0-9\-]+)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val consoleCarry = StringBuilder()

    /** Spectrum frames ("RX ..." / "TX ..." ASCII-encoded bins). */
    private val _spectrum = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val spectrum: SharedFlow<String> = _spectrum

    fun connect(pin: String) {
        if (state.value == ConnState.CONNECTING || state.value == ConnState.CONNECTED) return
        this.pin = pin
        userClosed = false
        everConnected = false
        reconnectAttempt = 0
        lastError.value = null
        open(ConnState.CONNECTING)
    }

    private fun open(newState: ConnState) {
        state.value = newState
        val scheme = if (useTls) "wss" else "ws"
        val req = Request.Builder().url("$scheme://$host:$port/websocket").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== ws) return
                cookie = "nullsession"
                synchronized(consoleCarry) { consoleCarry.setLength(0) }
                webSocket.send("nullsession\nlogin=$pin")
                if (state.value != ConnState.RECONNECTING) state.value = ConnState.LOGIN_SENT
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket === ws) handleText(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket === ws) _rxAudio.tryEmit(bytes.toByteArray())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                val reason = when {
                    response?.code == 302 ->
                        "The radio redirected to HTTPS - turn on TLS and use port 8443"
                    else -> t.message ?: "Connection failed"
                }
                linkLost(reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                linkLost(reason.ifBlank { "Connection closed by the radio" })
            }
        })
    }

    /** The socket is gone. Decide between retrying, reporting an error, or staying down. */
    private fun linkLost(reason: String) {
        stopAudioPolling()
        ws = null
        if (_onAir.value || pttHeld) unkeyOnReconnect = true
        _onAir.value = false
        when {
            userClosed -> state.value = ConnState.DISCONNECTED
            state.value == ConnState.AUTH_FAILED || state.value == ConnState.SESSION_ENDED -> Unit
            everConnected -> scheduleReconnect(reason)
            else -> {
                lastError.value = reason
                state.value = ConnState.ERROR
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        reconnectJob?.cancel()
        val waitS = listOf(1, 2, 4, 8, 15)[reconnectAttempt.coerceAtMost(4)]
        reconnectAttempt++
        lastError.value = "Link lost ($reason) - retrying in ${waitS}s"
        state.value = ConnState.RECONNECTING
        reconnectJob = scope.launch {
            delay(waitS * 1000L)
            if (!userClosed && state.value == ConnState.RECONNECTING) open(ConnState.RECONNECTING)
        }
    }

    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        stopAudioPolling()
        if (pttHeld || _onAir.value) runCatching { sendRaw("r ") }
        pttHeld = false
        ws?.close(1000, "bye")
        ws = null
        _onAir.value = false
        if (state.value != ConnState.AUTH_FAILED && state.value != ConnState.SESSION_ENDED)
            state.value = ConnState.DISCONNECTED
    }

    // ---------------------------------------------------------------- sending

    /** Send "field=value" (or a bare keyword when value is empty). Field must be 2+ chars. */
    fun sendCommand(field: String, value: String = "") {
        if (field.length < 2) return            // the radio would drop the socket
        send(if (value.isEmpty()) field else "$field=$value")
    }

    /** Send a raw command line exactly as the zBitx web UI would, e.g. "t " or "FT8 ...". */
    fun sendRaw(line: String) = send(line)

    private fun send(command: String) {
        val w = ws ?: return
        val msg = "$cookie\n$command"
        if (msg.length <= 99) w.send(msg)       // server ignores frames > 99 chars
    }

    // --- Standard radio controls (labels from main_controls[] in sbitx_gtk.c) ---
    fun setFrequency(hz: Long) = sendCommand("freq", hz.toString())
    fun setMode(mode: String) = sendCommand("MODE", mode)          // USB/LSB/AM/CW/CWR/FT8/DIGI/2TONE
    fun setMicGain(g: Int) = sendCommand("MIC", g.coerceIn(0, 50).toString())
    fun setVolume(v: Int) = sendCommand("AUDIO", v.coerceIn(0, 100).toString())
    fun setDrive(d: Int) = sendCommand("DRIVE", d.coerceIn(0, 100).toString())
    fun setBandwidth(hz: Int) = sendCommand("BW", hz.coerceIn(50, 5000).toString())
    fun setAgc(agc: String) = sendCommand("AGC", agc)              // OFF/SLOW/MED/FAST
    fun setIfGain(g: Int) = sendCommand("IF", g.coerceIn(0, 100).toString())
    fun setBand(band: String) = sendCommand(band)                  // "80M" ... "10M"
    fun setVfo(vfo: String) = sendCommand("VFO", vfo)              // A / B
    fun setSpan(span: String) = sendCommand("SPAN", span)          // 25K/10K/8K/6K/2.5K
    fun setRit(on: Boolean) = setToggle("RIT", on)
    fun setSplit(on: Boolean) = setToggle("SPLIT", on)
    fun setPitch(hz: Int) = sendCommand("PITCH", hz.coerceIn(100, 3000).toString())
    fun setTxPitch(hz: Int) = sendCommand("TX_PITCH", hz.coerceIn(100, 4000).toString())
    fun setWpm(wpm: Int) = sendCommand("WPM", wpm.coerceIn(1, 50).toString())
    fun setComp(level: Int) = sendCommand("COMP", level.coerceIn(0, 10).toString())

    /** zBitx ON/OFF toggles: ANR, DSP, NOTCH, TXEQ, RXEQ, VFOLK, FT8_AUTO, ... */
    fun setToggle(label: String, on: Boolean) = sendCommand(label, if (on) "ON" else "OFF")

    /** Key / unkey exactly the way the zBitx web UI does ("t " / "r "). */
    fun ptt(on: Boolean) {
        pttHeld = on
        sendRaw(if (on) "t " else "r ")
    }

    /** Antenna tune carrier at TNPWR for TNDUR seconds (radio auto-stops). */
    fun tune(on: Boolean) = sendCommand("TUNE", if (on) "ON" else "OFF")

    /** Stop any transmission/macro in progress (same as the web UI's abort). */
    fun abortTx() = sendCommand("abort", "1")

    fun refresh() = sendCommand("refresh")

    /** Type text into the radio's keyboard buffer - CW messages are sent from there. */
    fun sendKeys(text: String) {
        // keep each frame under the 99-char limit
        text.chunked(60).forEach { sendRaw("key $it") }
    }

    /** Queue a free-form FT8 message for the next slot (web UI's FT8_transmit()). */
    fun ft8Transmit(message: String) = sendRaw("key " + message.trim() + "\n")

    /**
     * Start (or resume) an FT8 QSO from a decode line, exactly like tapping it in
     * the zBitx web UI: the radio's ft8_process() fills in the logger and runs
     * the exchange itself (with FT8_AUTO on it also logs the contact).
     * [decodeText] is the raw console text, markup included.
     */
    fun ft8Reply(decodeText: String) {
        val normalized = normalizeFt8(decodeText) ?: return
        abortTx()
        sendRaw("FT8 $normalized")
    }

    fun setFt8Auto(on: Boolean) = setToggle("FT8_AUTO", on)

    // ------------------------------------------------------------- macros
    //
    // zBitx macros are N1MM-style .mc files in ~/sbitx/web on the Pi
    // ("F1 CQ,cq cq de {MYCALL} k"). The radio holds ONE loaded file at a time
    // (field MACRO). "MACRO=<name>" loads a file and the radio broadcasts the
    // new key labels as fields F1..F12. "F<n>" runs a key on the radio, which
    // expands the variables (! = CALL, * = MYCALL, {SENTRST} = SENT, {EXCH} = NR,
    // {GRIDSQUARE}, ...) from its own logger and then keys CW from the text
    // buffer, or queues the message for the next FT8 slot.

    /** One F-key definition parsed from a .mc file (for previews). */
    data class MacroKey(val key: Int, val label: String, val text: String)

    private val _macroFiles = MutableStateFlow<List<String>>(emptyList())
    /** Names of the .mc files on the radio (without extension). */
    val macroFiles: StateFlow<List<String>> = _macroFiles

    private val _macroDefs = MutableStateFlow<Map<String, List<MacroKey>>>(emptyMap())
    /** Parsed contents of .mc files fetched so far, by file name. */
    val macroDefs: StateFlow<Map<String, List<MacroKey>>> = _macroDefs

    /** Ask the radio for its list of macro files (reply: "macros_list A|B|"). */
    fun requestMacroFiles() = sendCommand("macros_list")

    /** Load a macro file on the radio; F1..F12 then run from that file. */
    fun loadMacroFile(name: String) {
        if (name.isBlank() || name.length > 40) return
        sendCommand("MACRO", name)
        fetchMacroFile(name)
    }

    /** Run F-key [n] (1..12) from the radio's loaded macro file. */
    fun runMacro(n: Int) {
        if (n in 1..12) sendRaw("F$n")
    }

    /**
     * Download <name>.mc from the radio's web server - the same file the zBitx
     * web UI fetches - so the app can show what each key will send. Labels on
     * the buttons come from the radio's own F1..F12 fields, so a failure here
     * only loses the previews.
     */
    fun fetchMacroFile(name: String) {
        if (name.isBlank() || _macroDefs.value.containsKey(name)) return
        scope.launch {
            val scheme = if (useTls) "https" else "http"
            val url = okhttp3.HttpUrl.Builder()
                .scheme(scheme).host(host).port(port)
                .addPathSegment("$name.mc").build()
            runCatching {
                // the WebSocket client has no read timeout; a file fetch should
                http.newBuilder().readTimeout(6, TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            }.getOrNull()?.let { body ->
                _macroDefs.value = _macroDefs.value + (name to parseMacroFile(body))
            }
        }
    }

    // ------------------------------------------------------------- logger
    // The radio's logger fields feed the macros (! = CALL, {SENTRST} = SENT,
    // {EXCH} = NR). Values are upper-cased by the radio; CALL max 11 chars,
    // the others max 7.

    fun setLogField(label: String, value: String) {
        val max = if (label == "CALL") 11 else 7
        sendCommand(label, value.trim().uppercase().filter { it > ' ' && it != '=' }.take(max))
    }

    /** Log the QSO from the radio's logger fields (needs CALL, SENT and RECV). */
    fun saveQso() = sendCommand("SAVE")

    /** Clear the radio's logger fields. */
    fun wipeLogger() = sendCommand("WIPE")

    // ------------------------------------------------------------ logbook
    // The radio's SQLite logbook (logbook.c logbook_query()):
    //   "logbook=0 [prefix]"   -> latest 50 QSOs (optionally callsign prefix)
    //   "logbook=<id> [prefix]"  -> 50 QSOs older than <id>
    //   "logbook=-<id> [prefix]" -> QSOs newer than <id>
    // Each comes back as "QSO id|mode|freq|date|time|mycall|rst_sent|exch_sent|
    //   call|rst_recv|exch_recv|comments|".

    data class Qso(
        val id: Int, val mode: String, val freq: String, val date: String, val time: String,
        val rstSent: String, val exchSent: String,
        val call: String, val rstRecv: String, val exchRecv: String, val comment: String,
    )

    private val _logbook = MutableStateFlow<List<Qso>>(emptyList())
    /** QSOs received so far, newest first. */
    val logbook: StateFlow<List<Qso>> = _logbook

    /** Current callsign-prefix filter for the logbook ("" = all). */
    @Volatile var logbookQuery: String = ""
        private set

    /** Load the latest 50 QSOs, replacing what is shown. [prefix] filters by callsign. */
    fun loadLogbook(prefix: String = logbookQuery) {
        logbookQuery = prefix.uppercase().filter { it.isLetterOrDigit() || it == '/' }.take(12)
        _logbook.value = emptyList()
        requestLogbook(0)
    }

    /** Load the next 50 older QSOs. */
    fun loadOlderQsos() {
        val oldest = _logbook.value.lastOrNull()?.id ?: return
        if (oldest > 1) requestLogbook(oldest)
    }

    /** Fetch QSOs logged since the newest one shown (after a "Logged:" message). */
    fun loadNewerQsos() {
        val newest = _logbook.value.firstOrNull()?.id ?: return requestLogbook(0)
        requestLogbook(-newest)
    }

    private fun requestLogbook(fromId: Int) {
        // the server crashes on "logbook" without a value, so always send one
        sendCommand("logbook", if (logbookQuery.isEmpty()) "$fromId" else "$fromId $logbookQuery")
    }

    private fun parseQso(line: String) {
        val t = line.trimEnd('\n', '\r').split('|')
        if (t.size < 11) return
        val id = t[0].trim().toIntOrNull() ?: return
        val q = Qso(id, t[1], t[2], t[3], t[4], t[6], t[7], t[8], t[9], t[10], t.getOrElse(11) { "" })
        val merged = (_logbook.value.filter { it.id != id } + q).sortedByDescending { it.id }
        _logbook.value = merged.take(2000)
    }

    /** Stream one chunk of mic PCM (int16 LE @ 8 kHz mono) while transmitting. */
    fun sendMicAudio(pcm: ByteArray) {
        ws?.send(pcm.toByteString())
    }

    // -------------------------------------------------------------- receiving

    private fun handleText(text: String) {
        when {
            text.startsWith("login ") -> {
                val v = text.removePrefix("login ").trim()
                if (v == "error") {
                    lastError.value = "The radio rejected the PIN"
                    state.value = ConnState.AUTH_FAILED
                    ws?.close(1000, "auth failed")
                } else {
                    val wasReconnect = state.value == ConnState.RECONNECTING
                    cookie = v
                    everConnected = true
                    reconnectAttempt = 0
                    lastError.value = null
                    state.value = ConnState.CONNECTED
                    // Safety: if the link died while we were keyed, the radio keeps
                    // transmitting (on its own mic). Unkey unless PTT is still held.
                    if (wasReconnect && unkeyOnReconnect && !pttHeld) sendRaw("r ")
                    unkeyOnReconnect = false
                    requestMacroFiles()
                    startAudioPolling()
                }
            }
            text.startsWith("quit") -> {
                val reason = text.removePrefix("quit").trim()
                lastError.value = when (reason) {
                    "expired" -> "Another device logged in to the radio. zBitx allows one remote session at a time."
                    else -> "The radio ended the session: $reason"
                }
                state.value = ConnState.SESSION_ENDED
                ws?.close(1000, "server quit")
            }
            text.startsWith("QSO ") -> parseQso(text.removePrefix("QSO "))
            text.startsWith("macros_list") -> {
                _macroFiles.value = text.removePrefix("macros_list").trim()
                    .split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    .distinct().sortedBy { it.lowercase() }
            }
            text.startsWith("RX ") -> { _onAir.value = false; _spectrum.tryEmit(text) }
            text.startsWith("TX ") -> { _onAir.value = true; _spectrum.tryEmit(text) }
            text.startsWith("CONSOLE ") -> parseConsole(text.removePrefix("CONSOLE "))
            else -> {
                val sp = text.indexOf(' ')
                if (sp > 0) {
                    _fields.value = _fields.value + (text.substring(0, sp) to text.substring(sp + 1))
                } else if (text.isNotBlank()) {
                    _fields.value = _fields.value + (text to "")
                }
            }
        }
    }

    // ---- Console history, kept here (not in the UI) so it survives screen
    // rotation and switching modes.

    /** One FT8 line: kind = WSJTX-RX / WSJTX-TX / WSJTX-Q, text keeps the colour markup. */
    data class Ft8Line(val kind: String, val text: String, val key: String)

    private val _ft8Lines = MutableStateFlow<List<Ft8Line>>(emptyList())
    val ft8Lines: StateFlow<List<Ft8Line>> = _ft8Lines

    /** CW/FLDIGI text as (isTx, text) runs. */
    private val _cwText = MutableStateFlow<List<Pair<Boolean, String>>>(emptyList())
    val cwText: StateFlow<List<Pair<Boolean, String>>> = _cwText

    /** Last plain LOG line from the radio (e.g. "Logged: ..."). */
    private val _lastLogLine = MutableStateFlow("")
    val lastLogLine: StateFlow<String> = _lastLogLine

    private fun recordConsole(kind: String, body: String) {
        when {
            kind == "LOG" -> stripMarkup(body).trim().takeIf { it.isNotEmpty() }?.let { _lastLogLine.value = it }
            kind.startsWith("WSJTX") -> {
                val text = body.trimEnd('\n', ' ')
                if (text.length < 20) return
                // de-duplicate like the web UI: everything from '~' on is the message
                val plain = stripMarkup(text)
                val key = plain.substringAfter('~', plain).replace(Regex("\\s+"), " ").trim()
                val list = _ft8Lines.value.toMutableList()
                val existing = list.indexOfLast { it.key == key && it.kind == kind }
                if (existing >= 0 && existing >= list.size - 30) list.removeAt(existing)
                list.add(Ft8Line(kind, text, key))
                _ft8Lines.value = list.takeLast(200)
            }
            kind == "CW-RX" || kind == "FLDIGI-RX" || kind == "CW-TX" || kind == "FLDIGI-TX" -> {
                val tx = kind.endsWith("TX")
                val t = stripMarkup(body)
                val list = _cwText.value.toMutableList()
                if (list.isNotEmpty() && list.last().first == tx)
                    list[list.size - 1] = tx to (list.last().second + t).takeLast(1500)
                else list.add(tx to t)
                _cwText.value = list.takeLast(40)
            }
        }
    }

    /** Console frames are capped at 2000 chars, so a tag can straddle two frames. */
    private fun parseConsole(payload: String) {
        val buf: String
        synchronized(consoleCarry) {
            consoleCarry.append(payload)
            buf = consoleCarry.toString()
            consoleCarry.setLength(0)
        }
        var consumed = 0
        for (m in consoleTag.findAll(buf)) {
            consumed = m.range.last + 1
            val body = decodeEntities(m.groupValues[2])
            if (body.isNotBlank()) {
                _console.tryEmit(ConsoleLine(m.groupValues[1], body))
                recordConsole(m.groupValues[1], body)
            }
            // a QSO was just saved on the radio: pull it into the logbook view
            if (m.groupValues[1] == "LOG" && stripMarkup(body).trimStart().startsWith("Logged:")
                && _logbook.value.isNotEmpty()) loadNewerQsos()
        }
        // keep an unfinished tag for the next frame (bounded, in case of garbage)
        val rest = buf.substring(consumed)
        val open = rest.indexOf('<')
        if (open >= 0 && rest.length - open < 4000)
            synchronized(consoleCarry) { consoleCarry.append(rest, open, rest.length) }
    }

    private fun startAudioPolling() {
        audioPollJob?.cancel()
        // The radio only sends RX audio (and fresh spectrum/fields) in reply
        // to "audio", so poll at the same 50 ms cadence as the web UI's ui_tick().
        audioPollJob = scope.launch {
            while (state.value == ConnState.CONNECTED) {
                sendCommand("audio")
                delay(50)
            }
        }
    }

    private fun stopAudioPolling() {
        audioPollJob?.cancel()
        audioPollJob = null
    }

    fun shutdown() {
        disconnect()
        scope.cancel()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        fun decodeEntities(s: String): String = s
            .replace("&#xA;", "\n").replace("&#xa;", "\n").replace("&#10;", "\n")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")

        /**
         * Parse a .mc file the way macros.c macro_load() does: lines starting
         * with F<n>, then the label up to the first comma, then the macro text.
         * Comment and malformed lines are skipped; the first definition of a
         * key wins (that's the one macro_exec() runs).
         */
        fun parseMacroFile(body: String): List<MacroKey> {
            val out = LinkedHashMap<Int, MacroKey>()
            for (raw in body.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (!line.startsWith("F")) continue
                val keyEnd = line.indexOf(' ').takeIf { it > 1 } ?: continue
                val key = line.substring(1, keyEnd).toIntOrNull() ?: continue
                val rest = line.substring(keyEnd).trimStart()
                val comma = rest.indexOf(',')
                if (comma < 0) continue
                if (key !in out) out[key] = MacroKey(key, rest.substring(0, comma).trim().take(30),
                    rest.substring(comma + 1).trim())
            }
            return out.values.sortedBy { it.key }
        }

        /** Remove the firmware's "#X" colour markup. */
        fun stripMarkup(s: String): String = s.replace(Regex("#."), "")

        /**
         * Turn a decode into the token string the radio's ft8_message_tokenize()
         * expects: "time conf snr pitch ~ m1 m2 [m3 [m4]]". Mirrors the web UI's
         * FT8_message_chosen() (non-word characters other than - and ~ become
         * spaces). Returns null if the line isn't a decode.
         */
        fun normalizeFt8(decodeText: String): String? {
            val plain = stripMarkup(decodeText).replace(Regex("""[^\w\-~]+"""), " ").trim()
            val tokens = plain.split(Regex("""\s+"""))
            if (tokens.size < 7 || tokens.size > 9 || tokens[4] != "~") return null
            return tokens.joinToString(" ")
        }
    }
}
