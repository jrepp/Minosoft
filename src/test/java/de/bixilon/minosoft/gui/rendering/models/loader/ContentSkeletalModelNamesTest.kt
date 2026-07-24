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

package de.bixilon.minosoft.gui.rendering.models.loader

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentSkeletalModelNamesTest {

    @Test
    fun `CEM filename maps to entity id and stable internal model id`() {
        val source = ResourceLocation.of("minecraft:optifine/cem/cow.jem")
        val content = SkeletalContent(
            source = source,
            format = SkeletalContentFormat.OPTIFINE_CEM,
            formatVersion = null,
            identifier = "cow",
            textureSize = Vec2i(64, 32),
            roots = emptyList(),
        )

        assertEquals(ResourceLocation.of("minecraft:cow"), ContentSkeletalModelNames.entity(content))
        assertEquals(
            ResourceLocation.of("minosoft:content/minecraft/cow.smodel"),
            ContentSkeletalModelNames.model(source, content.identifier),
        )
    }
}
