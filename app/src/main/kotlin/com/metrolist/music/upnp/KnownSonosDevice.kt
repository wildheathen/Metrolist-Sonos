package com.metrolist.music.upnp

import kotlinx.serialization.Serializable

/**
 * A "thin" persistent snapshot of a [SonosDevice] that we remember across
 * app launches so users can reconnect without re-running SSDP discovery.
 *
 * We store only stable identity + metadata for display. When the user taps
 * "Reconnect", we refetch the live device description from [baseUrl] to get
 * current control URLs (in case they changed after a firmware update).
 */
@Serializable
data class KnownSonosDevice(
    val udn: String,
    val ip: String,
    val baseUrl: String,
    val roomName: String,
    val modelName: String,
    val lastUsedEpochMillis: Long,
) {
    val displayName: String
        get() = if (roomName.isNotBlank()) "$roomName ($modelName)" else modelName
}

internal fun SonosDevice.toKnown(nowMillis: Long): KnownSonosDevice =
    KnownSonosDevice(
        udn = udn,
        ip = ip,
        baseUrl = baseUrl,
        roomName = roomName,
        modelName = modelName,
        lastUsedEpochMillis = nowMillis,
    )
