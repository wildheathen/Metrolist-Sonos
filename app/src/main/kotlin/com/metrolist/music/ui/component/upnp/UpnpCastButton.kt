/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component.upnp

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metrolist.music.R
import com.metrolist.music.constants.SonosCastEnabledKey
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.SonosTestViewModel

/**
 * Cast entry-point button.
 *
 * Renders nothing when the [SonosCastEnabledKey] preference is disabled — this
 * is an opt-in feature and should not appear in the player UI by default.
 *
 * When enabled, the icon reflects the underlying `UpnpCastController`
 * connection state (disconnected / connecting / connected) and tapping it
 * opens the [UpnpCastPickerSheet] modal.
 */
@Composable
fun UpnpCastButton(
    modifier: Modifier = Modifier,
) {
    val (enabled) = rememberPreference(
        key = SonosCastEnabledKey,
        defaultValue = false,
    )
    if (!enabled) return

    val viewModel: SonosTestViewModel = hiltViewModel()
    val connectionState by viewModel.connectionState.collectAsState()
    var showSheet by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showSheet = true },
        modifier = modifier,
    ) {
        when (connectionState) {
            is ConnectionState.Connecting -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
            is ConnectionState.Connected -> {
                Icon(
                    painter = painterResource(R.drawable.cast_connected),
                    contentDescription = "Cast (connesso)",
                )
            }
            // Disconnected / Error — show the plain cast icon.
            else -> {
                Icon(
                    painter = painterResource(R.drawable.cast),
                    contentDescription = "Cast",
                )
            }
        }
    }

    if (showSheet) {
        UpnpCastPickerSheet(
            onDismiss = { showSheet = false },
            viewModel = viewModel,
        )
    }
}
