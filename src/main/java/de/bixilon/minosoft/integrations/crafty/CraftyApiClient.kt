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

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.minosoft.util.json.Jackson
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class CraftyApiClient private constructor(
    apiToken: String,
    baseUri: URI,
    private val transport: CraftyApiTransport,
    private val requestTimeout: Duration,
) {
    private val authorization = "Bearer ${validateToken(apiToken)}"
    private val baseUrl = validateBaseUri(baseUri)

    constructor(
        apiToken: String,
        baseUri: URI = DEFAULT_BASE_URI,
        requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
        httpClient: HttpClient = createHttpClient(requestTimeout),
    ) : this(apiToken, baseUri, JdkCraftyApiTransport(httpClient), requestTimeout)

    internal constructor(
        apiToken: String,
        baseUri: URI,
        transport: CraftyApiTransport,
    ) : this(apiToken, baseUri, transport, DEFAULT_REQUEST_TIMEOUT)

    fun searchPlayers(search: String): JsonNode {
        return getJson(
            path = "players",
            query = listOf("search" to required("search", search)),
        )
    }

    fun getPlayer(username: String): JsonNode {
        return getJson("players/${encode(required("username", username))}")
    }

    fun pingServer(
        address: String,
        edition: CraftyServerEdition = CraftyServerEdition.JAVA,
    ): CraftyServerPing {
        val data = getJson(
            path = "servers/ping",
            query = listOf(
                "ip" to required("address", address),
                "edition" to edition.wireName,
            ),
        )
        return CraftyServerPing.from(data)
    }

    fun getRawSkin(username: String): CraftyBinaryResponse {
        return getBinary("skins/${encode(required("username", username))}/raw")
    }

    fun getSkinByHash(hash: String): CraftyBinaryResponse {
        return getBinary("skins/${encode(required("hash", hash))}")
    }

    fun generateAchievement(
        item: String? = null,
        title: String? = null,
        text: String? = null,
    ): CraftyBinaryResponse {
        return postCanvas("achievement", "item" to item, "title" to title, "text" to text)
    }

    fun generateDeathScreen(
        text: String? = null,
        score: String? = null,
    ): CraftyBinaryResponse {
        return postCanvas("death-screen", "text" to text, "score" to score)
    }

    fun generateObserver(
        top: String? = null,
        bottom: String? = null,
    ): CraftyBinaryResponse {
        return postCanvas("observer", "top" to top, "bottom" to bottom)
    }

    fun generateSign(text: String? = null): CraftyBinaryResponse {
        return postCanvas("sign", "text" to text)
    }

    fun generateSplashText(text: String? = null): CraftyBinaryResponse {
        return postCanvas("splashtext", "text" to text)
    }

    fun generateText(text: String? = null): CraftyBinaryResponse {
        return postCanvas("text", "text" to text)
    }

    private fun postCanvas(
        endpoint: String,
        vararg query: Pair<String, String?>,
    ): CraftyBinaryResponse {
        val request = requestBuilder("canvas/$endpoint", query.toList())
            .header("Accept", "image/png, application/json")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return binary(transport.send(request))
    }

    private fun getJson(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
    ): JsonNode {
        val request = requestBuilder(path, query)
            .header("Accept", "application/json")
            .GET()
            .build()
        return json(transport.send(request))
    }

    private fun getBinary(path: String): CraftyBinaryResponse {
        val request = requestBuilder(path)
            .header("Accept", "image/png, application/octet-stream, application/json")
            .GET()
            .build()
        return binary(transport.send(request))
    }

    private fun requestBuilder(
        path: String,
        query: List<Pair<String, String?>> = emptyList(),
    ): HttpRequest.Builder {
        val presentQuery = query.filter { it.second != null }
        val queryString = if (presentQuery.isEmpty()) {
            ""
        } else {
            presentQuery.joinToString(prefix = "?", separator = "&") { (name, value) ->
                "${encode(name)}=${encode(value!!)}"
            }
        }
        return HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/${path.trimStart('/')}$queryString"))
            .timeout(requestTimeout)
            .header("Authorization", authorization)
            .header("User-Agent", USER_AGENT)
    }

    private fun json(response: CraftyTransportResponse): JsonNode {
        ensureSuccess(response)
        val root = parseJson(response)
        ensureEnvelopeSuccess(response.statusCode, root)
        return root.get("data")?.takeUnless(JsonNode::isNull) ?: root
    }

    private fun binary(response: CraftyTransportResponse): CraftyBinaryResponse {
        ensureSuccess(response)
        val contentType = response.header("content-type")
        if (contentType?.contains("json", ignoreCase = true) == true || response.body.looksLikeJson()) {
            val root = parseJson(response)
            ensureEnvelopeSuccess(response.statusCode, root)
            throw CraftyApiException(response.statusCode, "Crafty API returned JSON where binary content was expected.")
        }
        if (response.body.isEmpty()) {
            throw CraftyApiException(response.statusCode, "Crafty API returned an empty binary response.")
        }
        return CraftyBinaryResponse(contentType = contentType, bytes = response.body)
    }

    private fun ensureSuccess(response: CraftyTransportResponse) {
        if (response.body.size > MAX_RESPONSE_BYTES) {
            throw CraftyApiException(
                response.statusCode,
                "Crafty API response exceeds the $MAX_RESPONSE_BYTES byte limit.",
            )
        }
        if (response.statusCode in 200..299) {
            return
        }
        throw CraftyApiException(
            statusCode = response.statusCode,
            message = response.messageOrNull()
                ?: "Crafty API request failed with HTTP ${response.statusCode}.",
        )
    }

    private fun parseJson(response: CraftyTransportResponse): JsonNode {
        return try {
            Jackson.MAPPER.readTree(response.body)
        } catch (exception: Exception) {
            throw CraftyApiException(
                response.statusCode,
                "Crafty API returned malformed JSON.",
                exception,
            )
        }
    }

    private fun ensureEnvelopeSuccess(statusCode: Int, root: JsonNode) {
        if (root.get("success")?.isBoolean == true && !root.get("success").asBoolean()) {
            throw CraftyApiException(
                statusCode,
                root.get("message")?.asText()?.takeIf(String::isNotBlank)
                    ?: "Crafty API reported an unsuccessful response.",
            )
        }
    }

    companion object {
        val DEFAULT_BASE_URI: URI = URI.create("https://api.crafty.gg/api/v2")
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)
        const val API_TOKEN_ENVIRONMENT = "CRAFTY_API_TOKEN"
        internal const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024
        private const val USER_AGENT = "Minosoft-Crafty-API/1"

        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
        ): CraftyApiClient {
            val token = environment[API_TOKEN_ENVIRONMENT]?.takeIf(String::isNotBlank)
                ?: throw CraftyApiConfigurationException(
                    "$API_TOKEN_ENVIRONMENT is required to use the Crafty public API."
                )
            return CraftyApiClient(token)
        }

        private fun createHttpClient(timeout: Duration): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(timeout)
                // Never risk forwarding the bearer token to a redirected origin.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        }

        private fun validateToken(token: String): String {
            if (token.isBlank()) {
                throw CraftyApiConfigurationException("Crafty API token must not be blank.")
            }
            if (token.any { it == '\r' || it == '\n' }) {
                throw CraftyApiConfigurationException("Crafty API token contains an invalid line break.")
            }
            return token
        }

        private fun validateBaseUri(uri: URI): String {
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                throw CraftyApiConfigurationException("Crafty API base URI must be an absolute HTTP(S) URI.")
            }
            if (uri.rawQuery != null || uri.rawFragment != null) {
                throw CraftyApiConfigurationException("Crafty API base URI must not contain a query or fragment.")
            }
            if (uri.rawUserInfo != null) {
                throw CraftyApiConfigurationException("Crafty API base URI must not contain user information.")
            }
            return uri.toString().trimEnd('/')
        }

        private fun required(name: String, value: String): String {
            if (value.isBlank()) {
                throw IllegalArgumentException("Crafty API $name must not be blank.")
            }
            return value
        }

        private fun encode(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
    }
}

