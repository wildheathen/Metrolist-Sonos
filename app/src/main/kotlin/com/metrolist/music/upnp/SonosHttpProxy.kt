/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded HTTP server that proxies arbitrary upstream audio URLs to the Sonos
 * with a forced `Content-Type: audio/mp4` header.
 *
 * Why this exists: Sonos rejects YouTube googlevideo URLs directly because the
 * upstream response carries `Content-Type: video/mp4`, even when the underlying
 * itag is audio-only AAC inside an MP4 container. Sonos's strict MIME check
 * trips and SetAVTransportURI returns SOAP 714 "Illegal MIME-Type". Re-serving
 * the same bytes with `audio/mp4` makes Sonos accept the stream as a
 * supported musicTrack/audioBroadcast resource.
 *
 * Lifecycle:
 *   - [start] is called when the user connects to a Sonos (controller transitions
 *     to Connected). The server binds to 0.0.0.0 on a random ephemeral port and
 *     records its LAN-facing IPv4 so [proxyUrlFor] can build absolute URLs the
 *     Sonos can fetch.
 *   - [register] adds an upstream URL → opaque token mapping. The Sonos
 *     fetches the proxy URL, the proxy in turn streams from upstream.
 *   - [stop] tears the server down on disconnect. Any tokens registered while
 *     the server was up are dropped — Sonos has the proxy URL embedded in its
 *     transport, but disconnect already issued Stop, so the upstream connection
 *     is no longer needed.
 *
 * Range support: forwards Sonos's `Range` header to upstream and mirrors the
 * 206 / Content-Range response back, so seek-within-track works.
 *
 * Single-shot per token: each registration is consumed once. The Sonos will
 * issue a single GET (or a Range-resumed sequence over the same token) for the
 * duration of the track. When the user moves to the next track, [register] is
 * called again with a fresh token.
 *
 * Thread-safety: token map is a [ConcurrentHashMap]. The Ktor server runs on
 * its own dispatchers; [start]/[stop] should be called from a single thread
 * (the controller's command mutex enforces this).
 */
class SonosHttpProxy(
    private val httpClient: HttpClient,
) {

    private data class Entry(
        val upstreamUrl: String,
        // Wall-clock (epoch ms) when this token was registered. Used to
        // garbage-collect stale entries — see [register] / [evictStale].
        val createdAtMs: Long,
        // Headers to forward upstream (e.g. cookies, user-agent overrides).
        // Sonos doesn't care about them; they're only for the upstream fetch.
        val upstreamHeaders: Map<String, String> = emptyMap(),
    )

    private val tokens = ConcurrentHashMap<String, Entry>()
    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var lanIp: String? = null

    /** True if the server is currently bound and ready to serve requests. */
    val isRunning: Boolean
        get() = server != null && lanIp != null

    /**
     * Start the embedded server on a random ephemeral port. Idempotent — calling
     * twice is a no-op once running. Re-resolves the LAN IPv4 each time it
     * starts (network may have changed).
     */
    fun start() {
        if (server != null) {
            Timber.tag(TAG).d("start() called but server already running on port=$boundPort")
            return
        }
        val ip = resolveLanIpv4()
        if (ip == null) {
            Timber.tag(TAG).w("No LAN IPv4 found — proxy will not start")
            return
        }
        // port=0 lets the OS pick a free port; we read it back from the engine
        // after start() completes.
        val srv = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
            routing {
                get("/sonos/{token}") {
                    val token = call.parameters["token"]
                    val entry = token?.let { tokens[it] }
                    val rangeHeader = call.request.header(HttpHeaders.Range)
                    val ua = call.request.header(HttpHeaders.UserAgent).orEmpty()
                    Timber.tag(TAG).i(
                        "GET /sonos/%s range=%s ua=%s found=%s",
                        token, rangeHeader.orEmpty(), ua.take(40), entry != null,
                    )
                    if (entry == null) {
                        call.respond(HttpStatusCode.NotFound, "unknown token")
                        return@get
                    }
                    try {
                        proxy(entry, rangeHeader, call)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Proxy request failed")
                        // Respond may have already started — best-effort.
                        try {
                            call.respond(HttpStatusCode.BadGateway, "upstream error: ${e.message}")
                        } catch (_: Exception) { /* response already committed */ }
                    }
                }
                // HEAD support for /sonos/{token}: Sonos issues a HEAD before
                // its first GET to learn the resource's Content-Length and
                // codec — without it the device may decide the stream is
                // unusable and refuse to start playback (or worse: stall
                // randomly mid-stream when it tries to seek to a byte
                // offset it never validated). We mirror upstream's HEAD
                // response with our forced Content-Type override.
                head("/sonos/{token}") {
                    val token = call.parameters["token"]
                    val entry = token?.let { tokens[it] }
                    val ua = call.request.header(HttpHeaders.UserAgent).orEmpty()
                    Timber.tag(TAG).i(
                        "HEAD /sonos/%s ua=%s found=%s", token, ua.take(40), entry != null,
                    )
                    if (entry == null) {
                        call.respond(HttpStatusCode.NotFound, "unknown token")
                        return@head
                    }
                    try {
                        proxyHead(entry, call)
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Proxy HEAD failed")
                        try {
                            call.respond(HttpStatusCode.BadGateway, "upstream error: ${e.message}")
                        } catch (_: Exception) { /* response already committed */ }
                    }
                }
                // Health check — useful when manually probing the proxy from
                // a browser on the same LAN. Not strictly required.
                get("/health") {
                    call.respond(HttpStatusCode.OK, "ok")
                }
            }
        }
        try {
            srv.start(wait = false)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start proxy server")
            return
        }
        // CIO exposes the bound port through the engine's resolved connectors
        // — pull the first one (we only registered a single 0.0.0.0 binding).
        val port = try {
            // engine.resolvedConnectors() is suspending in newer Ktor; this
            // is the synchronous accessor available on EmbeddedServer.
            kotlinx.coroutines.runBlocking {
                srv.engine.resolvedConnectors().firstOrNull()?.port ?: 0
            }
        } catch (_: Exception) {
            0
        }
        if (port == 0) {
            Timber.tag(TAG).w("Could not resolve bound port — stopping server")
            srv.stop(0, 0)
            return
        }
        server = srv
        boundPort = port
        lanIp = ip
        Timber.tag(TAG).i("Proxy started on http://$ip:$port")
    }

    /**
     * Stop the server and clear all registered tokens. Safe to call when not
     * running. After [stop] returns the server can be restarted with [start].
     */
    fun stop() {
        val srv = server ?: return
        try {
            // gracePeriod=100ms, timeout=500ms — we don't want to keep the
            // process around waiting for slow Sonos to drain.
            srv.stop(100L, 500L)
            Timber.tag(TAG).i("Proxy stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping proxy")
        } finally {
            server = null
            boundPort = 0
            lanIp = null
            tokens.clear()
        }
    }

    /**
     * Register an upstream URL and return an opaque token the caller should
     * embed in [proxyUrlFor]. The token is a random 16-hex-char string.
     */
    fun register(upstreamUrl: String, upstreamHeaders: Map<String, String> = emptyMap()): String {
        evictStale()
        val token = randomToken()
        tokens[token] = Entry(upstreamUrl, System.currentTimeMillis(), upstreamHeaders)
        Timber.tag(TAG).d("Registered token=$token for upstream=${upstreamUrl.take(80)}… (active=${tokens.size})")
        return token
    }

    /**
     * Drop tokens older than [TOKEN_TTL_MS]. A registered token only needs to
     * stay alive long enough for Sonos to resolve and start fetching — once
     * playback is underway the connection is open and tokens.get() is no
     * longer consulted (the existing socket stream just keeps going). Stale
     * tokens are purely a memory leak.
     *
     * Called opportunistically from [register] (hot path). Cheap: a single
     * pass over the (typically <5) entries, removing what's expired.
     */
    private fun evictStale() {
        val now = System.currentTimeMillis()
        val it = tokens.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAtMs > TOKEN_TTL_MS) {
                it.remove()
            }
        }
    }

    /**
     * Build the absolute proxy URL for a [token] previously returned by
     * [register]. Returns `null` if the proxy isn't running (caller should
     * fall back to the upstream URL — which won't work for Sonos, but at
     * least won't NPE).
     */
    fun proxyUrlFor(token: String): String? {
        val ip = lanIp ?: return null
        val port = boundPort
        if (port == 0) return null
        return "http://$ip:$port/sonos/$token"
    }

    /**
     * Drop a token registration explicitly. Optional — tokens are cleared
     * on [stop] anyway, and overwriting with the same key is fine.
     */
    fun unregister(token: String) {
        tokens.remove(token)
    }

    // -----------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------

    private suspend fun proxy(
        entry: Entry,
        rangeHeader: String?,
        call: io.ktor.server.application.ApplicationCall,
    ) {
        // Issue the upstream request. Forward Range so the Sonos's
        // segmented playback (and seek) translates 1:1 onto upstream byte
        // ranges. Forward any caller-provided headers (cookies, etc.).
        val statement = httpClient.prepareGet(entry.upstreamUrl) {
            entry.upstreamHeaders.forEach { (k, v) -> header(k, v) }
            rangeHeader?.let { header(HttpHeaders.Range, it) }
            // YouTube googlevideo URLs are picky about User-Agent — without
            // a browser-like UA they sometimes return 403. Mirror what
            // Metrolist's main player would send.
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        }
        statement.execute { upstream ->
            // Mirror status (200 or 206 for Range responses)
            val status = HttpStatusCode.fromValue(upstream.status.value)

            // Forward the upstream Content-Length and Content-Range so the
            // Sonos knows how big the resource is and where the byte window
            // sits — crucial for seek and gapless transitions.
            val len = upstream.headers[HttpHeaders.ContentLength]
            val contentRange = upstream.headers[HttpHeaders.ContentRange]

            call.response.header(HttpHeaders.AcceptRanges, "bytes")
            len?.let { call.response.header(HttpHeaders.ContentLength, it) }
            contentRange?.let { call.response.header(HttpHeaders.ContentRange, it) }

            // The whole point of this proxy: override Content-Type so Sonos
            // accepts the stream. We claim audio/mp4 since the upstream is
            // selected as itag-140 AAC-in-MP4.
            call.respondBytesWriter(
                contentType = ContentType.parse("audio/mp4"),
                status = status,
            ) {
                withContext(Dispatchers.IO) {
                    upstream.bodyAsChannel().copyAndClose(this@respondBytesWriter)
                }
            }
        }
    }

    private suspend fun proxyHead(
        entry: Entry,
        call: io.ktor.server.application.ApplicationCall,
    ) {
        // Issue an upstream HEAD so we get headers without the body. We
        // forward the response's Content-Length and Accept-Ranges, but
        // override Content-Type to audio/mp4 — same trick as the GET path.
        val response = httpClient.head(entry.upstreamUrl) {
            entry.upstreamHeaders.forEach { (k, v) -> header(k, v) }
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        }
        response.headers[HttpHeaders.ContentLength]?.let {
            call.response.header(HttpHeaders.ContentLength, it)
        }
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.response.header(HttpHeaders.ContentType, "audio/mp4")
        call.respond(HttpStatusCode.fromValue(response.status.value), Unit)
    }

    private fun randomToken(): String {
        val bytes = ByteArray(8)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Pick the first non-loopback IPv4 address on an "up" interface — that's
     * the one the Sonos can route to (we're on the same LAN). Returns null
     * if the device isn't on a network we can serve from.
     */
    private fun resolveLanIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { addr ->
                    !addr.isLoopbackAddress &&
                        !addr.isLinkLocalAddress &&
                        addr.address.size == 4 // IPv4
                }
                ?.hostAddress
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "resolveLanIpv4 failed")
            null
        }
    }

    private companion object {
        const val TAG = "SonosHttpProxy"
        // 10 minutes — far longer than the time between register() and
        // Sonos's first GET (which is a couple seconds), but bounded so a
        // long-running session doesn't accumulate dozens of stale entries.
        const val TOKEN_TTL_MS = 10L * 60L * 1000L
    }
}
