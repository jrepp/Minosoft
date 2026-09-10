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

package de.bixilon.minosoft.data.registries.blocks.types.pixlyzer

import de.bixilon.minosoft.data.registries.blocks.properties.BlockProperties
import de.bixilon.minosoft.data.registries.blocks.properties.list.MapPropertyList
import de.bixilon.minosoft.data.registries.blocks.factory.PixLyzerBlockFactory
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.registries.Registries
import de.bixilon.minosoft.protocol.versions.Version

/** Preserves the directional state surface used by multipart wall models. */
open class WallBlock(
    identifier: ResourceLocation,
    registries: Registries,
    data: Map<String, Any>,
) : PixLyzerBlock(identifier, registries, data) {

    override fun registerProperties(version: Version, list: MapPropertyList) {
        super.registerProperties(version, list)
        list += BlockProperties.MULTIPART_NORTH
        list += BlockProperties.MULTIPART_EAST
        list += BlockProperties.MULTIPART_SOUTH
        list += BlockProperties.MULTIPART_WEST
        list += BlockProperties.MULTIPART_UP
    }

    companion object : PixLyzerBlockFactory<WallBlock> {
        override fun build(identifier: ResourceLocation, registries: Registries, data: Map<String, Any>) =
            WallBlock(identifier, registries, data)
    }
}
