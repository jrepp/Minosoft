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

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.profile.ProfileOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FabricAdapterOptionStoreTest {
    @TempDir
    lateinit var temporary: Path
    private lateinit var previous: Path

    @BeforeTest
    fun setup() {
        previous = ProfileOptions.path
        ProfileOptions.path = temporary
    }

    @AfterTest
    fun cleanup() {
        ProfileOptions.path = previous
    }

    @Test
    fun `persists bounded adapter values outside repository state`() {
        val defaults = mapOf("enabled" to "true", "mode" to "fast")
        val first = FabricAdapterOptionStore("test_options", defaults)
        first.set("enabled", false)
        first.set("mode", "quality")
        first.persist()

        val second = FabricAdapterOptionStore("test_options", defaults)
        assertFalse(second.boolean("enabled"))
        assertEquals("quality", second.string("mode"))
        assertEquals(temporary.resolve("fabric-options/test_options.properties").toFile().canonicalFile.parentFile.parentFile, temporary.toFile().canonicalFile)
    }
}
