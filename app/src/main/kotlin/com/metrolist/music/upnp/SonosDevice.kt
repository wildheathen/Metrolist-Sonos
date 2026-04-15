package com.metrolist.music.upnp

/**
 * A Sonos speaker (or any UPnP MediaRenderer) discovered on the LAN.
 *
 * The `baseUrl` is the HTTP origin (`scheme://host:port`) used to resolve
 * relative control/SCPD URLs. Service-specific paths are stored in [services].
 *
 * A device without an [Service.AV_TRANSPORT] entry cannot be targeted for
 * playback (e.g. Sonos Sub Mini, Sonos Boost) and will be filtered out
 * during discovery.
 */
data class SonosDevice(
    /** IPv4 of the device (for display + debug). */
    val ip: String,
    /** Scheme + host + port, e.g. "http://192.168.1.101:1400". */
    val baseUrl: String,
    /** Unique Device Name — stable across sessions (from <UDN>). */
    val udn: String,
    /** Human name shown in the Sonos app, e.g. "Camera principale". */
    val roomName: String,
    /** Commercial model name, e.g. "Sonos Arc". */
    val modelName: String,
    /** Internal model code, e.g. "S19" for Arc. */
    val modelNumber: String,
    /** Firmware version string, e.g. "94.1-75110". */
    val softwareVersion: String?,
    /** Services the device exposes, keyed by serviceType URN. */
    val services: Map<String, UpnpService>,
) {
    /** Human-friendly label, combining room + model. */
    val displayName: String
        get() = if (roomName.isNotBlank()) "$roomName ($modelName)" else modelName

    /** Is this device capable of playing media (has AVTransport)? */
    val canPlay: Boolean
        get() = services.containsKey(Upnp.Service.AV_TRANSPORT)

    /** Is this device a playback target we will show to the user? */
    val isUsable: Boolean
        get() = canPlay && services.containsKey(Upnp.Service.RENDERING_CONTROL)

    fun service(serviceType: String): UpnpService? = services[serviceType]

    /** Absolute URL for a service's control endpoint. */
    fun controlUrl(serviceType: String): String? =
        services[serviceType]?.controlUrl?.let { resolveAbsolute(it) }

    /** Absolute URL for a service's SCPD (schema) endpoint. */
    fun scpdUrl(serviceType: String): String? =
        services[serviceType]?.scpdUrl?.let { resolveAbsolute(it) }

    /** Absolute URL for a service's eventing endpoint. */
    fun eventUrl(serviceType: String): String? =
        services[serviceType]?.eventUrl?.let { resolveAbsolute(it) }

    private fun resolveAbsolute(relativeOrAbsolute: String): String {
        if (relativeOrAbsolute.startsWith("http://", ignoreCase = true) ||
            relativeOrAbsolute.startsWith("https://", ignoreCase = true)
        ) {
            return relativeOrAbsolute
        }
        val trimmedBase = baseUrl.trimEnd('/')
        val trimmedPath = if (relativeOrAbsolute.startsWith('/')) relativeOrAbsolute else "/$relativeOrAbsolute"
        return "$trimmedBase$trimmedPath"
    }
}

/**
 * A UPnP service exposed by a device, as declared in its device description XML.
 * URLs are stored in their raw form (usually relative) and resolved against the
 * device's baseUrl via [SonosDevice.controlUrl] etc.
 */
data class UpnpService(
    val serviceType: String,
    val serviceId: String,
    val controlUrl: String,
    val scpdUrl: String,
    val eventUrl: String,
)
