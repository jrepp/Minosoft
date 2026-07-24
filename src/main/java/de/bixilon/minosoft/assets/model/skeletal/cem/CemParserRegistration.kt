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

package de.bixilon.minosoft.assets.model.skeletal.cem

import de.bixilon.minosoft.assets.model.skeletal.*
import java.io.InputStream

object CemGeometryParser : SkeletalGeometryParser {
    override val format = SkeletalContentFormat.OPTIFINE_CEM
    override val suffixes = setOf(".jem", ".jpm")

    override fun parse(context: SkeletalParseContext, input: InputStream): SkeletalContentDocument {
        val parser = CemParser { _, reference -> context.resources.open(reference) }
        val model = if (context.source.path.endsWith(".jpm", ignoreCase = true)) {
            parser.parseJpm(context.source, input)
        } else {
            parser.parseJem(context.source, input)
        }
        return SkeletalContentDocument(context.source, format, null, listOf(model))
    }
}

val CEM_PARSER_REGISTRATION = SkeletalParserRegistration(
    id = "minosoft:optifine-cem",
    geometry = CemGeometryParser,
)
