/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.data.registries.biomes

import de.bixilon.kutil.enums.EnumUtil
import de.bixilon.kutil.enums.ValuesEnum

enum class BiomeTemperatureModifier {
    NONE,
    FROZEN,
    ;

    companion object : ValuesEnum<BiomeTemperatureModifier> {
        override val VALUES = values()
        override val NAME_MAP = EnumUtil.getEnumValues(VALUES)
    }
}
