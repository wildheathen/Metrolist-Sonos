/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component.upnp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.upnp.castTrack
import com.metrolist.music.viewmodels.SonosTestViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Cast current track" button — reads the track currently playing locally
 * from [LocalPlayerConnection], fetches its stream URL via
 * [com.metrolist.music.playback.MusicService.getStreamUrl] and sends it to
 * the connected Sonos through [UpnpCastController].
 *
 * Renders nothing unless a Sonos is connected, so the button automatically
 * appears only when it's actionable. Disabled if no media is currently
 * playing locally.
 *
 * Intended to live inside the picker sheet (when connected) and/or the
 * player screen.
 */
@Composable
fun UpnpCastCurrentTrackButton(
    modifier: Modifier = Modifier,
    viewModel: SonosTestViewModel = hiltViewModel(),
) {
    val connection = LocalPlayerConnection.current ?: return
    val connectionState by viewModel.connectionState.collectAsState()
    val currentMetadata by connection.service.currentMediaMetadata.collectAsState()

    val connectedDevice = (connectionState as? ConnectionState.Connected)?.device ?: return

    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Button(
        modifier = modifier,
        enabled = currentMetadata != null && !loading,
        onClick = {
            val metadata = currentMetadata ?: return@Button
            loading = true
            scope.launch {
                try {
                    val streamUrl = withContext(Dispatchers.IO) {
                        connection.service.getStreamUrl(metadata.id)
                    }
                    if (streamUrl == null) {
                        Timber.w("getStreamUrl returned null for %s", metadata.id)
                        return@launch
                    }
                    viewModel.controller.castTrack(metadata, streamUrl)
                } catch (e: Exception) {
                    Timber.w(e, "Cast current track failed")
                } finally {
                    loading = false
                }
            }
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.cast),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(0.dp))
            val title = currentMetadata?.title
            Text(
                text = if (title != null) "Cast \"$title\" su ${connectedDevice.roomName.ifBlank { connectedDevice.modelName }}"
                else "Cast traccia corrente",
            )
        }
    }
}
