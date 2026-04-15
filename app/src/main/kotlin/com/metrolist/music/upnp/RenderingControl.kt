/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

/**
 * Typed wrapper around the RenderingControl:1 service on a [SonosDevice].
 *
 * All methods target InstanceID=0 and Channel=Master by default — that's what
 * Sonos uses for its single zone-wide volume control.
 */
internal class RenderingControl(
    private val device: SonosDevice,
    private val soap: SoapClient,
) {

    private val controlUrl: String = device.controlUrl(Upnp.Service.RENDERING_CONTROL)
        ?: throw UpnpError.MissingService(Upnp.Service.RENDERING_CONTROL)

    /** Current volume in the 0..100 range. */
    suspend fun getVolume(channel: String = Upnp.Channel.MASTER): Int {
        val out = soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.RENDERING_CONTROL,
            action = "GetVolume",
            arguments = listOf(
                "InstanceID" to "0",
                "Channel" to channel,
            ),
        )
        return out["CurrentVolume"]?.toIntOrNull() ?: 0
    }

    /** Set volume; value is clamped to 0..100. */
    suspend fun setVolume(volume: Int, channel: String = Upnp.Channel.MASTER) {
        val clamped = volume.coerceIn(0, 100)
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.RENDERING_CONTROL,
            action = "SetVolume",
            arguments = listOf(
                "InstanceID" to "0",
                "Channel" to channel,
                "DesiredVolume" to clamped.toString(),
            ),
        )
    }

    suspend fun getMute(channel: String = Upnp.Channel.MASTER): Boolean {
        val out = soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.RENDERING_CONTROL,
            action = "GetMute",
            arguments = listOf(
                "InstanceID" to "0",
                "Channel" to channel,
            ),
        )
        return out["CurrentMute"] == "1"
    }

    suspend fun setMute(mute: Boolean, channel: String = Upnp.Channel.MASTER) {
        soap.invoke(
            controlUrl = controlUrl,
            serviceType = Upnp.Service.RENDERING_CONTROL,
            action = "SetMute",
            arguments = listOf(
                "InstanceID" to "0",
                "Channel" to channel,
                "DesiredMute" to if (mute) "1" else "0",
            ),
        )
    }
}
