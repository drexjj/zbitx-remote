package com.sbitx.remote.ui

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbitx.remote.net.SbitxClient
import kotlinx.coroutines.delay

/**
 * Which macro file a mode group uses. The choice is remembered per group, so
 * CW/CWR and FT8 each come back to the file you last picked for them (zBitx's
 * own web UI defaults to CW1 and FT8 the same way).
 */
enum class MacroGroup(val prefKey: String, val defaultFile: String, val title: String) {
    CW("macroFileCw", "CW1", "CW macros"),
    FT8("macroFileFt8", "FT8", "FT8 macros"),
}

/**
 * Macro file picker + F1..F12 keys.
 *
 * The radio has one macro file loaded at a time and runs F-keys from it, so on
 * entering CW/CWR or FT8 this loads the file remembered for that mode group.
 * Button labels come from the radio's F1..F12 fields (exactly what its own
 * screen shows); the .mc file is fetched only to preview what a key will send.
 *
 * Tap = send. Long-press = show the macro text without sending.
 */
@Composable
fun MacroPanel(client: SbitxClient, group: MacroGroup) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sbitx", Context.MODE_PRIVATE) }
    val fields by client.fields.collectAsState()
    val files by client.macroFiles.collectAsState()
    val defs by client.macroDefs.collectAsState()
    val current = fields["MACRO"].orEmpty()
    var preview by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }

    // On entering this mode group, put the remembered file on the radio.
    LaunchedEffect(client, group, files.isNotEmpty()) {
        val wanted = prefs.getString(group.prefKey, group.defaultFile) ?: group.defaultFile
        val choice = when {
            files.isEmpty() -> { delay(2500); wanted }       // list never came: try anyway
            wanted in files -> wanted
            group.defaultFile in files -> group.defaultFile
            else -> current.ifBlank { files.first() }
        }
        if (choice != current) client.loadMacroFile(choice) else client.fetchMacroFile(choice)
    }
    // Keep previews in step if the file is changed from the radio's own screen.
    LaunchedEffect(current) { if (current.isNotBlank()) client.fetchMacroFile(current) }

    val keys = defs[current].orEmpty()

    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF151A22), RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(group.title, fontSize = 12.sp, color = Color(0xFF81C784))
            Spacer(Modifier.width(8.dp))
            Box {
                OutlinedButton(
                    onClick = { client.requestMacroFiles(); pickerOpen = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp)
                ) { Text("File: ${current.ifBlank { "…" }}  ▾", fontSize = 12.sp) }
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    if (files.isEmpty()) {
                        DropdownMenuItem(text = { Text("Loading list…") }, onClick = {}, enabled = false)
                    }
                    files.forEach { name ->
                        DropdownMenuItem(
                            text = {
                                Text(name, fontWeight = if (name == current) FontWeight.Bold else null)
                            },
                            onClick = {
                                pickerOpen = false
                                prefs.edit().putString(group.prefKey, name).apply()
                                preview = null
                                client.loadMacroFile(name)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Ft8Btn("Stop", true, danger = true) { client.abortTx() }
        }
        Spacer(Modifier.height(4.dp))

        for (row in 0 until 3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 4) {
                    val n = row * 4 + col + 1
                    val def = keys.firstOrNull { it.key == n }
                    // the radio's field is authoritative; fall back to the parsed file
                    val label = (fields["F$n"] ?: def?.label).orEmpty().trim()
                    val usable = label.isNotEmpty() && label != "-"
                    MacroKeyButton(
                        n = n, label = label, enabled = usable,
                        modifier = Modifier.weight(1f),
                        onTap = {
                            client.runMacro(n)
                            preview = "F$n sent" + (def?.text?.let { ": $it" } ?: "")
                        },
                        onLongPress = {
                            preview = "F$n $label: " + (def?.text ?: "(preview unavailable)")
                        }
                    )
                }
            }
            if (row < 2) Spacer(Modifier.height(4.dp))
        }
        preview?.let {
            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = Color(0xFFB3B3B3), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun MacroKeyButton(
    n: Int, label: String, enabled: Boolean, modifier: Modifier,
    onTap: () -> Unit, onLongPress: () -> Unit
) {
    val border = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Color(0x33FFFFFF)
    Box(
        modifier
            .height(44.dp)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0x1A90CAF9) else Color.Transparent, RoundedCornerShape(8.dp))
            .pointerInput(enabled, n) {
                if (enabled) detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("F$n", fontSize = 9.sp, color = Color(0x99FFFFFF))
            Text(
                if (enabled) label else "–", fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                color = if (enabled) Color.White else Color(0x55FFFFFF),
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }
    }
}

/**
 * The radio's QSO logger, which the macros read from:
 *   CALL -> "!", SENT -> {SENTRST}/{SENTRSTCUT}, NR -> {EXCH} / "#".
 * Every edit is pushed to the radio straight away so the next macro uses it.
 * Log saves the QSO on the radio (it needs Call, Sent and Rcvd).
 */
@Composable
fun LoggerRow(client: SbitxClient, defaultRst: String = "599") {
    val fields by client.fields.collectAsState()
    val call = fields["CALL"].orEmpty()
    val sent = fields["SENT"].orEmpty()

    Column(
        Modifier.fillMaxWidth()
            .background(Color(0xFF151A22), RoundedCornerShape(8.dp))
            .padding(6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LoggerField("Call", "CALL", call, client, Modifier.weight(1.5f)) { v ->
                // macros like "ur rst {SENTRST}" need SENT; fill the usual report
                if (v.isNotBlank() && sent.isBlank()) client.setLogField("SENT", defaultRst)
            }
            LoggerField("Sent", "SENT", sent, client, Modifier.weight(1f), placeholder = defaultRst)
            LoggerField("Rcvd", "RECV", fields["RECV"].orEmpty(), client, Modifier.weight(1f))
            LoggerField("My exch", "NR", fields["NR"].orEmpty(), client, Modifier.weight(1f))
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val canLog = call.length >= 3 && sent.isNotBlank() && fields["RECV"].orEmpty().isNotBlank()
            Button(onClick = { client.saveQso() }, enabled = canLog,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)) { Text("Log QSO", fontSize = 12.sp) }
            OutlinedButton(onClick = { client.wipeLogger() },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)) { Text("Wipe", fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                if (canLog) "" else "Log needs Call, Sent, Rcvd",
                fontSize = 10.sp, color = Color(0x88FFFFFF)
            )
        }
    }
}

@Composable
private fun LoggerField(
    label: String, radioLabel: String, radioValue: String, client: SbitxClient,
    modifier: Modifier, placeholder: String? = null, onEdited: (String) -> Unit = {}
) {
    var focused by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(radioValue) }
    // follow the radio (macros, FT8 auto, WIPE) unless the user is typing here
    LaunchedEffect(radioValue) { if (!focused) text = radioValue }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val clean = v.uppercase().filter { it > ' ' && it != '=' }
                .take(if (radioLabel == "CALL") 11 else 7)
            text = clean
            client.setLogField(radioLabel, clean)
            onEdited(clean)
        },
        label = { Text(label, fontSize = 10.sp) },
        placeholder = if (placeholder != null) { { Text(placeholder, fontSize = 12.sp) } } else null,
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        modifier = modifier.onFocusChanged { focused = it.isFocused }
    )
}
