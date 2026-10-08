package com.sbitx.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbitx.remote.net.SbitxClient
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** zBitx spectrum bins are 46.875 Hz wide (96 kHz / 2048-point FFT). */
private const val HZ_PER_BIN = 46.875

/**
 * Scrolling waterfall from zBitx "RX <bins>" frames.
 *
 * zBitx sends bins highest-frequency first (the web UI draws bin i at
 * x = width - i), so we mirror them: left = lower frequency, centre = dial.
 * Magnitude = (char - 32) * 2, clamped to 0..100, as in the web UI.
 *
 * [onTap] gets the tapped offset from the dial in Hz; [onDrag] gets a tuning
 * delta in Hz (drag right = signals move right = tune down, like the web UI).
 */
@Composable
fun Waterfall(
    client: SbitxClient,
    mode: String,
    low: Int,
    high: Int,
    txPitch: Int?,
    modifier: Modifier = Modifier,
    onTap: (Int) -> Unit,
    onDrag: (Long) -> Unit,
) {
    val rowsN = 90
    var bins by remember { mutableIntStateOf(0) }
    var bmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val pixels = remember { java.util.concurrent.atomic.AtomicReference(IntArray(0)) }
    var tick by remember { mutableIntStateOf(0) }
    var spectrumLine by remember { mutableStateOf(IntArray(0)) }

    LaunchedEffect(client) {
        client.spectrum.collect { line ->
            if (!line.startsWith("RX ")) return@collect   // TX frames are the modulation envelope
            val n = line.length - 3
            if (n < 16) return@collect
            var px = pixels.get()
            if (n != bins || bmp == null) {
                bins = n
                px = IntArray(n * rowsN) { 0xFF000000.toInt() }
                pixels.set(px)
                bmp = android.graphics.Bitmap.createBitmap(n, rowsN, android.graphics.Bitmap.Config.ARGB_8888)
            }
            // scroll down one row, newest at the top (same as the zBitx web UI)
            System.arraycopy(px, 0, px, n, n * (rowsN - 1))
            val spec = IntArray(n)
            for (x in 0 until n) {
                val v = ((line[3 + (n - 1 - x)].code - 32) * 2).coerceIn(0, 100)
                spec[x] = v
                px[x] = heatColor(v)
            }
            spectrumLine = spec
            bmp?.setPixels(px, 0, n, 0, 0, n, rowsN)
            tick++
        }
    }

    val spanHz = bins * HZ_PER_BIN
    var dragAccum by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .background(Color(0xFF0A0E14))
            .pointerInput(bins) {
                detectTapGestures { p ->
                    if (bins > 0) onTap(((p.x / size.width - 0.5f) * spanHz).toInt())
                }
            }
            .pointerInput(bins) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccum = 0f },
                    onDragCancel = { dragAccum = 0f }
                ) { change, dx ->
                    if (bins == 0) return@detectHorizontalDragGestures
                    dragAccum += (-dx / size.width * spanHz).toFloat()
                    val whole = (dragAccum / 10).toLong() * 10   // 10 Hz granularity
                    if (whole != 0L) { onDrag(whole); dragAccum -= whole }
                    change.consume()
                }
            }
    ) {
        val b = bmp
        if (b != null && tick > 0) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = "waterfall",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize()
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            if (bins == 0) return@Canvas
            val w = size.width
            val h = size.height
            fun xOf(offsetHz: Double) = (w / 2f + (offsetHz / spanHz * w)).toFloat()

            // spectrum trace across the top third
            val spec = spectrumLine
            if (spec.isNotEmpty()) {
                val sh = h * 0.35f
                val step = w / spec.size
                for (i in 1 until spec.size) {
                    drawLine(
                        Color(0xAAFFFFFF),
                        Offset((i - 1) * step, sh - spec[i - 1] / 100f * sh),
                        Offset(i * step, sh - spec[i] / 100f * sh),
                        strokeWidth = 1f
                    )
                }
            }

            // receive passband from LOW/HIGH (relative to the dial)
            val (a, z) = when (mode) {
                "LSB", "CWR" -> -high.toDouble() to -low.toDouble()
                "AM" -> -high.toDouble() to high.toDouble()
                else -> low.toDouble() to high.toDouble()
            }
            val x0 = xOf(a); val x1 = xOf(z)
            drawRect(Color(0x2242A5F5), topLeft = Offset(minOf(x0, x1), 0f),
                size = Size(kotlin.math.abs(x1 - x0), h))
            drawLine(Color(0x8842A5F5), Offset(x0, 0f), Offset(x0, h), 1.5f)
            drawLine(Color(0x8842A5F5), Offset(x1, 0f), Offset(x1, h), 1.5f)
            // dial
            drawLine(Color(0xAAFF5252), Offset(w / 2f, 0f), Offset(w / 2f, h), 2f)
            // FT8 transmit tone
            if (mode == "FT8" && txPitch != null) {
                val xt = xOf(txPitch.toDouble())
                drawLine(Color(0xFFFF1744), Offset(xt, 0f), Offset(xt, h), 2f)
            }
        }
    }
}

