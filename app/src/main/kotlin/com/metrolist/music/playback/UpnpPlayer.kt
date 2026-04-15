/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.SimpleBasePlayer.PositionSupplier
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.music.upnp.ConnectionState
import com.metrolist.music.upnp.PlaybackState
import com.metrolist.music.upnp.UpnpCastController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

/**
 * Media3 [Player] implementation that forwards playback commands to a
 * [UpnpCastController]. Designed to be swapped into the app's MediaLibrarySession
 * (via `mediaSession.setPlayer(upnpPlayer)`) when the user casts to a Sonos,
 * and swapped back to the local ExoPlayer when they disconnect.
 *
 * This is the same model Media3 uses for Google Cast via `CastPlayer`: the
 * MediaSession sees one unified player so the app UI, lock screen controls,
 * Bluetooth remote, and Android Auto all work transparently against whatever
 * player is currently in charge.
 *
 * Scope of the MVP:
 *   * Single-track playback (queue beyond the current item is not yet
 *     forwarded to the Sonos queue — Phase 5 via SetNextAVTransportURI).
 *   * Seek within the current track is fully supported.
 *   * Volume is proxied to RenderingControl (Sonos 0..100 ⇢ Media3 0..1f).
 *   * Position/duration are polled 1Hz by UpnpCastController and surfaced
 *     as State values on every invalidateState().
 *   * next/previous within the MediaItem list passed at swap-time are
 *     supported; moving to a new item triggers a fresh SetAVTransportURI
 *     with the new stream URL.
 *
 * The player is `@UnstableApi` because SimpleBasePlayer is itself annotated
 * as unstable in Media3 1.x — OK for internal use.
 */
