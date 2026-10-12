package com.sbitx.remote.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sbitx.remote.net.SbitxClient

private val CALL_RE = Regex("^[A-Z0-9/]{3,10}$")
private val GRID_RE = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")

/**
 * "Set" dialog for your station callsign and grid square. Saves them on the
 * radio (MYCALLSIGN / MYGRID - the same fields the zBitx web UI's settings
 * panel and the front panel's setup screen use), so FT8, the macros and the
 * logbook all pick them up.
 */
@Composable
fun StationDialog(client: SbitxClient, onClose: () -> Unit) {
    val fields by client.fields.collectAsState()
    val radioCall = fields["MYCALLSIGN"].orEmpty().let { if (it == "CALL" || it == "N0CALL" || it == "NOBODY") "" else it }
    val radioGrid = fields["MYGRID"].orEmpty().let { if (it == "NOWHERE") "" else it }

    var call by remember { mutableStateOf(radioCall) }
    var grid by remember { mutableStateOf(radioGrid) }

    val callOk = CALL_RE.matches(call)
    val gridOk = GRID_RE.matches(grid)

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Station") },
        text = {
            Column {
                OutlinedTextField(
                    call,
                    { call = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(10) },
                    label = { Text("My callsign") },
                    singleLine = true,
                    isError = call.isNotEmpty() && !callOk,
                    supportingText = { if (call.isNotEmpty() && !callOk) Text("3–10 letters, digits or /") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    grid,
                    { grid = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    label = { Text("My grid square") },
                    placeholder = { Text("EM10 or EM10dg") },
                    singleLine = true,
                    isError = grid.isNotEmpty() && !gridOk,
                    supportingText = { if (grid.isNotEmpty() && !gridOk) Text("4 or 6 characters, e.g. EM10 or EM10DG") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Saved on the radio - used by FT8, macros and the log.",
                    fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { client.setMyStation(call, grid); onClose() },
                enabled = callOk && gridOk && (call != radioCall || grid != radioGrid)
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } }
    )
}
