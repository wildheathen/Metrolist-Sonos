package com.metrolist.music.upnp

import android.content.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * High-level controller that mirrors the API surface of Metrolist's existing
 * [com.metrolist.music.playback.CastConnectionHandler] but targets UPnP
 * (Sonos) speakers instead of Google Cast.
 *
 * Responsibilities:
 *  - Discover devices on the LAN ([startDiscovery] / [devices]).
 *  - Own the "currently connected" device ([connectionState]).
 *  - Route playback control commands to the remote when connected, and expose
 *    a reactive [playbackState] the UI can observe for play/pause state,
 *    position, duration and volume.
 *
 * Lifecycle:
 *  - Instantiated once per app (Hilt @Singleton).
 *  - [shutdown] must be called when the host service is destroyed to cancel
 *    background polling coroutines.
 *
 * NOTE: this class does NOT touch the local ExoPlayer. The caller (MusicService
 * / PlayerConnection in Phase 3) is responsible for stopping the local player
 * when a UPnP connection is established, and for resuming it when disconnecting.
 */
class UpnpCastController(
    private val context: Context,
    private val httpClient: HttpClient,
    private val defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) {

    // Controller owns its own supervisor scope so background polling does not
    // tear down cross-app coroutines on failure.
    private val scope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    private val discovery = UpnpDiscovery(context, httpClient)
    private val soap = SoapClient(httpClient)

    // Guards concurrent issue of SOAP commands — avoids out-of-order play/pause
    // race when the user hammers buttons.
    private val commandMutex = Mutex()

    // ---------------------------------------------------------------------
    // Discovery state
    // ---------------------------------------------------------------------
    private val _devices = MutableStateFlow<List<SonosDevice>>(emptyList())
    val devices: StateFlow<List<SonosDevice>> = _devices.asStateFlow()

    private val _discoveryState = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    private var discoveryJob: Job? = null

    /** Start a discovery cycle. Idempotent — cancels any in-flight discovery. */
    fun startDiscovery(timeoutMs: Long = 5_000) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _discoveryState.value = DiscoveryState.Searching
            try {
                val found = discovery.discover(timeoutMs)
                _devices.value = found
                _discoveryState.value = if (found.isEmpty()) DiscoveryState.Idle
                else DiscoveryState.Found(found.size)
                Timber.i("UPnP discovery found %d device(s): %s",
                    found.size, found.joinToString { it.displayName })
            } catch (e: Exception) {
                Timber.w(e, "UPnP discovery failed")
                _discoveryState.value = DiscoveryState.Error(e.message ?: "discovery failed")
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        if (_discoveryState.value is DiscoveryState.Searching) {
            _discoveryState.value = DiscoveryState.Idle
        }
    }

    // ---------------------------------------------------------------------
    // Connection state
    // ---------------------------------------------------------------------
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var activeDevice: SonosDevice? = null
    private var avTransport: AVTransport? = null
    private var renderingControl: RenderingControl? = null
    private var pollingJob: Job? = null

    /**
     * Connect to a discovered device and start polling its playback state.
     * No media is loaded automatically — call [loadMedia] separately.
     */
    suspend fun connect(device: SonosDevice) = commandMutex.withLock {
        if (!device.isUsable) {
            _connectionState.value = ConnectionState.Error("Device ${device.displayName} cannot play media")
            return
        }
        disconnectInternalLocked()

        _connectionState.value = ConnectionState.Connecting(device)
        try {
            val av = AVTransport(device, soap)
            val rc = RenderingControl(device, soap)
            // Probe the device with a read-only call; bail out early if unreachable.
            av.getTransportInfo()
            val initialVolume = rc.getVolume()

            activeDevice = device
            avTransport = av
            renderingControl = rc
            _playbackState.value = _playbackState.value.copy(
                volume = initialVolume,
                isPlaying = false,
                position = Duration.ZERO,
                duration = Duration.ZERO,
            )
            _connectionState.value = ConnectionState.Connected(device)
            startPollingLocked()
            Timber.i("UPnP connected to %s (volume=%d)", device.displayName, initialVolume)
        } catch (e: Exception) {
            Timber.w(e, "UPnP connect failed for %s", device.displayName)
            activeDevice = null
            avTransport = null
            renderingControl = null
            _connectionState.value = ConnectionState.Error(e.message ?: "connection failed")
        }
    }

    /** Disconnect; best-effort Stop is sent to the device. */
    suspend fun disconnect() = commandMutex.withLock {
        disconnectInternalLocked()
    }

    private suspend fun disconnectInternalLocked() {
        pollingJob?.cancel()
        pollingJob = null
        try {
            avTransport?.stop()
        } catch (_: Exception) {
            // device may be gone — ignore
        }
        activeDevice = null
        avTransport = null
        renderingControl = null
        _playbackState.value = PlaybackState()
        _connectionState.value = ConnectionState.Disconnected
    }

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * Load a new media URL on the remote and start playing it.
     * Use [DidlBuilder.audioItem] to build the metadata XML.
     */
    suspend fun loadMedia(
        url: String,
        title: String,
        artist: String = "",
        album: String = "",
        albumArtUrl: String = "",
        mimeType: String = "audio/mpeg",
        durationMs: Long? = null,
    ) = commandMutex.withLock {
        val av = avTransport ?: run {
            Timber.w("loadMedia called without active UPnP connection")
            return
        }
        val metadata = DidlBuilder.audioItem(
            url = url,
            title = title,
            creator = artist,
            album = album,
            albumArtUri = albumArtUrl,
            mimeType = mimeType,
            durationMs = durationMs,
        )
        try {
            av.setAvTransportUri(url, metadata)
            av.play()
            _playbackState.value = _playbackState.value.copy(
                currentUrl = url,
                currentTitle = title,
                currentArtist = artist,
                isPlaying = true,
            )
        } catch (e: Exception) {
            Timber.w(e, "loadMedia failed")
            _playbackState.value = _playbackState.value.copy(lastError = e.message)
        }
    }

    suspend fun play() = commandMutex.withLock {
        runSafely("play") { avTransport?.play() }
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    suspend fun pause() = commandMutex.withLock {
        runSafely("pause") { avTransport?.pause() }
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    suspend fun stop() = commandMutex.withLock {
        runSafely("stop") { avTransport?.stop() }
        _playbackState.value = _playbackState.value.copy(isPlaying = false, position = Duration.ZERO)
    }

    suspend fun seek(position: Duration) = commandMutex.withLock {
        runSafely("seek") { avTransport?.seek(position) }
        _playbackState.value = _playbackState.value.copy(position = position)
    }

    /** Set the remote volume (0..100). */
    suspend fun setVolume(volume: Int) = commandMutex.withLock {
        runSafely("setVolume") { renderingControl?.setVolume(volume) }
        _playbackState.value = _playbackState.value.copy(volume = volume.coerceIn(0, 100))
    }

    suspend fun setMute(mute: Boolean) = commandMutex.withLock {
        runSafely("setMute") { renderingControl?.setMute(mute) }
        _playbackState.value = _playbackState.value.copy(isMuted = mute)
    }

    // ---------------------------------------------------------------------
    // Polling — keeps playbackState.position current while connected.
    // ---------------------------------------------------------------------
    private fun startPollingLocked() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val av = avTransport ?: break
                try {
                    val info = av.getTransportInfo()
                    val pos = av.getPositionInfo()
                    _playbackState.value = _playbackState.value.copy(
                        isPlaying = info.isPlaying,
                        isTransitioning = info.isTransitioning,
                        position = pos.relativeTime,
                        duration = pos.trackDuration,
                        currentUrl = pos.trackUri.ifBlank { _playbackState.value.currentUrl },
                    )
                } catch (e: Exception) {
                    // Transient errors are fine — we'll retry at next tick.
                    Timber.v(e, "UPnP polling tick failed")
                }
                delay(1.seconds)
            }
        }
    }

    private inline fun runSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.w(e, "UPnP %s failed", name)
        }
    }

    /** Release all background work. Call from host service's onDestroy. */
    fun shutdown() {
        scope.cancel()
    }
}

// =========================================================================
// Public state types
// =========================================================================
sealed class DiscoveryState {
    object Idle : DiscoveryState()
    object Searching : DiscoveryState()
    data class Found(val count: Int) : DiscoveryState()
    data class Error(val message: String) : DiscoveryState()
}

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    data class Connecting(val device: SonosDevice) : ConnectionState()
    data class Connected(val device: SonosDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/** Simple reactive snapshot of the remote player for the UI to observe. */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val isTransitioning: Boolean = false,
    val isMuted: Boolean = false,
    val volume: Int = 0,
    val position: Duration = Duration.ZERO,
    val duration: Duration = Duration.ZERO,
    val currentUrl: String = "",
    val currentTitle: String = "",
    val currentArtist: String = "",
    val lastError: String? = null,
)
