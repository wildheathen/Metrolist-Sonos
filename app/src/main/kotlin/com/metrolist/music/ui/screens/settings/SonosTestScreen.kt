package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.upnp.DiscoveryState
import com.metrolist.music.upnp.SonosDevice
import com.metrolist.music.viewmodels.SonosTestViewModel

private const val DEFAULT_TEST_URL =
    "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonosTestScreen(
    navController: NavController,
    viewModel: SonosTestViewModel = hiltViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    var testUrl by rememberSaveable { mutableStateOf(DEFAULT_TEST_URL) }
    var volumeDraft by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- Discovery section --------------------------------------------------
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
                        is DiscoveryState.Found -> "Trovati ${s.count} device."
                        is DiscoveryState.Error -> "Errore: ${s.message}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Devices list -------------------------------------------------------
        if (devices.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Dispositivi",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    val conn = connectionState
                    devices.forEach { device ->
                        DeviceRow(
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

        // ---- Connection + playback controls (only when connected) ---------------
        val connectedDevice = (connectionState as? ConnectionState.Connected)?.device
        if (connectedDevice != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Riproduzione",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Connesso a ${connectedDevice.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = testUrl,
                        onValueChange = { testUrl = it },
                        label = { Text("URL audio") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.loadMedia(testUrl) }) {
                            Text("Carica & Play")
                        }
                        OutlinedButton(onClick = { viewModel.play() }) { Text("Play") }
                        OutlinedButton(onClick = { viewModel.pause() }) { Text("Pause") }
                        OutlinedButton(onClick = { viewModel.stop() }) { Text("Stop") }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ---- Volume slider ------------------------------------------
                    Text("Volume: ${volumeDraft ?: playbackState.volume}")
                    Slider(
                        value = (volumeDraft ?: playbackState.volume).toFloat(),
                        valueRange = 0f..100f,
                        steps = 0,
                        onValueChange = { volumeDraft = it.toInt() },
                        onValueChangeFinished = {
                            val v = volumeDraft
                            if (v != null) {
                                viewModel.setVolume(v)
                                volumeDraft = null
                            }
                        },
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        buildString {
                            append("Stato: ")
                            append(if (playbackState.isPlaying) "PLAYING" else "STOPPED")
                            if (playbackState.isTransitioning) append(" (transitioning)")
                            append("  |  Pos: ")
                            append(formatSec(playbackState.position.inWholeSeconds))
                            append(" / ")
                            append(formatSec(playbackState.duration.inWholeSeconds))
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    playbackState.lastError?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Errore: $it",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    TopAppBar(
        title = { Text("Sonos UPnP Cast (beta)") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun DeviceRow(
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

private fun formatSec(s: Long): String {
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%d:%02d".format(m, sec)
}