/** 0..100 -> black -> blue -> cyan -> yellow -> white heat map. */
fun heatColor(v: Int): Int {
    val t = v / 100f
    val r: Int; val g: Int; val b: Int
    when {
        t < 0.25f -> { val k = t / 0.25f; r = 0; g = 0; b = (k * 180).toInt() }
        t < 0.5f -> { val k = (t - 0.25f) / 0.25f; r = 0; g = (k * 200).toInt(); b = 180 + (k * 75).toInt() }
        t < 0.75f -> { val k = (t - 0.5f) / 0.25f; r = (k * 255).toInt(); g = 200 + (k * 55).toInt(); b = (255 * (1 - k)).toInt() }
        else -> { val k = (t - 0.75f) / 0.25f; r = 255; g = 255; b = (k * 255).toInt() }
    }
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

@Composable
fun BarMeter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(10.dp)) {
        drawRoundRect(Color(0x33FFFFFF), size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
        drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f))
    }
}

/** Rotary tuning knob: drag around the centre; each 12 degrees = one step. */
@Composable
fun TuningKnob(modifier: Modifier = Modifier, onDelta: (Int) -> Unit) {
    var angle by remember { mutableFloatStateOf(0f) }
    var accum by remember { mutableFloatStateOf(0f) }
    val knobColor = MaterialTheme.colorScheme.surfaceVariant
    val rimColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.onSurface

    Canvas(
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
        drawCircle(color = rimColor, radius = r, center = c, style = Stroke(width = r * 0.08f))
        for (i in 0 until 12) {
            val a = Math.toRadians((angle + i * 30f).toDouble())
            val inner = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.72f)
            val outer = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.88f)
            drawLine(rimColor.copy(alpha = 0.5f), inner, outer, strokeWidth = r * 0.04f)
        }
        val a = Math.toRadians(angle.toDouble() - 90.0)
        val dot = c + Offset(cos(a).toFloat(), sin(a).toFloat()) * (r * 0.55f)
        drawCircle(color = dotColor, radius = r * 0.10f, center = dot)
    }
}

