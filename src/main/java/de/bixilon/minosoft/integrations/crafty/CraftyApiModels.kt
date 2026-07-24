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

enum class CraftyServerEdition(
    val wireName: String,
) {
    JAVA("java"),
    BEDROCK("bedrock"),
}

data class CraftyServerVersion(
    val name: String?,
    val protocol: Int?,
    val cleanName: String?,
)

data class CraftyServerPlayers(
    val max: Int?,
    val online: Int?,
    val sample: JsonNode?,
)

data class CraftyServerPing(
    val version: CraftyServerVersion?,
    val players: CraftyServerPlayers?,
    val description: String?,
    val favicon: String?,
    val cleanDescription: String?,
    val information: JsonNode?,
    val modList: JsonNode?,
) {
    companion object {
        internal fun from(data: JsonNode): CraftyServerPing {
            val version = data.get("version")?.takeUnless(JsonNode::isNull)?.let {
                CraftyServerVersion(
                    name = it.textOrNull("name"),
                    protocol = it.intOrNull("protocol"),
                    cleanName = it.textOrNull("cleanName"),
                )
            }
            val players = data.get("players")?.takeUnless(JsonNode::isNull)?.let {
                CraftyServerPlayers(
                    max = it.intOrNull("max"),
                    online = it.intOrNull("online"),
                    sample = it.get("sample")?.takeUnless(JsonNode::isNull),
                )
            }
            return CraftyServerPing(
                version = version,
                players = players,
                description = data.textOrNull("description"),
                favicon = data.textOrNull("favicon"),
                cleanDescription = data.textOrNull("cleanDescription"),
                information = data.get("information")?.takeUnless(JsonNode::isNull),
                modList = data.get("modList")?.takeUnless(JsonNode::isNull),
            )
        }

        private fun JsonNode.textOrNull(name: String): String? {
            return get(name)?.takeUnless(JsonNode::isNull)?.asText()
        }

        private fun JsonNode.intOrNull(name: String): Int? {
            return get(name)?.takeIf(JsonNode::isIntegralNumber)?.asInt()
        }
    }
}

data class CraftyBinaryResponse(
    val contentType: String?,
    val bytes: ByteArray,
)
