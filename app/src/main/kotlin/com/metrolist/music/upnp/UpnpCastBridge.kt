/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

import com.metrolist.music.models.MediaMetadata

/**
 * Thin glue between the Metrolist playback model ([MediaMetadata]) and the
 * service-agnostic [UpnpCastController]. Lives here rather than inside the
 * controller so the controller stays independent of YouTube Music concerns
 * and would also be usable from any other MediaMetadata source in the future.
 *
 * Typical flow:
 * ```
 * val metadata = playerConnection.currentMetadata.value ?: return
 * val streamUrl = playerConnection.service.getStreamUrl(metadata.id) ?: return
 * controller.castTrack(metadata, streamUrl)
 * ```
 */
suspend fun UpnpCastController.castTrack(
    metadata: MediaMetadata,
    streamUrl: String,
) {
    loadMedia(
        url = streamUrl,
        title = metadata.title,
        artist = metadata.artists.joinToString(", ") { it.name },
        album = metadata.album?.title.orEmpty(),
        albumArtUrl = metadata.thumbnailUrl.orEmpty(),
        mimeType = guessAudioMimeType(streamUrl),
        durationMs = metadata.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
    )
}

/**
 * Best-effort MIME type guess for arbitrary audio stream URLs.
 * Sonos is lenient about `protocolInfo` — wrong MIME rarely breaks playback,
 * but a sensible default helps some legacy renderers. YouTube Music's
 * `googlevideo.com` URLs are typically WebM-containerized Opus (audio/webm)
 * or MP4-containerized AAC (audio/mp4) depending on the selected itag.
 */
private fun guessAudioMimeType(url: String): String {
    val lower = url.lowercase()
    return when {
        // Suffix hints
        lower.endsWith(".mp3") -> "audio/mpeg"
        lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
        lower.endsWith(".webm") -> "audio/webm"
        lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
        lower.endsWith(".flac") -> "audio/flac"
        lower.endsWith(".wav") -> "audio/wav"
        lower.endsWith(".aac") -> "audio/aac"
        // YouTube itag hints in query string
        "mime=audio%2fwebm" in lower || "mime=audio/webm" in lower -> "audio/webm"
        "mime=audio%2fmp4" in lower || "mime=audio/mp4" in lower -> "audio/mp4"
        // Fallback — most universally accepted by Sonos
        else -> "audio/mpeg"
    }
}
