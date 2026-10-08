package com.sbitx.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sbitx.remote.net.SbitxClient
import kotlinx.coroutines.delay

/**
 * Full-screen view of the radio's logbook (the same SQLite log the zBitx web
 * UI's LOG panel shows). Newest first, 50 at a time, with a callsign-prefix
 * search. QSOs saved while it is open (Log QSO, FT8 auto) appear at the top.
 */
@Composable
fun LogbookDialog(client: SbitxClient, onClose: () -> Unit) {
    val qsos by client.logbook.collectAsState()
    var search by remember { mutableStateOf(client.logbookQuery) }
    var waited by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        client.loadLogbook(search)
        delay(3000); waited = true
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Logbook", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text(
                        if (client.logbookQuery.isEmpty()) "${qsos.size} shown"
                        else "${qsos.size} matching ${client.logbookQuery}",
                        fontSize = 12.sp, color = Color(0x99FFFFFF)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onClose,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp)) { Text("Close", fontSize = 12.sp) }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        search, { search = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(12) },
                        label = { Text("Callsign starts with") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Search
                        ),
                        keyboardActions = KeyboardActions(onSearch = { client.loadLogbook(search) }),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(6.dp))
                    Button(onClick = { client.loadLogbook(search) }) { Text("Search") }
                    if (client.logbookQuery.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = { search = ""; client.loadLogbook("") }) { Text("All") }
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (qsos.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                !waited -> "Loading…"
                                client.logbookQuery.isNotEmpty() -> "No QSOs with calls starting ${client.logbookQuery}"
                                else -> "No QSOs in the log yet"
                            },
                            color = Color(0x99FFFFFF)
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(qsos, key = { it.id }) { q -> QsoRow(q) }
                        item {
                            // id 1 is the first QSO ever logged; below that there is nothing older
                            if (qsos.size >= 50 && qsos.last().id > 1) {
                                TextButton(onClick = { client.loadOlderQsos() },
                                    modifier = Modifier.fillMaxWidth()) { Text("Load older QSOs") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QsoRow(q: SbitxClient.Qso) {
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color(0xFF151A22), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.call, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(q.mode, fontSize = 12.sp, color = Color(0xFF81C784))
            Spacer(Modifier.width(10.dp))
            Text("${q.freq} kHz", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Row {
            Text("${q.date}  ${formatHhmm(q.time)}Z", fontSize = 12.sp, color = Color(0xAAFFFFFF),
                modifier = Modifier.weight(1f))
            Text(
                "sent ${listOf(q.rstSent, q.exchSent).filter { it.isNotBlank() }.joinToString(" ")}" +
                    "   rcvd ${listOf(q.rstRecv, q.exchRecv).filter { it.isNotBlank() }.joinToString(" ")}",
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xAAFFFFFF)
            )
        }
        if (q.comment.isNotBlank()) {
            Text(q.comment, fontSize = 11.sp, color = Color(0x88FFFFFF), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatHhmm(t: String): String =
    if (t.length == 4 && t.all { it.isDigit() }) "${t.substring(0, 2)}:${t.substring(2)}" else t
