/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.upnp.DiscoveryState
import com.metrolist.music.upnp.KnownSonosDevice
import com.metrolist.music.upnp.PlaybackState
import com.metrolist.music.upnp.SonosDevice
import com.metrolist.music.upnp.UpnpCastController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration

/**
 * ViewModel for the developer-only "Sonos UPnP Cast (beta)" settings screen.
 * Exposes the [UpnpCastController] state flows and delegates actions to it.
 *
 * Lives as a ViewModel (not application-scoped) but the underlying
 * UpnpCastController is a @Singleton, so connection state survives
 * navigation away from the screen — you can navigate back and still be
 * connected to the same device.
 */
@HiltViewModel
class SonosTestViewModel @Inject constructor(
    val controller: UpnpCastController,
) : ViewModel() {

    val devices: StateFlow<List<SonosDevice>> = controller.devices
    val discoveryState: StateFlow<DiscoveryState> = controller.discoveryState
    val connectionState: StateFlow<ConnectionState> = controller.connectionState
    val playbackState: StateFlow<PlaybackState> = controller.playbackState
    val knownDevices: StateFlow<List<KnownSonosDevice>> = controller.knownDevices

    fun startDiscovery() = controller.startDiscovery()

    fun addDeviceByIp(ip: String) = controller.addDeviceByIp(ip)

    fun reconnectKnown(known: KnownSonosDevice) {
        viewModelScope.launch { controller.reconnectKnown(known) }
    }

    fun forgetKnown(udn: String) = controller.forgetKnown(udn)

    fun verifyActiveConnection() {
        viewModelScope.launch { controller.verifyActiveConnection() }
    }

    fun connect(device: SonosDevice) {
        viewModelScope.launch { controller.connect(device) }
    }

    fun disconnect() {
        viewModelScope.launch { controller.disconnect() }
    }

    fun loadMedia(url: String, title: String = "UPnP Test") {
        viewModelScope.launch {
            controller.loadMedia(
                url = url,
                title = title,
                artist = "Metrolist",
                mimeType = mimeTypeFor(url),
            )
        }
    }

    fun play() { viewModelScope.launch { controller.play() } }
    fun pause() { viewModelScope.launch { controller.pause() } }
    fun stop() { viewModelScope.launch { controller.stop() } }
    fun seek(position: Duration) { viewModelScope.launch { controller.seek(position) } }
    fun setVolume(volume: Int) { viewModelScope.launch { controller.setVolume(volume) } }

    private fun mimeTypeFor(url: String): String = when {
        url.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        url.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        url.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        url.endsWith(".wav", ignoreCase = true) -> "audio/wav"
        url.endsWith(".flac", ignoreCase = true) -> "audio/flac"
        url.endsWith(".ogg", ignoreCase = true) -> "audio/ogg"
        else -> "audio/mpeg" // reasonable default; Sonos is lenient
    }
}
