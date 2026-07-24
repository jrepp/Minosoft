/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */
package de.bixilon.minosoft.data.entities.entities.display

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kmath.vec.vec4.f.Vec4f
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.data.EntityDataField
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel

abstract class DisplayEntity(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation) : Entity(session, entityType, data, position, rotation) {

    override val dimensions get() = SIZE // TODO

    val interpolationStartDeltaTicks: Int by data(INTERPOLATION_START, 0)
    val interpolationDurationTicks: Int by data(INTERPOLATION_DURATION, 0)
    val positionRotationInterpolationDurationTicks: Int by data(POSITION_ROTATION_INTERPOLATION_DURATION, 0)
    val translation: Vec3f by data(TRANSLATION, Vec3f.EMPTY)
    val scale: Vec3f by data(SCALE, Vec3f(1.0f))
    val leftRotation: Vec4f by data(LEFT_ROTATION, IDENTITY_ROTATION)
    val rightRotation: Vec4f by data(RIGHT_ROTATION, IDENTITY_ROTATION)
    val billboard: Byte by data(BILLBOARD, 0)
    val brightnessOverride: Int by data(BRIGHTNESS, -1)
    val viewRange: Float by data(VIEW_RANGE, 1.0f)
    val shadowRadius: Float by data(SHADOW_RADIUS, 0.0f)
    val shadowStrength: Float by data(SHADOW_STRENGTH, 1.0f)
    val displayWidth: Float by data(WIDTH, 0.0f)
    val displayHeight: Float by data(HEIGHT, 0.0f)
    val glowColorOverride: Int by data(GLOW_COLOR_OVERRIDE, -1)

    val transformation: DisplayTransformation
        get() = DisplayTransformation(translation, scale, leftRotation, rightRotation)

    val brightness: LightLevel?
        get() {
            if (brightnessOverride < 0) return null
            val block = brightnessOverride ushr 4 and LightLevel.BLOCK_MASK
            val sky = brightnessOverride ushr 20 and LightLevel.SKY_MASK
            return LightLevel(block, sky)
        }

    companion object {
        val SIZE = Vec2f(1.0f)
        val IDENTITY_ROTATION = Vec4f(0.0f, 0.0f, 0.0f, 1.0f)
        val INTERPOLATION_START = EntityDataField("INTERPOLATION_START")
        val INTERPOLATION_DURATION = EntityDataField("INTERPOLATION_DURATION")
        val POSITION_ROTATION_INTERPOLATION_DURATION = EntityDataField("TELEPORT_DURATION", "POSITION_ROTATION_INTERPOLATION_DURATION")
        val TRANSLATION = EntityDataField("TRANSLATION")
        val SCALE = EntityDataField("SCALE")
        val LEFT_ROTATION = EntityDataField("LEFT_ROTATION")
        val RIGHT_ROTATION = EntityDataField("RIGHT_ROTATION")
        val BILLBOARD = EntityDataField("BILLBOARD_RENDER_CONSTRAINTS", "BILLBOARD")
        val BRIGHTNESS = EntityDataField("BRIGHTNESS")
        val VIEW_RANGE = EntityDataField("VIEW_RANGE")
        val SHADOW_RADIUS = EntityDataField("SHADOW_RADIUS")
        val SHADOW_STRENGTH = EntityDataField("SHADOW_STRENGTH")
        val WIDTH = EntityDataField("WIDTH")
        val HEIGHT = EntityDataField("HEIGHT")
        val GLOW_COLOR_OVERRIDE = EntityDataField("GLOW_COLOR_OVERRIDE")
    }
}
