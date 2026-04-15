/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

/**
 * Errors surfaced by the UPnP layer. Kept narrow and `sealed` so callers
 * (e.g. the Cast UI) can exhaustively render meaningful messages.
 */
sealed class UpnpError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Network I/O failed (timeout, unreachable host, etc.). */
    class Network(message: String, cause: Throwable? = null) : UpnpError(message, cause)

    /** HTTP response with non-2xx status. */
    class HttpStatus(val statusCode: Int, message: String) : UpnpError(message)

    /** XML parse failure (malformed device description, SCPD, or SOAP response). */
    class InvalidXml(message: String, cause: Throwable? = null) : UpnpError(message, cause)

    /**
     * SOAP Fault with a UPnP <errorCode>. 401=Invalid Action, 402=Invalid Args,
     * 501=Action Failed, 7xx=AVTransport-specific, 8xx+=Sonos vendor-specific.
     */
    class Soap(
        val errorCode: Int,
        val errorDescription: String,
    ) : UpnpError("UPnP SOAP error $errorCode: $errorDescription")

    /** The device doesn't expose the service we need (e.g. Sub without AVTransport). */
    class MissingService(val serviceType: String) :
        UpnpError("Device does not expose service $serviceType")

    /** Discovery found no Sonos devices on the network. */
    class NoDevicesFound : UpnpError("No Sonos devices found on the network")
}
