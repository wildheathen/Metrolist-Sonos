/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

/**
 * Builds minimal DIDL-Lite metadata XML documents for SetAVTransportURI.
 *
 * The Sonos is lenient with metadata — for a plain HTTP audio stream the
 * minimal fields below are sufficient. Title/artist drive what appears on
 * the Sonos app; protocolInfo tells the device how to handle the stream.
 */
internal object DidlBuilder {

    /**
     * A single audio track stream whose URI is a direct HTTP(S) URL.
     * Used for YouTube Music ProgressiveWebm/MP4 URLs, etc.
     */
    fun audioItem(
        url: String,
        title: String = "Metrolist",
        creator: String = "",
        album: String = "",
        albumArtUri: String = "",
        mimeType: String = "audio/mpeg",
        durationMs: Long? = null,
        asRadio: Boolean = false,
    ): String {
        val resAttrs = buildString {
            append("protocolInfo=\"http-get:*:")
            append(mimeType)
            append(":*\"")
            durationMs?.let {
                append(" duration=\"")
                append(formatUpnpDuration(it))
                append('"')
            }
        }
        val upnpClass = if (asRadio) {
            // Sonos treats audioBroadcast as a continuous internet-radio stream
            // and skips the strict container/codec checks it applies to
            // musicTrack items — necessary for googlevideo URLs which don't
            // serve plain file-style audio (they're DASH-segmented).
            "object.item.audioItem.audioBroadcast"
        } else {
            "object.item.audioItem.musicTrack"
        }
        return buildString {
            append("<DIDL-Lite xmlns=\"").append(Upnp.NS_DIDL).append('"')
            append(" xmlns:dc=\"").append(Upnp.NS_DC).append('"')
            append(" xmlns:upnp=\"").append(Upnp.NS_UPNP_META).append("\">")
            append("<item id=\"1\" parentID=\"0\" restricted=\"true\">")
            append("<dc:title>").append(xmlEscape(title)).append("</dc:title>")
            if (creator.isNotBlank()) {
                append("<dc:creator>").append(xmlEscape(creator)).append("</dc:creator>")
                // 'upnp:artist' is the correct field on most UPnP renderers
                append("<upnp:artist>").append(xmlEscape(creator)).append("</upnp:artist>")
            }
            if (album.isNotBlank()) {
                append("<upnp:album>").append(xmlEscape(album)).append("</upnp:album>")
            }
            if (albumArtUri.isNotBlank()) {
                append("<upnp:albumArtURI>").append(xmlEscape(albumArtUri)).append("</upnp:albumArtURI>")
            }
            append("<upnp:class>").append(upnpClass).append("</upnp:class>")
            append("<res ").append(resAttrs).append('>').append(xmlEscape(url)).append("</res>")
            append("</item>")
            append("</DIDL-Lite>")
        }
    }

    /** H:MM:SS.mmm format required by UPnP `res@duration`. */
    private fun formatUpnpDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val mmm = ms % 1000
        return "%d:%02d:%02d.%03d".format(h, m, s, mmm)
    }

    /**
     * XML-safe escape. This is the inner DIDL-Lite — callers are expected to
     * XML-escape the entire DIDL string AGAIN when embedding it in the SOAP
     * CurrentURIMetaData argument (so "&lt;" becomes "&amp;lt;"). See SoapClient.
     */
    fun xmlEscape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}
