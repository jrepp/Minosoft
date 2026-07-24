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

package de.bixilon.minosoft.util.crash.section

object EnvironmentSanitizer {
    const val REDACTED = "<redacted>"

    private val sensitiveNames = listOf(
        "TOKEN",
        "SECRET",
        "PASSWORD",
        "PASSWD",
        "PRIVATE_KEY",
        "API_KEY",
        "ACCESS_KEY",
        "CREDENTIAL",
        "AUTHORIZATION",
    )

    fun sanitize(environment: Map<String, String>): Map<String, String> {
        return environment.mapValues { (name, value) ->
            if (isSensitive(name)) REDACTED else value
        }
    }

    private fun isSensitive(name: String): Boolean {
        val normalized = name.uppercase()
        return sensitiveNames.any(normalized::contains)
    }
}
