/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component.upnp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.upnp.DiscoveryState
import com.metrolist.music.upnp.KnownSonosDevice
import com.metrolist.music.upnp.SonosDevice
import com.metrolist.music.viewmodels.SonosTestViewModel

/**
 * Modal bottom sheet that lets the user pick a Sonos UPnP device to cast to.
 *
 * Sections:
 *  - If already Connected: header with device name + "Disconnetti" button.
 *  - Recents (known devices): tap-to-reconnect / forget.
 *  - Live discovery results: tap-to-connect.
 *  - Discovery button + manual IP fallback.
 *
 * Shares the same [SonosTestViewModel] used by the dev test screen so the app
 * only ever has one source of truth for cast state (the underlying
 * `UpnpCastController` is a Hilt @Singleton).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpnpCastPickerSheet(
    onDismiss: () -> Unit,
    viewModel: SonosTestViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val devices by viewModel.devices.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val knownDevices by viewModel.knownDevices.collectAsState()

    var manualIp by rememberSaveable { mutableStateOf("") }

    // Ping the active device (if any) once the sheet opens so the UI reflects
    // reality even after app backgrounding.
    LaunchedEffect(Unit) {
        viewModel.verifyActiveConnection()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Cast su Sonos",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // ---- Connected header ---------------------------------------------
            val connectedDevice = (connectionState as? ConnectionState.Connected)?.device
            if (connectedDevice != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Connesso a",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                connectedDevice.displayName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.disconnect() }) {
                            Text("Disconnetti")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- Known devices (recents) --------------------------------------
            if (knownDevices.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Dispositivi recenti",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap per riconnettere senza SSDP",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        knownDevices.forEach { known ->
                            SheetKnownDeviceRow(
                                known = known,
                                onReconnect = { viewModel.reconnectKnown(known) },
                                onForget = { viewModel.forgetKnown(known.udn) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- Discovery results --------------------------------------------
            if (devices.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Dispositivi trovati",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        val conn = connectionState
                        devices.forEach { device ->
                            SheetDeviceRow(
                                device = device,
                                isConnected = (conn as? ConnectionState.Connected)?.device?.udn == device.udn,
                                isConnecting = (conn as? ConnectionState.Connecting)?.device?.udn == device.udn,
                                onConnect = { viewModel.connect(device) },
                                onDisconnect = { viewModel.disconnect() },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- Discovery + manual IP ----------------------------------------
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Discovery",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { viewModel.startDiscovery() },
                            enabled = discoveryState !is DiscoveryState.Searching,
                        ) { Text("Cerca Sonos") }
                        if (discoveryState is DiscoveryState.Searching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = when (val s = discoveryState) {
                            DiscoveryState.Idle -> "Nessuna ricerca in corso."
                            DiscoveryState.Searching -> "Ricerca SSDP in corso..."
                            is DiscoveryState.Found ->
                                if (s.count == 0) "Nessun device trovato. Prova di nuovo o inserisci IP manualmente."
                                else "Trovati ${s.count} device."
                            is DiscoveryState.Error -> "Errore: ${s.message}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        "IP manuale (bypassa SSDP)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = manualIp,
                            onValueChange = { manualIp = it.trim() },
                            placeholder = { Text("192.168.1.101") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { if (manualIp.isNotBlank()) viewModel.addDeviceByIp(manualIp) },
                            enabled = manualIp.isNotBlank() && discoveryState !is DiscoveryState.Searching,
                        ) { Text("Aggiungi") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetDeviceRow(
    device: SonosDevice,
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(device.displayName, style = MaterialTheme.typography.bodyMedium)
            val model = device.modelNumber.ifBlank { "?" }
            val fw = device.softwareVersion?.takeIf { it.isNotBlank() } ?: "?"
            Text(
                "${device.ip}  ·  $model  ·  fw $fw",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            isConnecting -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
            isConnected -> OutlinedButton(onClick = onDisconnect) { Text("Disconnetti") }
            else -> Button(onClick = onConnect, enabled = device.isUsable) { Text("Connetti") }
        }
    }
}

@Composable
private fun SheetKnownDeviceRow(
    known: KnownSonosDevice,
    onReconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(known.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                known.ip,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = onReconnect) { Text("Connetti") }
        Spacer(Modifier.width(4.dp))
        OutlinedButton(onClick = onForget) { Text("Rimuovi") }
    }
}
