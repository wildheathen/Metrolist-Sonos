package com.metrolist.music.upnp

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Typed wrapper around the AVTransport:1 service on a [SonosDevice].
 *
 * All methods are `suspend` and may throw [UpnpError.Network], [UpnpError.HttpStatus],
 * [UpnpError.Soap] or [UpnpError.InvalidXml].
 *
 * InstanceID is hard-coded to "0" — Sonos only ever exposes instance 0.
 */
internal class AVTransport(
    private val device: SonosDevice,
    private val soap: SoapClient,
) {

    private val controlUrl: String = device.controlUrl(Upnp.Service.AV_TRANSPORT)
        ?: throw UpnpError.MissingService(Upnp.Service.AV_TRANSPORT)

    /**
     * Set the current transport URI plus its DIDL-Lite metadata.
     * Does NOT start playback — call [play] afterwards.
     *
     * @param uri the media URL (http/https/x-rincon-...)
     * @param metadata DIDL-Lite document describing the resource. See [DidlBuilder].
     */
    suspend fun setAvTransportUri(uri: String, metadata: String) {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "SetAVTransportURI",
            arguments = listOf(
                "InstanceID" to "0",
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata,
            ),
        )
    }

    /** Prefetch the next track for gapless playback. */
    suspend fun setNextAvTransportUri(uri: String, metadata: String) {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "SetNextAVTransportURI",
            arguments = listOf(
                "InstanceID" to "0",
                "NextURI" to uri,
                "NextURIMetaData" to metadata,
            ),
        )
    }

    suspend fun play(speed: String = "1") {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "Play",
            arguments = listOf(
                "InstanceID" to "0",
                "Speed" to speed,
            ),
        )
    }

    suspend fun pause() {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "Pause",
            arguments = listOf("InstanceID" to "0"),
        )
    }

    suspend fun stop() {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "Stop",
            arguments = listOf("InstanceID" to "0"),
        )
    }

    /** Seek to an absolute time within the current track. */
    suspend fun seek(position: Duration) {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "Seek",
            arguments = listOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to formatHms(position),
            ),
        )
    }

    suspend fun getTransportInfo(): TransportInfo {
        val out = soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "GetTransportInfo",
            arguments = listOf("InstanceID" to "0"),
        )
        return TransportInfo(
            state = out["CurrentTransportState"].orEmpty(),
            status = out["CurrentTransportStatus"].orEmpty(),
            speed = out["CurrentSpeed"].orEmpty(),
        )
    }

    suspend fun getPositionInfo(): PositionInfo {
        val out = soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.AV_TRANSPORT,
            action = "GetPositionInfo",
            arguments = listOf("InstanceID" to "0"),
        )
        return PositionInfo(
            trackNumber = out["Track"]?.toIntOrNull() ?: 0,
            trackDuration = parseHms(out["TrackDuration"]),
            trackUri = out["TrackURI"].orEmpty(),
            trackMetadata = out["TrackMetaData"].orEmpty(),
            relativeTime = parseHms(out["RelTime"]),
            absoluteTime = parseHms(out["AbsTime"]),
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------
    private fun formatHms(d: Duration): String {
        val total = d.inWholeSeconds
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "%d:%02d:%02d".format(h, m, s)
    }

    /** Parse "H:MM:SS" (or "NOT_IMPLEMENTED") from UPnP time values. */
    private fun parseHms(value: String?): Duration {
        if (value.isNullOrBlank() || value == "NOT_IMPLEMENTED") return ZERO
        val parts = value.split(':')
        return try {
            when (parts.size) {
                3 -> parts[0].toInt().hours + parts[1].toInt().minutes +
                    parts[2].substringBefore('.').toInt().seconds
                else -> ZERO
            }
        } catch (_: NumberFormatException) {
            ZERO
        }
    }
}

/** Snapshot of AVTransport GetTransportInfo. */
internal data class TransportInfo(
    val state: String,
    val status: String,
    val speed: String,
) {
    val isPlaying: Boolean get() = state == Upnp.TransportState.PLAYING
    val isPaused: Boolean get() = state == Upnp.TransportState.PAUSED_PLAYBACK
    val isStopped: Boolean get() = state == Upnp.TransportState.STOPPED
    val isTransitioning: Boolean get() = state == Upnp.TransportState.TRANSITIONING
}

/** Snapshot of AVTransport GetPositionInfo. */
internal data class PositionInfo(
    val trackNumber: Int,
    val trackDuration: Duration,
    val trackUri: String,
    val trackMetadata: String,
    val relativeTime: Duration,
    val absoluteTime: Duration,
)
