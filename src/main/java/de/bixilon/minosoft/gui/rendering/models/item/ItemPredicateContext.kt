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

package de.bixilon.minosoft.gui.rendering.models.item

import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.entities.entities.LivingEntity
import de.bixilon.minosoft.data.entities.entities.player.Arms
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.entities.entities.player.local.LocalPlayerEntity
import de.bixilon.minosoft.data.entities.entities.projectile.FishingBobber
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

/**
 * Render-time inputs used by legacy item-model predicates.
 *
 * Stack-only properties remain available with [EMPTY]. Dynamic world or entity
 * properties are explicit so headless callers can select models deterministically.
 * [values] is also the extension boundary for properties whose value is supplied
 * by a content adapter or by a render context not represented here.
 */
data class ItemPredicateContext(
    val active: Boolean = false,
    val useTicks: Int = 0,
    val leftHanded: Boolean = false,
    val cooldownProgress: Float = 0.0f,
    val fishingCast: Boolean = false,
    val entity: Entity? = null,
    val runtime: ItemPredicateRuntime? = null,
    val catalog: ItemPredicateCatalog = ItemPredicateCatalog.V1_20_4,
    val seed: Int = 0,
    val values: Map<ResourceLocation, Float> = emptyMap(),
) {
    init {
        require(useTicks >= 0) { "Item use ticks must not be negative." }
        require(cooldownProgress.isFinite()) { "Item cooldown progress must be finite." }
        require(values.values.all(Float::isFinite)) { "Item predicate values must be finite." }
    }

    companion object {
        val EMPTY = ItemPredicateContext()

        fun of(
            entity: Entity?,
            stack: ItemStack,
            runtime: ItemPredicateRuntime? = null,
            seed: Int = entity?.id ?: 0,
        ): ItemPredicateContext {
            val living = entity as? LivingEntity
            val activeStack = living?.usingHand?.let { living.equipment[it.slot] }
            val local = entity as? LocalPlayerEntity
            val cooldown = local?.items?.cooldown?.get(stack.item)
            val cooldownProgress = if (cooldown == null || cooldown.ended) {
                0.0f
            } else {
                val elapsed = now() - cooldown.start
                (1.0 - elapsed / cooldown.ticks.duration).toFloat().coerceIn(0.0f, 1.0f)
            }
            val fishingCast = entity != null && stack.item.identifier.toString() == FISHING_ROD && entity.session.world.entities.any {
                it is FishingBobber && it.owner === entity
            }
            return ItemPredicateContext(
                active = activeStack === stack,
                useTicks = when {
                    activeStack !== stack -> 0
                    local != null -> local.using?.tick ?: 0
                    else -> living.itemUseTicks
                },
                leftHanded = (entity as? PlayerEntity)?.mainArm == Arms.LEFT,
                cooldownProgress = cooldownProgress,
                fishingCast = fishingCast,
                entity = entity,
                runtime = runtime,
                catalog = entity?.session?.version?.versionId
                    ?.let(ItemPredicateCatalog::forVersion)
                    ?: ItemPredicateCatalog.V1_20_4,
                seed = seed,
            )
        }

        private const val FISHING_ROD = "minecraft:fishing_rod"
    }
}
