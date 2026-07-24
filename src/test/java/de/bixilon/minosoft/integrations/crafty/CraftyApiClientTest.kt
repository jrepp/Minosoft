/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.integrations.crafty

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import java.net.http.HttpRequest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CraftyApiClientTest {

    @Test
    fun `server ping authenticates encodes and parses typed response`() {
        val transport = RecordingTransport(
            jsonResponse(
                """
                {
                  "success": true,
                  "data": {
                    "version": {"name": "1.20.4", "protocol": 765, "cleanName": "1.20.4"},
                    "players": {"max": 20, "online": 3, "sample": []},
                    "description": "A server",
                    "cleanDescription": "A server",
                    "information": [],
                    "modList": []
                  }
                }
                """.trimIndent()
            )
        )
        val client = client(transport)

        val ping = client.pingServer("play.example.test:25565", CraftyServerEdition.BEDROCK)

        assertEquals("https://example.test/api/v2/servers/ping?ip=play.example.test%3A25565&edition=bedrock", transport.last.uri().toString())
        assertEquals("Bearer secret-token", transport.last.headers().firstValue("Authorization").orElse(null))
        assertEquals("GET", transport.last.method())
        assertEquals(765, ping.version?.protocol)
        assertEquals(3, ping.players?.online)
        assertEquals("A server", ping.cleanDescription)
    }

    @Test
    fun `player endpoints return unwrapped flexible JSON`() {
        val transport = RecordingTransport(jsonResponse("""{"success":true,"data":{"username":"Alex"}}"""))
        val client = client(transport)

        assertEquals("Alex", client.searchPlayers("Alex Smith").path("username").asText())
        assertEquals("https://example.test/api/v2/players?search=Alex%20Smith", transport.last.uri().toString())

        assertEquals("Alex", client.getPlayer("Alex/Smith").path("username").asText())
        assertEquals("https://example.test/api/v2/players/Alex%2FSmith", transport.last.uri().toString())
    }

    @Test
    fun `skin endpoints preserve caller-owned binary content`() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        val transport = RecordingTransport(
            CraftyTransportResponse(200, mapOf("Content-Type" to listOf("image/png")), bytes)
        )
        val client = client(transport)

        val raw = client.getRawSkin("Alex")
        assertEquals("https://example.test/api/v2/skins/Alex/raw", transport.last.uri().toString())
        assertEquals("image/png", raw.contentType)
        assertContentEquals(bytes, raw.bytes)

        client.getSkinByHash("abc123")
        assertEquals("https://example.test/api/v2/skins/abc123", transport.last.uri().toString())
    }

    @Test
    fun `all canvas endpoints use documented post routes`() {
        val transport = RecordingTransport(binaryResponse())
        val client = client(transport)
        val paths = mutableListOf<String>()

        client.generateAchievement(item = "stone sword", title = "Ready", text = "Go")
        paths += transport.last.uri().rawPath
        assertEquals("item=stone%20sword&title=Ready&text=Go", transport.last.uri().rawQuery)

        client.generateDeathScreen(text = "Oops", score = "42")
        paths += transport.last.uri().rawPath
        client.generateObserver(top = "Top", bottom = "Bottom")
        paths += transport.last.uri().rawPath
        client.generateSign(text = "Hello")
        paths += transport.last.uri().rawPath
        client.generateSplashText(text = "Splash")
        paths += transport.last.uri().rawPath
        client.generateText(text = "Text")
        paths += transport.last.uri().rawPath

        assertEquals(
            listOf(
                "/api/v2/canvas/achievement",
                "/api/v2/canvas/death-screen",
                "/api/v2/canvas/observer",
                "/api/v2/canvas/sign",
                "/api/v2/canvas/splashtext",
                "/api/v2/canvas/text",
            ),
            paths,
        )
        assertTrue(transport.requests.all { it.method() == "POST" })
    }

    @Test
    fun `http and envelope errors retain status and service message`() {
        val transport = RecordingTransport(jsonResponse("""{"success":false,"message":"rate limited"}""", 429))
        val client = client(transport)

        val http = assertThrows<CraftyApiException> { client.searchPlayers("Alex") }
        assertEquals(429, http.statusCode)
        assertEquals("rate limited", http.message)

        transport.response = jsonResponse("""{"success":false,"message":"not available"}""")
        val envelope = assertThrows<CraftyApiException> { client.getPlayer("Alex") }
        assertEquals(200, envelope.statusCode)
        assertEquals("not available", envelope.message)
    }

    @Test
    fun `binary endpoint rejects json error bodies`() {
        val transport = RecordingTransport(jsonResponse("""{"success":false,"message":"invalid skin"}"""))
        val client = client(transport)

        val error = assertThrows<CraftyApiException> { client.getRawSkin("Alex") }

        assertEquals("invalid skin", error.message)
    }

    @Test
    fun `environment factory requires explicit token`() {
        assertThrows<CraftyApiConfigurationException> {
            CraftyApiClient.fromEnvironment(emptyMap())
        }

        val client = CraftyApiClient.fromEnvironment(mapOf(CraftyApiClient.API_TOKEN_ENVIRONMENT to "token"))
        assertFalse(client.toString().contains("token"))
    }

    @Test
    fun `response bodies are bounded before parsing`() {
        val transport = RecordingTransport(
            CraftyTransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/octet-stream")),
                body = ByteArray(CraftyApiClient.MAX_RESPONSE_BYTES + 1),
            )
        )

        val error = assertThrows<CraftyApiException> { client(transport).getRawSkin("Alex") }

        assertEquals(200, error.statusCode)
        assertTrue(error.message.orEmpty().contains("exceeds"))
    }

    @Test
    fun `base URI rejects embedded credentials`() {
        assertThrows<CraftyApiConfigurationException> {
            CraftyApiClient(
                apiToken = "secret-token",
                baseUri = URI.create("https://user:password@example.test/api/v2"),
                transport = RecordingTransport(binaryResponse()),
            )
        }
    }

    private fun client(transport: RecordingTransport): CraftyApiClient {
        return CraftyApiClient(
            apiToken = "secret-token",
            baseUri = URI.create("https://example.test/api/v2"),
            transport = transport,
        )
    }

    private class RecordingTransport(
        var response: CraftyTransportResponse,
    ) : CraftyApiTransport {
        val requests = mutableListOf<HttpRequest>()
        val last: HttpRequest get() = requests.last()

        override fun send(request: HttpRequest): CraftyTransportResponse {
            requests += request
            return response
        }
    }

    companion object {
        private fun jsonResponse(body: String, status: Int = 200): CraftyTransportResponse {
            return CraftyTransportResponse(
                statusCode = status,
                headers = mapOf("Content-Type" to listOf("application/json")),
                body = body.toByteArray(),
            )
        }

        private fun binaryResponse(): CraftyTransportResponse {
            return CraftyTransportResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("image/png")),
                body = byteArrayOf(0x01),
            )
        }
    }
}
