package com.metrolist.music.upnp

import android.content.Context
import android.net.wifi.WifiManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.StringReader
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URI

/**
 * SSDP-based discovery of Sonos (UPnP) devices on the local network.
 *
 * On Android we must acquire a [WifiManager.MulticastLock] before sending or
 * receiving multicast UDP — many phone vendors drop multicast frames otherwise
 * to save power. The lock is acquired for the duration of [discover] only.
 *
 * Usage:
 * ```
 * val devices = UpnpDiscovery(context, httpClient).discover()
 * ```
 */
class UpnpDiscovery(
    private val context: Context,
    private val httpClient: HttpClient,
) {

    /**
     * Run a full discovery cycle and return the Sonos devices found.
     *
     * @param timeoutMs how long to listen for SSDP responses. 3-5s is the
     *                  conventional value; the protocol's `MX: 3` header asks
     *                  devices to answer within 3 s.
     * @param mx the UPnP `MX` header value (seconds).
     * @param filterSonos if true, devices whose manufacturer/friendlyName does
     *                    not contain "sonos" are discarded.
     * @param requirePlayback if true, devices without AVTransport (e.g. Sub Mini)
     *                        are discarded. Recommended.
     */
    suspend fun discover(
        timeoutMs: Long = 5_000,
        mx: Int = 3,
        filterSonos: Boolean = true,
        requirePlayback: Boolean = true,
    ): List<SonosDevice> = withContext(Dispatchers.IO) {
        val multicastLock = acquireMulticastLock()
        try {
            val locations = ssdpSearch(timeoutMs, mx)
            Timber.d("SSDP discovery found %d unique LOCATION headers", locations.size)

            // Fetch device descriptions concurrently
            val devices = coroutineScope {
                locations.map { loc ->
                    async { fetchDevice(loc) }
                }.awaitAll().filterNotNull()
            }

            devices.filter {
                val passesSonos = !filterSonos ||
                    it.modelName.contains("sonos", true) ||
                    it.roomName.contains("sonos", true)
                val passesPlayback = !requirePlayback || it.canPlay
                passesSonos && passesPlayback
            }
        } finally {
            multicastLock?.release()
        }
    }

    // ---------------------------------------------------------------------
    // SSDP: send M-SEARCH on every IPv4 interface, collect LOCATION headers.
    // ---------------------------------------------------------------------
    private fun ssdpSearch(timeoutMs: Long, mx: Int): Set<String> {
        val searchTargets = listOf(
            // Sonos-specific ST — less noisy than ssdp:all, only Sonos responds.
            "urn:schemas-upnp-org:device:ZonePlayer:1",
            // Fallback for older firmwares / other UPnP media renderers.
            "ssdp:all",
        )

        val packet = buildMSearchPacket(mx, searchTargets.first())
        val locations = mutableSetOf<String>()
        val interfaces = localIPv4Interfaces()

        if (interfaces.isEmpty()) {
            Timber.w("No local IPv4 interfaces available for SSDP")
            return emptySet()
        }

        // One socket per interface — crucial on Windows-like multi-NIC hosts
        // and on Android devices with both WiFi + cellular active.
        val sockets = interfaces.mapNotNull { iface ->
            try {
                MulticastSocket().apply {
                    networkInterface = NetworkInterface.getByInetAddress(iface)
                    soTimeout = 500
                }.also { s ->
                    // Send on this socket
                    for (st in searchTargets) {
                        val pkt = buildMSearchPacket(mx, st)
                        s.send(pkt)
                    }
                }
            } catch (e: Exception) {
                Timber.d(e, "SSDP: failed to open socket on %s", iface)
                null
            }
        }

        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                for (sock in sockets) {
                    val resp = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(resp)
                    } catch (_: Exception) {
                        continue
                    }
                    val text = String(resp.data, 0, resp.length, Charsets.ISO_8859_1)
                    parseLocation(text)?.let { locations += it }
                }
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
        return locations
    }

    private fun buildMSearchPacket(mx: Int, st: String): DatagramPacket {
        val body = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: ").append(Upnp.SSDP_ADDRESS).append(':').append(Upnp.SSDP_PORT).append("\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: ").append(mx).append("\r\n")
            append("ST: ").append(st).append("\r\n")
            append("USER-AGENT: ").append(Upnp.USER_AGENT).append("\r\n")
            append("\r\n")
        }
        val bytes = body.toByteArray(Charsets.ISO_8859_1)
        return DatagramPacket(
            bytes, bytes.size,
            InetAddress.getByName(Upnp.SSDP_ADDRESS), Upnp.SSDP_PORT
        )
    }

    private fun parseLocation(ssdpResponse: String): String? {
        for (line in ssdpResponse.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("location", ignoreCase = true)) {
                val url = line.substring(idx + 1).trim()
                if (url.isNotEmpty()) return url
            }
        }
        return null
    }

    private fun localIPv4Interfaces(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            if (!iface.supportsMulticast()) continue
            for (addr in iface.inetAddresses) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    result += addr
                }
            }
        }
        return result
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("metrolist-upnp-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not acquire MulticastLock — discovery may fail on some devices")
            null
        }
    }

    /**
     * Fetch a device description directly from an IP, bypassing SSDP.
     * Useful when the phone's multicast is flaky or the device is on a
     * subnet the current WiFi cannot multicast to.
     */
    suspend fun fetchByIp(ip: String, port: Int = Upnp.SONOS_DEFAULT_PORT): SonosDevice? =
        withContext(Dispatchers.IO) {
            val location = "http://$ip:$port/xml/device_description.xml"
            fetchDevice(location)
        }

    // ---------------------------------------------------------------------
    // Device description fetch + parse.
    // ---------------------------------------------------------------------
    private suspend fun fetchDevice(location: String): SonosDevice? {
        val xml = try {
            withTimeoutOrNull(4_000) {
                val resp = httpClient.get(location) {
                    header("User-Agent", Upnp.USER_AGENT)
                }
                if (!resp.status.isSuccess()) {
                    Timber.d("Device description %s returned HTTP %s", location, resp.status.value)
                    return@withTimeoutOrNull null
                }
                resp.bodyAsText()
            }
        } catch (e: TimeoutCancellationException) {
            Timber.d(e, "Timeout fetching device description %s", location)
            null
        } catch (e: Exception) {
            Timber.d(e, "Failed fetching device description %s", location)
            null
        } ?: return null

        return try {
            parseDeviceDescription(xml, baseUrlFrom(location))
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse device description %s", location)
            null
        }
    }

    private fun baseUrlFrom(location: String): String {
        val uri = URI(location)
        val port = if (uri.port == -1) {
            if (uri.scheme.equals("https", true)) 443 else 80
        } else uri.port
        return "${uri.scheme}://${uri.host}:$port"
    }

    private fun parseDeviceDescription(xml: String, baseUrl: String): SonosDevice? {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(xml))

        // Walk the tree, collecting service URLs from the top-level device
        // and any sub-devices (MediaServer, MediaRenderer on Sonos).
        val collectedServices = mutableMapOf<String, UpnpService>()
        var roomName = ""
        var modelName = ""
        var modelNumber = ""
        var softwareVersion: String? = null
        var udn = ""
        var ip = ""
        try {
            ip = URI(baseUrl).host ?: ""
        } catch (_: Exception) {
        }

        // Depth tracking for nested <device> blocks.
        val deviceStack = ArrayDeque<DeviceFrame>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val local = parser.name
                val ns = parser.namespace
                when {
                    ns == Upnp.NS_DEVICE && local == "device" -> {
                        deviceStack.addLast(DeviceFrame())
                    }
                    deviceStack.isNotEmpty() && ns == Upnp.NS_DEVICE -> {
                        val frame = deviceStack.last()
                        when (local) {
                            "friendlyName" -> frame.friendlyName = parser.nextText().trim()
                            "manufacturer" -> frame.manufacturer = parser.nextText().trim()
                            "modelName" -> frame.modelName = parser.nextText().trim()
                            "modelNumber" -> frame.modelNumber = parser.nextText().trim()
                            "UDN" -> frame.udn = parser.nextText().trim()
                            "softwareVersion" -> frame.softwareVersion = parser.nextText().trim()
                            "service" -> parseService(parser)?.let { svc ->
                                // First occurrence of a given serviceType wins.
                                collectedServices.putIfAbsent(svc.serviceType, svc)
                            }
                            else -> {} // ignore
                        }
                    }
                }
            } else if (eventType == XmlPullParser.END_TAG &&
                parser.namespace == Upnp.NS_DEVICE && parser.name == "device"
            ) {
                val done = deviceStack.removeLast()
                // Remember the richest metadata we see — the MediaRenderer
                // sub-device typically has the most user-meaningful roomName,
                // while the root device has modelName/manufacturer.
                if (done.modelName.isNotBlank() && modelName.isBlank()) modelName = done.modelName
                if (done.modelNumber.isNotBlank() && modelNumber.isBlank()) modelNumber = done.modelNumber
                if (done.udn.isNotBlank() && udn.isBlank()) udn = done.udn
                if (done.softwareVersion != null && softwareVersion == null) softwareVersion = done.softwareVersion
                if (done.friendlyName.isNotBlank()) {
                    // Prefer the sub-device friendly name that does NOT include
                    // Media Server / Media Renderer suffix (i.e. the room name).
                    val cleaned = done.friendlyName.substringBefore(" - Sonos").trim()
                    if (roomName.isBlank() || cleaned.length < roomName.length) {
                        roomName = cleaned
                    }
                }
            }
            eventType = parser.next()
        }

        if (collectedServices.isEmpty()) return null
        return SonosDevice(
            ip = ip,
            baseUrl = baseUrl,
            udn = udn,
            roomName = roomName,
            modelName = modelName,
            modelNumber = modelNumber,
            softwareVersion = softwareVersion,
            services = collectedServices,
        )
    }

    private fun parseService(parser: XmlPullParser): UpnpService? {
        var serviceType = ""
        var serviceId = ""
        var controlUrl = ""
        var scpdUrl = ""
        var eventUrl = ""
        val startDepth = parser.depth
        while (!(parser.eventType == XmlPullParser.END_TAG && parser.depth == startDepth)) {
            parser.next()
            if (parser.eventType == XmlPullParser.START_TAG && parser.namespace == Upnp.NS_DEVICE) {
                when (parser.name) {
                    "serviceType" -> serviceType = parser.nextText().trim()
                    "serviceId" -> serviceId = parser.nextText().trim()
                    "controlURL" -> controlUrl = parser.nextText().trim()
                    "SCPDURL" -> scpdUrl = parser.nextText().trim()
                    "eventSubURL" -> eventUrl = parser.nextText().trim()
                }
            }
            if (parser.eventType == XmlPullParser.END_DOCUMENT) break
        }
        return if (serviceType.isEmpty() || controlUrl.isEmpty()) null
        else UpnpService(serviceType, serviceId, controlUrl, scpdUrl, eventUrl)
    }

    private class DeviceFrame {
        var friendlyName: String = ""
        var manufacturer: String = ""
        var modelName: String = ""
        var modelNumber: String = ""
        var udn: String = ""
        var softwareVersion: String? = null
    }
}
