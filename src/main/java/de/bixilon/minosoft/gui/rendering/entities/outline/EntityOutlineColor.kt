/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.display.DisplayEntity
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.RGBAColor
import de.bixilon.minosoft.data.text.formatting.color.RGBColor

/**
 * Resolves vanilla's outline enablement and color independently. A display's
 * glow_color_override selects a color; it does not enable the glowing flag.
 */
object EntityOutlineColor {

    fun resolve(entity: Entity): RGBAColor? {
        if (!entity.hasGlowingEffect) return null

        val override = (entity as? DisplayEntity)?.glowColorOverride ?: -1
        if (override >= 0) return RGBColor(override and 0xFFFFFF).rgba()

        val team = when (entity) {
            is PlayerEntity -> entity.additional.team
            else -> entity.session.scoreboard.getTeam(entity.uuid.toString())
        }
        return team?.formatting?.color?.rgba() ?: ChatColors.WHITE
    }
}