/** Tap-to-select frequency digits; the knob then tunes the selected digit's place. */
@Composable
fun FreqDigits(freq: Long, selectedMult: Long, onSelect: (Long) -> Unit) {
    val f = freq.coerceIn(0L, 99_999_999L)
    val digits = "%08d".format(f)
    val mults = listOf(10_000_000L, 1_000_000L, 100_000L, 10_000L, 1_000L, 100L, 10L, 1L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        digits.forEachIndexed { i, ch ->
            if (i == 2 || i == 5) {
                Text(".", fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            val sel = mults[i] == selectedMult
            Text(
                ch.toString(),
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (sel) TextDecoration.Underline else null,
                modifier = Modifier.clickable { onSelect(mults[i]) }.padding(horizontal = 1.dp)
            )
        }
    }
}

// ============================================================== FT8 console

private data class Ft8Row(val kind: String, val text: String, val key: String)

/**
 * FT8 panel, working the way the zBitx web UI does:
 *  - TAP a decode to answer it. The app sends "FT8 <decode>" and the radio's own
 *    ft8_process() fills the logger and sequences the whole QSO (and logs it
 *    when FT8_AUTO is on). Tapping one of your own TX lines re-sends it.
 *  - LONG-PRESS a decode to put that station in the radio's CALL field, so the
 *    macro keys ("!" = CALL) address it.
 *  - F1..F12 come from the selected FT8 macro file on the radio.
 */
@Composable
fun Ft8Console(client: SbitxClient) {
    val fields by client.fields.collectAsState()
    val myCall = (fields["MYCALLSIGN"] ?: "").uppercase()
    val myGrid = (fields["MYGRID"] ?: "").uppercase().take(4)
    val auto = fields["FT8_AUTO"] == "ON"
    val call = fields["CALL"].orEmpty()

    val rows = remember { mutableStateListOf<Ft8Row>() }
    val listState = rememberLazyListState()
    var msg by remember { mutableStateOf("") }
    var lastLog by remember { mutableStateOf("") }

    LaunchedEffect(client) {
        client.console.collect { cl ->
            if (cl.kind == "LOG") {
                SbitxClient.stripMarkup(cl.text).trim().takeIf { it.isNotEmpty() }?.let { lastLog = it }
                return@collect
            }
            if (!cl.kind.startsWith("WSJTX")) return@collect
            val text = cl.text.trimEnd('\n', ' ')
            if (text.length < 20) return@collect
            // de-duplicate the way the web UI does: everything from '~' on is the message
            val plain = SbitxClient.stripMarkup(text)
            val key = plain.substringAfter('~', plain).replace(Regex("\\s+"), " ").trim()
            val existing = rows.indexOfLast { it.key == key && it.kind == cl.kind }
            if (existing >= 0 && existing >= rows.size - 30) rows.removeAt(existing)
            rows.add(Ft8Row(cl.kind, text, key))
            while (rows.size > 200) rows.removeAt(0)
            listState.animateScrollToItem(rows.size - 1)
        }
    }

    Column(
        Modifier.fillMaxWidth().background(Color(0xFF10141A), RoundedCornerShape(8.dp)).padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (myCall.isBlank() || myCall == "CALL") "FT8  (set MYCALLSIGN on the radio)"
                else "FT8  $myCall $myGrid" + (if (call.isNotBlank()) "  ⇄  $call" else ""),
                fontSize = 12.sp, color = Color(0xFF81C784), modifier = Modifier.weight(1f)
            )
            if (call.isNotBlank()) {
                Text("clear", fontSize = 11.sp, color = Color(0xFF90CAF9),
                    modifier = Modifier.clickable { client.wipeLogger() }.padding(horizontal = 6.dp))
            }
            Text("Auto", fontSize = 11.sp)
            Switch(checked = auto, onCheckedChange = { client.setFt8Auto(it) },
                modifier = Modifier.height(24.dp).padding(start = 4.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(180.dp)) {
            items(rows.size) { i ->
                val row = rows[i]
                Text(
                    renderDecorated(row.text, row.kind),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(row) {
                            detectTapGestures(
                                onTap = {
                                    val toks = SbitxClient.normalizeFt8(row.text)?.split(' ')
                                    if (toks != null && toks.getOrNull(6) == myCall) {
                                        // one of ours: re-send it (web UI behaviour)
                                        client.ft8Transmit(toks.drop(5).joinToString(" "))
                                    } else {
                                        client.ft8Reply(row.text)
                                    }
                                },
                                onLongPress = {
                                    pickCall(row.text, myCall)?.let { client.setLogField("CALL", it) }
                                }
                            )
                        }
                        .padding(vertical = 1.dp)
                )
            }
        }
        if (lastLog.isNotEmpty()) {
            Text(lastLog, fontSize = 11.sp, color = Color(0xFFB3B3B3), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Text("Tap a decode to answer it (radio runs the QSO). Long-press to set CALL for the macros.",
            fontSize = 10.sp, color = Color(0x88FFFFFF))
    }

    Spacer(Modifier.height(6.dp))
    MacroPanel(client, MacroGroup.FT8)

    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            msg, { msg = it.uppercase() },
            label = { Text("Free FT8 message", fontSize = 11.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Button(onClick = { if (msg.isNotBlank()) { client.ft8Transmit(msg); msg = "" } },
            enabled = msg.isNotBlank()) { Text("Send") }
    }
}

/** The other station in a decode: the 'R'-styled span, else the sender token. */
private fun pickCall(text: String, myCall: String): String? {
    decoSpans(text).firstOrNull { it.first == 'R' }?.second?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val toks = SbitxClient.normalizeFt8(text)?.split(' ') ?: return null
    // "... ~ CQ [DX] CALL GRID" or "... ~ TO FROM X"
    return when {
        toks[5] == "CQ" && toks.size == 9 -> toks[7]
        toks[5] == "CQ" -> toks[6]
        toks[5] == myCall -> toks[6]
        else -> toks[6]
    }
}

@Composable
fun Ft8Btn(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = if (danger) ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF5350))
                 else ButtonDefaults.outlinedButtonColors(),
        modifier = Modifier.height(36.dp)
    ) { Text(label, fontSize = 12.sp) }
}

// =============================================================== CW console

/**
 * CW / CWR panel: decoded and sent text, the radio's pending text buffer,
 * type-to-send, the QSO logger the macros read from, and the macro keys.
 * Decoded/sent text arrives as CW-RX / CW-TX console tags (FLDIGI-* too).
 * Typed text and macros both land in the radio's text buffer ("key ..." /
 * "F<n>") and zBitx keys the transmitter itself.
 */
@Composable
fun CwConsole(client: SbitxClient) {
    val fields by client.fields.collectAsState()
    val segments = remember { mutableStateListOf<Pair<Boolean, String>>() } // (isTx, text)
    var outgoing by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(client) {
        client.console.collect { cl ->
            val tx = when (cl.kind) {
                "CW-RX", "FLDIGI-RX" -> false
                "CW-TX", "FLDIGI-TX" -> true
                else -> return@collect
            }
            val t = SbitxClient.stripMarkup(cl.text)
            if (segments.isNotEmpty() && segments.last().first == tx) {
                segments[segments.size - 1] = tx to (segments.last().second + t).takeLast(1500)
            } else segments.add(tx to t)
            while (segments.size > 40) segments.removeAt(0)
        }
    }
    LaunchedEffect(segments.size, segments.lastOrNull()?.second?.length) { scroll.animateScrollTo(scroll.maxValue) }

    Column(Modifier.fillMaxWidth().background(Color(0xFF10141A), RoundedCornerShape(8.dp)).padding(6.dp)) {
        Text("CW", fontSize = 12.sp, color = Color(0xFF81C784))
        Box(Modifier.fillMaxWidth().height(110.dp).verticalScroll(scroll)) {
            Text(
                buildAnnotatedString {
                    for ((tx, t) in segments) {
                        pushStyle(SpanStyle(color = if (tx) Color(0xFFFFB74D) else Color(0xFFE0E0E0)))
                        append(t); pop()
                    }
                },
                fontFamily = FontFamily.Monospace, fontSize = 13.sp
            )
        }
        // what the radio still has to key (macros and typed text queue here)
        val pending = fields["TEXT"].orEmpty().trim()
        if (pending.isNotEmpty() && pending != "text box") {
            Text("Sending: $pending", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFFFFB74D), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                outgoing, { outgoing = it.uppercase() },
                label = { Text("Type to send", fontSize = 11.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = { if (outgoing.isNotBlank()) { client.sendKeys(outgoing + " "); outgoing = "" } },
                enabled = outgoing.isNotBlank()
            ) { Text("Send") }
        }
    }
    Spacer(Modifier.height(6.dp))
    LoggerRow(client)
    Spacer(Modifier.height(6.dp))
    MacroPanel(client, MacroGroup.CW)
}

// ======================================================== firmware markup

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
 * Render firmware span styles (hist_disp.c / font_table in sbitx_gtk.c):
 * G=time/freq/FT8-RX, H=SNR/FT8-TX, Q=my call, R=caller, S=grid,
 * W=already-worked grid, P=country/reply, O=queued, U/V=distance/azimuth.
 */
fun renderDecorated(text: String, kind: String): AnnotatedString {
    fun colorOf(s: Char): Color = when (s) {
        'G' -> Color(0xFF00CCCC)
        'H' -> Color(0xFFFFFFFF)
        'Q' -> Color(0xFFFF3333)
        'R' -> Color(0xFFE07818)
        'S' -> Color(0xFFFFCC00)
        'W' -> Color(0xFF00B300)
        'P' -> Color(0xFF00E000)
        'O' -> Color(0xFFFFB74D)
        'U', 'V' -> Color(0xFFFFCC00)
        else -> Color(0xFFB3B3B3)
    }
    return buildAnnotatedString {
        val kindTint = when (kind) {
            "WSJTX-TX" -> Color(0xFFEF5350)
            "WSJTX-Q" -> Color(0xFFFFB74D)
            else -> null
        }
        for ((s, t) in decoSpans(text)) {
            pushStyle(SpanStyle(color = kindTint ?: colorOf(s), fontWeight = if (s == 'Q') FontWeight.Bold else null))
            append(t.replace('\n', ' '))
            pop()
        }
    }
}
