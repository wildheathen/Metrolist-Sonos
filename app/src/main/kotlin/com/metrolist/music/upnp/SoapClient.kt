/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.upnp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HeaderValueParam
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.StringReader

/**
 * Thin wrapper around Ktor that builds SOAP/UPnP requests and parses responses.
 *
 * Each call returns [SoapResponse.Success] with the output arguments of the action
 * (as a flat Map keyed by output name) or raises [UpnpError.Soap] / [UpnpError.HttpStatus].
 *
 * Callers should prefer the service-level helpers ([AVTransport], [RenderingControl])
 * instead of using this class directly.
 */
internal class SoapClient(
    private val httpClient: HttpClient,
) {
    private companion object {
        /** `text/xml; charset=utf-8` — built directly to avoid Ktor module/version drift. */
        val XML_UTF8 = ContentType(
            "text",
            "xml",
            listOf(HeaderValueParam("charset", "utf-8"))
        )
    }

    /**
     * Invoke a UPnP action.
     *
     * @param controlUrl absolute control URL (use [SonosDevice.controlUrl]).
     * @param serviceType the service type URN.
     * @param action the action name (e.g. "SetAVTransportURI").
     * @param arguments the input arguments as <name, value> pairs, in declaration order.
     *                  Values are XML-escaped automatically; callers that pass
     *                  DIDL-Lite metadata should pass it raw — it is double-escaped below.
     */
    suspend fun invoke(
        controlUrl: String,
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> {
        val body = buildEnvelope(serviceType, action, arguments)
        val soapAction = "\"$serviceType#$action\""

        val response = try {
            httpClient.post(controlUrl) {
                header("SOAPAction", soapAction)
                header("User-Agent", Upnp.USER_AGENT)
                contentType(XML_UTF8)
                setBody(body)
            }
        } catch (e: Exception) {
            throw UpnpError.Network("HTTP failure calling $action: ${e.message}", e)
        }

        val status = response.status.value
        val responseBody = runCatching { response.bodyAsText() }.getOrDefault("")

        if (response.status.isSuccess()) {
            return parseResponse(responseBody, action)
        }

        // HTTP 500 + SOAP Fault is the standard error path for UPnP.
        // Other 4xx/5xx are raw HTTP failures (e.g. wrong control URL).
        if (status == 500) {
            val fault = parseFault(responseBody)
            if (fault != null) throw UpnpError.Soap(fault.code, fault.description)
        }
        Timber.w("SOAP call %s returned HTTP %d body=%s", action, status, responseBody.take(500))
        throw UpnpError.HttpStatus(status, "SOAP call $action returned HTTP $status")
    }

    // ---------------------------------------------------------------------
    // Envelope building
    // ---------------------------------------------------------------------
    private fun buildEnvelope(
        serviceType: String,
        action: String,
        arguments: List<Pair<String, String>>,
    ): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"").append(Upnp.NS_SOAP).append('"')
        append(" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
        for ((name, value) in arguments) {
            append('<').append(name).append('>')
            append(DidlBuilder.xmlEscape(value))
            append("</").append(name).append('>')
        }
        append("</u:").append(action).append('>')
        append("</s:Body>")
        append("</s:Envelope>")
    }

    // ---------------------------------------------------------------------
    // Response parsing — returns a flat map of output name -> string value.
    // Uses local-name matching to stay agnostic of the SOAP namespace prefix.
    // ---------------------------------------------------------------------
    private fun parseResponse(xml: String, action: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val responseTag = "${action}Response"

        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        parser.setInput(StringReader(xml))

        var insideResponse = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == responseTag) {
                        insideResponse = true
                    } else if (insideResponse) {
                        val name = parser.name
                        val text = runCatching { parser.nextText() }.getOrDefault("")
                        results[name] = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == responseTag) insideResponse = false
                }
            }
            eventType = parser.next()
        }
        return results
    }

    // ---------------------------------------------------------------------
    // SOAP Fault parsing (UPnP dialect).
    // ---------------------------------------------------------------------
    private data class Fault(val code: Int, val description: String)

    private fun parseFault(xml: String): Fault? {
        if (xml.isEmpty()) return null
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }.newPullParser()
        try {
            parser.setInput(StringReader(xml))
        } catch (_: Exception) {
            return null
        }

        var code: Int? = null
        var description = ""
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "errorCode" -> code = runCatching { parser.nextText().trim().toInt() }.getOrNull()
                        "errorDescription" -> description = runCatching { parser.nextText().trim() }.getOrDefault("")
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            return null
        }
        return code?.let { Fault(it, description.ifBlank { "(no description)" }) }
    }
}

/**
 * Wrap the raw Map result of [SoapClient.invoke] for conveyance readers.
 * Kept as a small helper because most actions pick one or two specific outputs.
 */
internal sealed class SoapResponse {
    data class Success(val outputs: Map<String, String>) : SoapResponse()
}
