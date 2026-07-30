/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.skeletal

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateInput
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateQuery
import de.bixilon.minosoft.data.abilities.Gamemodes
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.shapes.aabb.AABB
import kotlin.random.Random

/**
 * Resolves the immutable host queries declared by a retained Gecko controller.
 * World scans occur only for explicitly requested inputs and use the shared
 * entity collection's read lock through [getInRadius].
 */
object GeckoLibEntityHostStateResolver {
    fun resolve(
        entity: Entity,
        input: GeckoLibHostStateInput,
        random: Random,
    ): Double = when (val query = input.query) {
        is GeckoLibHostStateQuery.RandomInteger -> random.nextInt(query.bound).toDouble()
        is GeckoLibHostStateQuery.EntityType ->
            if (entity.type.identifier == query.identifier) 1.0 else 0.0
        is GeckoLibHostStateQuery.NearbyPlayer ->
            if (hasNearbyPlayer(entity, query)) 1.0 else 0.0
    }

    private fun hasNearbyPlayer(
        entity: Entity,
        query: GeckoLibHostStateQuery.NearbyPlayer,
    ): Boolean {
        val aabb = entity.physics.aabb ?: return false
        val expansion = Vec3d(query.horizontalExpansion, query.verticalExpansion, query.horizontalExpansion)
        val bounds = AABB(aabb.min - expansion, aabb.max + expansion)
        return entity.session.world.entities.getInRadius(entity.physics.position, query.range) { candidate ->
            candidate !== entity &&
                candidate is PlayerEntity &&
                candidate.health > 0.0 &&
                candidate.gamemode != Gamemodes.SPECTATOR &&
                candidate.physics.aabb?.intersects(bounds) == true
        }.isNotEmpty()
    }
}
