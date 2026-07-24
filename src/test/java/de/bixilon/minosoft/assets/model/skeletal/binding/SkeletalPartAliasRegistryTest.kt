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

package de.bixilon.minosoft.assets.model.skeletal.binding

import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletalPartAliasRegistryTest {

    @Test
    fun `entity and version specific aliases override generic aliases and unload`() {
        val registry = SkeletalPartAliasRegistry(listOf(SkeletalPartAliasRegistry.HUMANOID))
        val zombie = minecraft("zombie")

        assertEquals(registry.resolve(100, zombie, "leftArm"), "left_arm")

        val registration = registry.register(
            SkeletalPartAliasSet(
                id = "test:zombie-v2",
                entity = zombie,
                minimumVersionId = 100,
                maximumVersionId = 200,
                aliases = mapOf("leftArm" to "custom_left_arm"),
            ),
        )
        assertEquals(registry.resolve(150, zombie, "leftArm"), "custom_left_arm")
        assertEquals(registry.resolve(99, zombie, "leftArm"), "left_arm")

        registration.close()
        assertEquals(registry.resolve(150, zombie, "leftArm"), "left_arm")
    }
}
