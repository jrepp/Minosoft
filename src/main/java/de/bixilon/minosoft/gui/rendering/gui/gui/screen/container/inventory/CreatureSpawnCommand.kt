/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.Locale
import kotlin.math.sqrt

internal enum class CreatureSpawnVariant(
    internal val summonNbt: String? = null,
) {
    DEFAULT,
    SHEEP_WOOLLY("{Sheared:0b}"),
    SHEEP_SHEARED("{Sheared:1b}"),
}

internal object CreatureSpawnCommand {
    const val COMMAND = "summon"
    const val DISTANCE = 3.0

    fun available(session: PlaySession): Boolean =
        session.commands?.hasDirectChild(COMMAND) == true

    fun create(
        entity: ResourceLocation,
        origin: Vec3d,
        rotation: EntityRotation,
        distance: Double = DISTANCE,
        variant: CreatureSpawnVariant = CreatureSpawnVariant.DEFAULT,
    ): String {
        require(distance.isFinite() && distance >= 0.0) { "Spawn distance must be finite and non-negative." }
        val front = rotation.front
        val horizontalLength = sqrt((front.x * front.x + front.z * front.z).toDouble())
        val xDirection = if (horizontalLength > 1.0E-6) front.x / horizontalLength else 0.0
        val zDirection = if (horizontalLength > 1.0E-6) front.z / horizontalLength else 1.0
        val target = Vec3d(
            origin.x + xDirection * distance,
            origin.y,
            origin.z + zDirection * distance,
        )
        val command = String.format(
            Locale.ROOT,
            "/%s %s %.3f %.3f %.3f",
            COMMAND,
            entity,
            cleanZero(target.x),
            cleanZero(target.y),
            cleanZero(target.z),
        )
        return variant.summonNbt?.let { "$command $it" } ?: command
    }

    fun send(
        session: PlaySession,
        entity: ResourceLocation,
        variant: CreatureSpawnVariant = CreatureSpawnVariant.DEFAULT,
    ) {
        check(available(session)) { "The server did not grant the /$COMMAND command." }
        val player = session.player
        session.util.typeChat(create(entity, player.physics.position, player.physics.rotation, variant = variant))
    }

    private fun cleanZero(value: Double): Double = if (value == -0.0 || kotlin.math.abs(value) < 0.0005) 0.0 else value
}
