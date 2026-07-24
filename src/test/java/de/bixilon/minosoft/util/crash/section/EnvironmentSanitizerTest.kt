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

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EnvironmentSanitizerTest {

    @Test
    fun `secret-shaped environment variables are redacted`() {
        val sanitized = EnvironmentSanitizer.sanitize(
            mapOf(
                "CRAFTY_API_TOKEN" to "crafty-secret",
                "GITHUB_TOKEN" to "github-secret",
                "AWS_SECRET_ACCESS_KEY" to "aws-secret",
                "DATABASE_PASSWORD" to "database-secret",
                "LANG" to "en_US.UTF-8",
            )
        )

        assertEquals(EnvironmentSanitizer.REDACTED, sanitized["CRAFTY_API_TOKEN"])
        assertEquals(EnvironmentSanitizer.REDACTED, sanitized["GITHUB_TOKEN"])
        assertEquals(EnvironmentSanitizer.REDACTED, sanitized["AWS_SECRET_ACCESS_KEY"])
        assertEquals(EnvironmentSanitizer.REDACTED, sanitized["DATABASE_PASSWORD"])
        assertEquals("en_US.UTF-8", sanitized["LANG"])
    }
}
