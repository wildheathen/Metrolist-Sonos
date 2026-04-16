/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.KnownSonosDevicesKey
import com.metrolist.music.utils.dataStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private val proxy: SonosHttpProxy,
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Known devices: persisted across app launches, MRU-ordered, max [MAX_KNOWN].
    private val _knownDevices = MutableStateFlow<List<KnownSonosDevice>>(emptyList())
    val knownDevices: StateFlow<List<KnownSonosDevice>> = _knownDevices.asStateFlow()

    init {
        // Load known devices from DataStore once at creation.
        scope.launch {
            try {
                val raw = context.dataStore.data
                    .map { it[KnownSonosDevicesKey] ?: "" }
                    .first()
                if (raw.isNotBlank()) {
                    _knownDevices.value = json.decodeFromString(
                        ListSerializer(KnownSonosDevice.serializer()),
                        raw,
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load known Sonos devices")
            }
        }
    }

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

    /**
     * Add a device to the list by fetching its device description directly from an IP.
     * Bypasses SSDP entirely — useful when the phone's multicast is broken
     * (some OEMs silently drop SSDP traffic) or when the device is on a different
     * subnet. Port defaults to 1400 (Sonos).
     */
    fun addDeviceByIp(ip: String, port: Int = 1400) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            _discoveryState.value = DiscoveryState.Searching
            val clean = ip.trim()
            if (clean.isEmpty()) {
                _discoveryState.value = DiscoveryState.Error("IP vuoto")
                return@launch
            }
            try {
                val found = discovery.fetchByIp(clean, port)
                if (found == null) {
                    _discoveryState.value = DiscoveryState.Error("Nessuna risposta da $clean:$port")
                    return@launch
                }
                // Merge with existing devices (dedup by UDN).
                val merged = (_devices.value + found).distinctBy { it.udn }
                _devices.value = merged
                _discoveryState.value = DiscoveryState.Found(merged.size)
                Timber.i("UPnP manual add: %s", found.displayName)
            } catch (e: Exception) {
                Timber.w(e, "UPnP manual add failed for %s", clean)
                _discoveryState.value = DiscoveryState.Error(e.message ?: "errore")
            }
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
            rememberDevice(device)
            // Spin up the local HTTP proxy so we have a place to register
            // upstream URLs once playback starts. Idempotent.
            try {
                proxy.start()
            } catch (e: Exception) {
                Timber.w(e, "Failed to start SonosHttpProxy")
            }
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
        // Tear down the local proxy — there's no Sonos to serve to anymore.
        try {
            proxy.stop()
        } catch (e: Exception) {
            Timber.w(e, "Failed to stop SonosHttpProxy")
        }
    }

    // ---------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * Load a new media URL on the remote and start playing it.
     * Use [DidlBuilder.audioItem] to build the metadata XML.
     *
     * @return `true` if the remote accepted the URL and Play commands;
     *   `false` if there was no active connection, or a SOAP / HTTP error
     *   prevented the load. On failure the error message is also written
     *   to [PlaybackState.lastError] for UI surfacing.
     */
    suspend fun loadMedia(
        url: String,
        title: String,
        artist: String = "",
        album: String = "",
        albumArtUrl: String = "",
        mimeType: String = "audio/mpeg",
        durationMs: Long? = null,
    ): Boolean = commandMutex.withLock {
        val av = avTransport ?: run {
            Timber.w("loadMedia called without active UPnP connection")
            return@withLock false
        }
        // Route HTTP(S) URLs through the local SonosHttpProxy so the Sonos
        // sees a stream served with Content-Type: audio/mp4 (the upstream
        // googlevideo URL is rejected directly with SOAP 714 because it
        // returns video/mp4). Non-HTTP URIs (file://, x-rincon-*) are
        // already in formats Sonos accepts natively — pass through unchanged.
        val playUrl = if (
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            if (!proxy.isRunning) proxy.start()
            val token = proxy.register(url)
            proxy.proxyUrlFor(token) ?: url
        } else {
            url
        }

        // Eagerly publish the known duration so the UI has a number to draw
        // the seek bar against from the very first frame after Play. Without
        // this, the slider sits at 0% (position / 0 = NaN) until the first
        // polling tick brings the trackDuration back from Sonos — visible
        // as "the time-elapsed counter advances but the bar doesn't move".
        durationMs?.takeIf { it > 0 }?.let { dms ->
            Timber.tag(TAG).i("loadMedia: eager duration=%d ms (title=%s)", dms, title.take(40))
            _playbackState.value = _playbackState.value.copy(
                duration = dms.milliseconds,
            )
        }
        Timber.tag(TAG).i(
            "loadMedia: title=%s | url=%s | durationMs=%s",
            title.take(40), playUrl.take(80), durationMs?.toString() ?: "null",
        )

        val metadata = DidlBuilder.audioItem(
            url = playUrl,
            title = title,
            creator = artist,
            album = album,
            albumArtUri = albumArtUrl,
            mimeType = "audio/mp4",
            durationMs = durationMs,
            asRadio = false, // proxy serves real AAC/MP4 → musicTrack is fine
        )
        try {
            av.setAvTransportUri(playUrl, metadata)
            av.play()
        } catch (e: Exception) {
            Timber.w(e, "loadMedia SOAP failed")
            _playbackState.value = _playbackState.value.copy(
                lastError = "SOAP error: ${e.message ?: e::class.simpleName} | url=${playUrl.take(140)}",
            )
            return@withLock false
        }

        // Verify the Sonos actually entered PLAYING. A SOAP 200 OK on
        // SetAVTransportURI/Play does NOT guarantee the device is streaming —
        // if the URL is rejected (codec mismatch, googlevideo IP-binding,
        // 4xx on the underlying GET) the transport silently falls back to
        // STOPPED and the UI gets stuck at 0:00 (issue #2). Poll a few times
        // for the transport state to confirm PLAYING before declaring success.
        var info: TransportInfo? = null
        val maxAttempts = 6 // up to ~3s of waiting (6 × 500ms)
        var attempt = 0
        while (attempt < maxAttempts) {
            delay(500)
            info = try {
                av.getTransportInfo()
            } catch (e: Exception) {
                Timber.v(e, "verify-after-play: getTransportInfo tick failed")
                null
            }
            when {
                info?.isPlaying == true -> break
                info?.isStopped == true -> break // terminal — no point polling further
                else -> { /* TRANSITIONING or null → keep waiting */ }
            }
            attempt++
        }

        if (info?.isPlaying != true) {
            val detail = if (info != null) {
                "transport=${info.state}/${info.status}, url=${playUrl.take(140)}"
            } else {
                "no transport response after Play, url=${playUrl.take(140)}"
            }
            val errMsg = "Sonos non è entrato in PLAYING dopo Play (attempts=$attempt, $detail)"
            Timber.w(errMsg)
            _playbackState.value = _playbackState.value.copy(
                currentUrl = "",
                isPlaying = false,
                lastError = errMsg,
            )
            return@withLock false
        }
        Timber.tag(TAG).i(
            "loadMedia: PLAYING confirmed after %d attempts (transport=%s/%s)",
            attempt, info.state, info.status,
        )

        _playbackState.value = _playbackState.value.copy(
            currentUrl = playUrl,
            currentTitle = title,
            currentArtist = artist,
            isPlaying = true,
            lastError = null,
        )
        true
    }

    /**
     * Prefetch the next track's URI on the Sonos so it can transition gaplessly
     * when the current one ends (AVTransport `SetNextAVTransportURI`). Passing
     * an empty [url] clears any previously-queued next URI.
     *
     * @return `true` if the SOAP call succeeded, `false` otherwise.
     */
    suspend fun setNextMedia(
        url: String,
        title: String,
        artist: String = "",
        album: String = "",
        albumArtUrl: String = "",
        mimeType: String = "audio/mpeg",
        durationMs: Long? = null,
    ): Boolean = commandMutex.withLock {
        val av = avTransport ?: return@withLock false
        // Same proxy routing as loadMedia: serve through SonosHttpProxy so
        // Sonos sees audio/mp4 instead of video/mp4 from googlevideo.
        val nextUrl = if (
            url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
        ) {
            if (!proxy.isRunning) proxy.start()
            val token = proxy.register(url)
            proxy.proxyUrlFor(token) ?: url
        } else {
            url
        }
        val metadata = if (nextUrl.isBlank()) "" else DidlBuilder.audioItem(
            url = nextUrl,
            title = title,
            creator = artist,
            album = album,
            albumArtUri = albumArtUrl,
            mimeType = "audio/mp4",
            durationMs = durationMs,
            asRadio = false,
        )
        try {
            av.setNextAvTransportUri(nextUrl, metadata)
            true
        } catch (e: Exception) {
            Timber.w(e, "setNextMedia failed")
            false
        }
    }

    suspend fun play() = commandMutex.withLock {
        Timber.tag(TAG).i("play()")
        runSafely("play") { avTransport?.play() }
        _playbackState.value = _playbackState.value.copy(isPlaying = true)
    }

    suspend fun pause() = commandMutex.withLock {
        Timber.tag(TAG).i("pause()")
        runSafely("pause") { avTransport?.pause() }
        _playbackState.value = _playbackState.value.copy(isPlaying = false)
    }

    suspend fun stop() = commandMutex.withLock {
        Timber.tag(TAG).i("stop()")
        runSafely("stop") { avTransport?.stop() }
        _playbackState.value = _playbackState.value.copy(isPlaying = false, position = Duration.ZERO)
    }

    suspend fun seek(position: Duration) = commandMutex.withLock {
        Timber.tag(TAG).i("seek(%d ms)", position.inWholeMilliseconds)
        runSafely("seek") { avTransport?.seek(position) }
        // Anchor the optimistic position so the polling loop can spot
        // bogus values (Sonos transiently reports 0:00 / the pre-seek
        // position while it's still completing the seek) and reject them.
        // We don't fully freeze the polling — that would also stop the
        // natural advance of the slider during playback for the lock
        // window. Instead the polling tick accepts a value only if it's
        // within ±SEEK_PLAUSIBILITY_MS of the anchor (plus elapsed wall
        // time, since playback keeps moving forward).
        seekAnchorMs = System.currentTimeMillis()
        seekAnchorPosition = position
        _playbackState.value = _playbackState.value.copy(position = position)
    }

    /** Set the remote volume (0..100). */
    suspend fun setVolume(volume: Int) = commandMutex.withLock {
        Timber.tag(TAG).d("setVolume(%d)", volume)
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
    // Anchor for the most recent seek: when did we issue it (wall-clock ms)
    // and what position did we ask for. The polling loop uses these to
    // compute an "expected position" (anchor + elapsed) and reject Sonos
    // reports that fall outside a plausibility window — i.e. the device's
    // transient post-seek 0:00 hiccup. After SEEK_PLAUSIBILITY_WINDOW_MS the
    // anchor is considered stale and we trust polling unconditionally again.
    @Volatile private var seekAnchorMs: Long = 0L
    @Volatile private var seekAnchorPosition: Duration = Duration.ZERO

    /**
     * True if a seek was issued in the last [SEEK_PLAUSIBILITY_WINDOW_MS].
     * UpnpPlayer uses this to suppress the gapless preload while the Sonos
     * transport is in a transitional state.
     */
    fun isSeekActive(): Boolean =
        (System.currentTimeMillis() - seekAnchorMs) < SEEK_PLAUSIBILITY_WINDOW_MS

    private fun startPollingLocked() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val av = avTransport ?: break
                try {
                    val info = av.getTransportInfo()
                    val pos = av.getPositionInfo()
                    val current = _playbackState.value
                    val acceptedPosition = reconcilePosition(
                        reported = pos.relativeTime,
                        cached = current.position,
                        isPlaying = info.isPlaying,
                    )
                    Timber.tag(TAG).v(
                        "poll: state=%s/%s pos=%ds dur=%ds (reported=%ds, accepted=%ds, anchor=%dms ago)",
                        info.state, info.status,
                        acceptedPosition.inWholeSeconds, pos.trackDuration.inWholeSeconds,
                        pos.relativeTime.inWholeSeconds, acceptedPosition.inWholeSeconds,
                        if (seekAnchorMs > 0) System.currentTimeMillis() - seekAnchorMs else -1L,
                    )
                    _playbackState.value = current.copy(
                        isPlaying = info.isPlaying,
                        isTransitioning = info.isTransitioning,
                        position = acceptedPosition,
                        // Keep the cached duration once we've learned it from
                        // either the eager publish (loadMedia) or a previous
                        // polling tick — Sonos sometimes reports trackDuration
                        // = 0 transiently during track transitions, which
                        // would otherwise wipe the slider scale and make the
                        // bar jump to 0%. Only accept the new value when the
                        // device gives us something positive.
                        duration = if (pos.trackDuration > Duration.ZERO) pos.trackDuration else current.duration,
                        currentUrl = pos.trackUri.ifBlank { current.currentUrl },
                    )
                    // Throttle polling when paused — there's no position
                    // advance to track and the user isn't watching the
                    // slider tick. Saves LAN traffic on long pauses.
                    val nextDelayMs = if (info.isPlaying || info.isTransitioning) 1_000L
                        else POLLING_PAUSED_INTERVAL_MS
                    delay(nextDelayMs)
                    continue
                } catch (e: Exception) {
                    // Transient errors are fine — we'll retry at next tick.
                    Timber.v(e, "UPnP polling tick failed")
                }
                delay(1.seconds)
            }
        }
    }

    /**
     * Decide whether the polled [reported] position should be accepted as the
     * new cached position, or whether it's a transient bogus value (Sonos
     * sometimes reports the pre-seek position or 0:00 for a tick or two
     * after a Seek SOAP returns 200).
     *
     * Strategy: if no recent seek was issued, accept anything. Otherwise
     * compute the expected position from the seek anchor + elapsed wall time
     * (only when playing, position doesn't advance while paused) and accept
     * the reported value if it's within ±SEEK_PLAUSIBILITY_DELTA_MS.
     */
    private fun reconcilePosition(
        reported: Duration,
        cached: Duration,
        isPlaying: Boolean,
    ): Duration {
        val anchorAge = System.currentTimeMillis() - seekAnchorMs
        if (anchorAge < 0 || anchorAge > SEEK_PLAUSIBILITY_WINDOW_MS) {
            // No active anchor → trust the device.
            return reported
        }
        val expectedMs = seekAnchorPosition.inWholeMilliseconds +
            if (isPlaying) anchorAge else 0L
        val deltaMs = kotlin.math.abs(reported.inWholeMilliseconds - expectedMs)
        return if (deltaMs <= SEEK_PLAUSIBILITY_DELTA_MS) {
            reported
        } else {
            // Reject: keep cached (which is the optimistic seek + any
            // accepted polling advances since then).
            cached
        }
    }

    private inline fun runSafely(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.w(e, "UPnP %s failed", name)
        }
    }

    // ---------------------------------------------------------------------
    // Known devices persistence
    // ---------------------------------------------------------------------
    private fun rememberDevice(device: SonosDevice) {
        val entry = device.toKnown(System.currentTimeMillis())
        val current = _knownDevices.value
        // MRU: move to front (or add), dedup by UDN, cap at MAX_KNOWN.
        val merged = (listOf(entry) + current.filter { it.udn != entry.udn })
            .take(MAX_KNOWN)
        _knownDevices.value = merged
        scope.launch { persistKnownDevices(merged) }
    }

    /** Remove a previously remembered device. */
    fun forgetKnown(udn: String) {
        val updated = _knownDevices.value.filterNot { it.udn == udn }
        _knownDevices.value = updated
        scope.launch { persistKnownDevices(updated) }
    }

    /**
     * Reconnect to a known device by re-fetching its device description
     * from the stored IP, then calling [connect]. If the IP has changed
     * (DHCP reshuffle, etc.) this returns false and the user should run
     * discovery.
     */
    suspend fun reconnectKnown(known: KnownSonosDevice): Boolean {
        val ip = known.ip
        val fresh = try {
            discovery.fetchByIp(ip)
        } catch (e: Exception) {
            Timber.w(e, "reconnectKnown: fetchByIp failed for %s", ip)
            null
        }
        if (fresh == null) {
            _discoveryState.value = DiscoveryState.Error(
                "Impossibile contattare ${known.displayName} a $ip — riprova discovery"
            )
            return false
        }
        // Merge into devices list so the UI can reflect "found".
        val merged = (_devices.value + fresh).distinctBy { it.udn }
        _devices.value = merged
        connect(fresh)
        return connectionState.value is ConnectionState.Connected
    }

    private suspend fun persistKnownDevices(list: List<KnownSonosDevice>) {
        try {
            val encoded = json.encodeToString(
                ListSerializer(KnownSonosDevice.serializer()),
                list,
            )
            context.dataStore.edit { it[KnownSonosDevicesKey] = encoded }
        } catch (e: Exception) {
            Timber.w(e, "Failed to persist known Sonos devices")
        }
    }

    // ---------------------------------------------------------------------
    // Connection verification (for app resume / heartbeat)
    // ---------------------------------------------------------------------
    /**
     * Verify the active connection by issuing a cheap [AVTransport.getTransportInfo]
     * call. If it fails, downgrade to Disconnected. Safe to call at any time
     * and on every resume — it's a no-op when not connected.
     */
    suspend fun verifyActiveConnection() {
        val av = avTransport ?: return
        val device = activeDevice ?: return
        try {
            av.getTransportInfo()
            // still alive; nothing to do
        } catch (e: Exception) {
            Timber.i("UPnP connection to %s lost (%s) — marking disconnected",
                device.displayName, e.message)
            commandMutex.withLock { disconnectInternalLocked() }
        }
    }

    /** Release all background work. Call from host service's onDestroy. */
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "UpnpCastController"
        const val MAX_KNOWN = 5
        // How long after a seek the plausibility filter stays armed (ms).
        // Sonos typically settles within 1-2 polling ticks; 4 s is a generous
        // headroom that still lets us trust the device again quickly.
        const val SEEK_PLAUSIBILITY_WINDOW_MS = 4_000L
        // How far the polled position may deviate from the expected position
        // (anchor + elapsed) before we treat it as a bogus transient and
        // keep the cached value. 2.5 s covers normal LAN latency and the
        // ~1 s polling resolution while still catching the "back to 0" glitch.
        const val SEEK_PLAUSIBILITY_DELTA_MS = 2_500L
        // While the Sonos is paused (or stopped) the polling tick is
        // throttled to this interval. Lower frequency is fine — the
        // position isn't advancing — and reduces idle LAN chatter. Restored
        // to 1 s as soon as playback (or transitioning) resumes.
        const val POLLING_PAUSED_INTERVAL_MS = 5_000L
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