@OptIn(UnstableApi::class)
class UpnpPlayer(
    private val controller: UpnpCastController,
    private val scope: CoroutineScope,
    private val streamUrlProvider: suspend (mediaId: String) -> String?,
    looper: Looper = Looper.getMainLooper(),
) : SimpleBasePlayer(looper) {

    private var items: List<MediaItem> = emptyList()
    private var currentIndex: Int = 0

    // Cached snapshot so getState() doesn't race against flow collection.
    @Volatile private var lastPlayback: PlaybackState = PlaybackState()
    @Volatile private var connected: Boolean = false

    private var observerJob: Job? = null

    init {
        observerJob = scope.launch {
            combine(controller.playbackState, controller.connectionState) { pb, conn ->
                lastPlayback = pb
                connected = conn is ConnectionState.Connected
                Unit
            }.collect {
                // Hop to main looper because SimpleBasePlayer requires it.
                withContext(Dispatchers.Main) { invalidateState() }
            }
        }
    }

    // ---------------------------------------------------------------------
    // State snapshot exposed to Media3
    // ---------------------------------------------------------------------
    override fun getState(): State {
        val pb = lastPlayback
        val playbackState = when {
            !connected -> Player.STATE_IDLE
            pb.isTransitioning -> Player.STATE_BUFFERING
            items.isEmpty() -> Player.STATE_IDLE
            else -> Player.STATE_READY
        }

        val mediaItemData = items.mapIndexed { idx, item ->
            MediaItemData.Builder(item.mediaId.ifBlank { "idx-$idx" })
                .setMediaItem(item)
                .setDurationUs(
                    if (idx == currentIndex && pb.duration.inWholeMilliseconds > 0)
                        pb.duration.inWholeMilliseconds * 1000L
                    else C.TIME_UNSET
                )
                .setIsSeekable(idx == currentIndex)
                .setIsDynamic(false)
                .build()
        }

        val commands = Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_VOLUME)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_GET_DEVICE_VOLUME)
            .add(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)
            .add(Player.COMMAND_RELEASE)
            .build()

        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(pb.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(mediaItemData)
            .setCurrentMediaItemIndex(currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            .setContentPositionMs(pb.position.inWholeMilliseconds)
            .setContentBufferedPositionMs(PositionSupplier.getConstant(pb.position.inWholeMilliseconds))
            .setVolume(pb.volume / 100f)
            .setDeviceVolume(pb.volume)
            .setIsDeviceMuted(pb.isMuted)
            .build()
    }

    // ---------------------------------------------------------------------
    // Command handlers — all return immediate futures because the actual
    // work is fired-and-forgotten on the controller's coroutine scope;
    // Media3 only needs to know the command was accepted. State refresh
    // happens asynchronously when the controller flow ticks.
    // ---------------------------------------------------------------------
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        scope.launch {
            if (items.isEmpty()) return@launch
            if (playWhenReady) {
                if (lastPlayback.currentUrl.isBlank() && currentIndex in items.indices) {
                    // First play on an uninitialized remote → load before playing
                    loadCurrentOnRemote()
                } else {
                    controller.play()
                }
            } else {
                controller.pause()
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        scope.launch { controller.stop() }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        observerJob?.cancel()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        scope.launch {
            val wantsDifferentItem = mediaItemIndex != currentIndex &&
                mediaItemIndex in items.indices
            if (wantsDifferentItem) {
                currentIndex = mediaItemIndex
                loadCurrentOnRemote(startOffsetMs = positionMs.coerceAtLeast(0))
            } else {
                controller.seek(positionMs.coerceAtLeast(0).milliseconds)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        scope.launch {
            if (items.isNotEmpty()) loadCurrentOnRemote(startPositionMs.coerceAtLeast(0))
        }
        return Futures.immediateVoidFuture()
    }

    /**
     * Synchronously prime the remote Sonos with the current media item, queue
     * and start position. Unlike [handleSetMediaItems] (fire-and-forget), this
     * suspends until the SOAP `SetAVTransportURI` + `Play` round-trip completes
     * (or fails) and returns a boolean indicating success.
     *
     * Intended to be called by [MusicService] immediately BEFORE swapping the
     * active player on the MediaSession — so we never expose a fresh UpnpPlayer
     * to the UI before the Sonos has actually accepted the media, preventing
     * the "stuck at 0:00, paused" limbo state documented in issue #2 (Bug 3).
     *
     * @return `true` if the Sonos is playing (or about to play) the requested
     *   track; `false` if the queue is empty, the stream URL could not be
     *   resolved, or the SOAP call failed. On failure the caller should
     *   NOT swap players — the local ExoPlayer should remain active.
     */
    suspend fun primeSonos(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): Boolean {
        val listCopy = mediaItems.toList()
        if (listCopy.isEmpty()) return false
        // `items`/`currentIndex` are read by Media3 from getState() on the
        // application looper, so assign them there to avoid a race with any
        // pending invalidateState() hop already queued on main.
        withContext(Dispatchers.Main) {
            items = listCopy
            currentIndex = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
        val ok = loadCurrentOnRemote(startPositionMs.coerceAtLeast(0))
        // Republish the state so the new playlist snapshot reaches the UI even
        // if the controller flow did not emit (loadMedia may have updated no
        // observable field beyond currentUrl).
        withContext(Dispatchers.Main) { invalidateState() }
        return ok
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        scope.launch { controller.setVolume(deviceVolume.coerceIn(0, 100)) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        scope.launch { controller.setMute(muted) }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        scope.launch { controller.setVolume((volume.coerceIn(0f, 1f) * 100).toInt()) }
        return Futures.immediateVoidFuture()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private suspend fun loadCurrentOnRemote(startOffsetMs: Long = 0): Boolean {
        val item = items.getOrNull(currentIndex) ?: return false
        val mediaId = item.mediaId
        if (mediaId.isBlank()) {
            Timber.w("UpnpPlayer: current MediaItem has no mediaId; cannot resolve stream URL")
            return false
        }

        // First attempt with the currently-cached stream URL.
        if (tryLoadForCurrent(item, mediaId, startOffsetMs)) return true

        // Stream-URL TTL refresh. YouTube Music `googlevideo.com` URLs have a
        // ~6h TTL; if the first Sonos load failed (403/404/SOAP error), request
        // a fresh URL and retry exactly once. We do NOT loop — repeated failures
        // are surfaced to the UI via playbackState.lastError.
        Timber.i("UpnpPlayer: first load failed for %s — refreshing URL and retrying", mediaId)
        return tryLoadForCurrent(item, mediaId, startOffsetMs)
    }

    /**
     * Resolve a fresh stream URL for [mediaId] and issue the SOAP load.
     * Returns whether the Sonos accepted the URL and started playback.
     */
    private suspend fun tryLoadForCurrent(
        item: MediaItem,
        mediaId: String,
        startOffsetMs: Long,
    ): Boolean {
        val url = try {
            streamUrlProvider(mediaId)
        } catch (e: Exception) {
            Timber.w(e, "UpnpPlayer: streamUrlProvider failed for %s", mediaId)
            null
        }
        if (url == null) {
            Timber.w("UpnpPlayer: streamUrlProvider returned null for %s", mediaId)
            return false
        }
        val meta = item.mediaMetadata
        val titleText = meta.title?.toString().orEmpty()
        val artistText = meta.artist?.toString().orEmpty()
        val albumText = meta.albumTitle?.toString().orEmpty()
        val artUrl = meta.artworkUri?.toString().orEmpty()
        val ok = controller.loadMedia(
            url = url,
            title = titleText.ifBlank { "Metrolist" },
            artist = artistText,
            album = albumText,
            albumArtUrl = artUrl,
        )
        if (ok && startOffsetMs > 0) {
            try {
                controller.seek(startOffsetMs.milliseconds)
            } catch (_: Exception) {
            }
        }
        return ok
    }
}