internal fun interface CraftyApiTransport {
    fun send(request: HttpRequest): CraftyTransportResponse
}

internal data class CraftyTransportResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? {
        return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    fun messageOrNull(): String? {
        if (!body.looksLikeJson()) {
            return null
        }
        return runCatching {
            Jackson.MAPPER.readTree(body).get("message")?.asText()?.takeIf(String::isNotBlank)
        }.getOrNull()
    }
}

private class JdkCraftyApiTransport(
    private val client: HttpClient,
) : CraftyApiTransport {
    override fun send(request: HttpRequest): CraftyTransportResponse {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body().use { input ->
            val bytes = input.readNBytes(CraftyApiClient.MAX_RESPONSE_BYTES + 1)
            if (bytes.size > CraftyApiClient.MAX_RESPONSE_BYTES) {
                throw CraftyApiException(
                    response.statusCode(),
                    "Crafty API response exceeds the ${CraftyApiClient.MAX_RESPONSE_BYTES} byte limit.",
                )
            }
            bytes
        }
        return CraftyTransportResponse(
            statusCode = response.statusCode(),
            headers = response.headers().map(),
            body = body,
        )
    }
}

private fun ByteArray.looksLikeJson(): Boolean {
    for (byte in this) {
        val character = byte.toInt().toChar()
        if (character.isWhitespace()) {
            continue
        }
        return character == '{' || character == '['
    }
    return false
}
