package com.metrolist.music.upnp

/**
 * Constants and well-known identifiers for the UPnP / Sonos integration.
 * See the device dump at `generator/data/sonos-S19-2.json` in svrooij/sonos-api-docs
 * for the authoritative list of services and actions on modern Sonos firmware.
 */
internal object Upnp {

    /** SSDP multicast group (IPv4). */
    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900

    /**
     * Default port Sonos speakers use for their embedded UPnP HTTP server.
     * The SSDP LOCATION header encodes the full URL so we should not hard-code
     * this except as a fallback when the user passes `--host IP` directly.
     */
    const val SONOS_DEFAULT_PORT = 1400

    /** XML namespaces. */
    const val NS_DEVICE = "urn:schemas-upnp-org:device-1-0"
    const val NS_SCPD = "urn:schemas-upnp-org:service-1-0"
    const val NS_SOAP = "http://schemas.xmlsoap.org/soap/envelope/"
    const val NS_DIDL = "urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    const val NS_DC = "http://purl.org/dc/elements/1.1/"
    const val NS_UPNP_META = "urn:schemas-upnp-org:metadata-1-0/upnp/"

    /** Service type URIs we consume. */
    object Service {
        const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
        const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory:1"
    }

    /** Conventional RenderingControl channels. */
    object Channel {
        const val MASTER = "Master"
        const val LEFT_FRONT = "LF"
        const val RIGHT_FRONT = "RF"
    }

    /** UPnP AVTransport transport state values (from SCPD allowedValueList). */
    object TransportState {
        const val STOPPED = "STOPPED"
        const val PLAYING = "PLAYING"
        const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
        const val TRANSITIONING = "TRANSITIONING"
        const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
    }

    /** User-Agent used for all our UPnP / HTTP interactions. */
    const val USER_AGENT = "Metrolist-UPnP/1.0 (Android)"
}
