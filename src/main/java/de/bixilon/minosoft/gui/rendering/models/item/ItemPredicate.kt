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

import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.entities.entities.player.PlayerEntity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.DurableItem
import de.bixilon.minosoft.data.registries.item.items.armor.ArmorItem
import de.bixilon.minosoft.data.registries.item.stack.StackableItem
import de.bixilon.minosoft.data.world.positions.BlockPosition
import de.bixilon.minosoft.tags.item.MinecraftItemTags.isIn
import java.lang.Math.floorMod

data class ItemPredicate(val thresholds: Map<ResourceLocation, Float>) {
    fun matches(stack: ItemStack, context: ItemPredicateContext = ItemPredicateContext.EMPTY): Boolean = thresholds.all { (key, threshold) ->
        (value(key, stack, context) ?: Float.NEGATIVE_INFINITY) >= threshold
    }

    private fun value(key: ResourceLocation, stack: ItemStack, context: ItemPredicateContext): Float? {
        context.values[key]?.let { return it }
        if (key.namespace != "minecraft") return null
        return when (key.path) {
            "custom_model_data" -> stack.customModelData()
            "damage" -> stack.damage()
            "damaged" -> stack.damaged()
            "lefthanded" -> context.leftHanded.flag()
            "cooldown" -> context.cooldownProgress.coerceIn(0.0f, 1.0f)
            "pull" -> stack.pull(context)
            "pulling" -> stack.pulling(context)
            "filled" -> stack.itemProperty(BUNDLE) { stack.bundleFilled(context) }
            "time" -> stack.itemProperty(CLOCK) { stack.clock(context) }
            "angle" -> stack.compassAngle(context)
            "brushing" -> stack.itemProperty(context, BRUSH) {
                floorMod(BRUSH_MAX_USE_TICKS - context.useTicks, BRUSH_FRAME_TICKS).toFloat() / BRUSH_FRAME_TICKS
            }
            "charged" -> stack.itemProperty(CROSSBOW) { stack.nbt.nbt["Charged"].boolean().flag() }
            "firework" -> stack.itemProperty(CROSSBOW) { stack.hasChargedFirework().flag() }
            "broken" -> stack.itemProperty(ELYTRA) {
                val item = stack.item as? DurableItem ?: return@itemProperty 0.0f
                val durability = stack.durability?.durability ?: item.maxDurability
                (durability <= ELYTRA_MIN_USABLE_DURABILITY).flag()
            }
            "cast" -> stack.itemProperty(FISHING_ROD) { context.fishingCast.flag() }
            "blocking" -> stack.itemProperty(context, SHIELD) { context.active.flag() }
            "throwing" -> stack.itemProperty(context, TRIDENT) { context.active.flag() }
            "tooting" -> stack.itemProperty(context, GOAT_HORN) { context.active.flag() }
            "level" -> stack.itemProperty(LIGHT) { stack.lightLevel() }
            "trim_type" -> stack.trimType(context)
            else -> null
        }
    }

    private fun ItemStack.customModelData(): Float {
        val value = nbt.nbt["CustomModelData"] ?: nbt.nbt["custom_model_data"]
        return when (value) {
            is Number -> value.toFloat()
            is Map<*, *> -> (value["floats"] as? List<*>)?.firstOrNull().let { it as? Number }?.toFloat() ?: 0.0f
            else -> 0.0f
        }
    }

    private fun ItemStack.damage(): Float? {
        val item = item as? DurableItem ?: return null
        val durability = durability?.durability ?: item.maxDurability
        return if (item.maxDurability <= 0) 0.0f else {
            ((item.maxDurability - durability).toFloat() / item.maxDurability).coerceIn(0.0f, 1.0f)
        }
    }

    private fun ItemStack.damaged(): Float? {
        val item = item as? DurableItem ?: return null
        val durability = durability?.durability ?: item.maxDurability
        return (durability < item.maxDurability && this.durability?.unbreakable != true).flag()
    }

    private fun ItemStack.pull(context: ItemPredicateContext): Float? {
        if (!context.active) return itemProperty(BOW, CROSSBOW) { 0.0f }
        return when (item.identifier.toString()) {
            BOW -> context.useTicks / BOW_PULL_TICKS.toFloat()
            CROSSBOW -> {
                if (nbt.nbt["Charged"].boolean()) return 0.0f
                val quickCharge = enchanting.enchantments.entries.firstOrNull {
                    it.key.identifier.toString() == QUICK_CHARGE
                }?.value ?: 0
                context.useTicks / (CROSSBOW_PULL_TICKS - CROSSBOW_QUICK_CHARGE_REDUCTION * quickCharge).coerceAtLeast(1).toFloat()
            }
            else -> null
        }
    }

    private fun ItemStack.pulling(context: ItemPredicateContext): Float? = when (item.identifier.toString()) {
        BOW -> context.active.flag()
        CROSSBOW -> (context.active && !nbt.nbt["Charged"].boolean()).flag()
        else -> null
    }

    private inline fun ItemStack.itemProperty(vararg identifiers: String, value: () -> Float): Float? {
        if (item.identifier.toString() !in identifiers) return null
        return value()
    }

    private inline fun ItemStack.itemProperty(context: ItemPredicateContext, vararg identifiers: String, value: () -> Float): Float? {
        if (item.identifier.toString() !in identifiers) return null
        if (!context.active) return 0.0f
        return value()
    }

    private fun ItemStack.hasChargedFirework(): Boolean {
        if (!nbt.nbt["Charged"].boolean()) return false
        val projectiles = nbt.nbt["ChargedProjectiles"] as? List<*> ?: return false
        return projectiles.any { projectile ->
            val compound = projectile as? Map<*, *> ?: return@any false
            compound["id"]?.toString() == FIREWORK_ROCKET
        }
    }

    private fun ItemStack.lightLevel(): Float {
        val state = nbt.nbt["BlockStateTag"] as? Map<*, *> ?: return 1.0f
        val level = state["level"]?.toString()?.toIntOrNull() ?: return 1.0f
        return level / 16.0f
    }

    private fun ItemStack.bundleFilled(context: ItemPredicateContext): Float {
        val items = nbt.nbt["Items"] as? List<*> ?: return 0.0f
        val occupancy = items.sumOf { entry ->
            val item = entry as? Map<*, *> ?: return@sumOf 0
            bundleEntryOccupancy(item, context, depth = 0)
        }
        return (occupancy / BUNDLE_CAPACITY.toFloat()).coerceIn(0.0f, 1.0f)
    }

    private fun bundleEntryOccupancy(
        compound: Map<*, *>,
        context: ItemPredicateContext,
        depth: Int,
    ): Int {
        if (depth >= MAX_BUNDLE_DEPTH) return BUNDLE_CAPACITY
        val count = compound["Count"].intValue().coerceAtLeast(0)
        if (count == 0) return 0
        val identifier = compound["id"]?.toString()?.resourceLocation() ?: return 0
        val perItem = when (identifier.toString()) {
            BUNDLE -> {
                val tag = compound["tag"] as? Map<*, *>
                val nested = tag?.get("Items") as? List<*>
                BUNDLE_NESTING_COST + (nested?.sumOf { entry ->
                    bundleEntryOccupancy(entry as? Map<*, *> ?: return@sumOf 0, context, depth + 1)
                } ?: 0)
            }
            BEEHIVE, BEE_NEST -> {
                val tag = compound["tag"] as? Map<*, *>
                val blockEntity = tag?.get("BlockEntityTag") as? Map<*, *>
                val bees = blockEntity?.get("Bees") as? List<*>
                if (bees.isNullOrEmpty()) normalBundleOccupancy(identifier, context) else BUNDLE_CAPACITY
            }
            else -> normalBundleOccupancy(identifier, context)
        }
        return perItem * count
    }

    private fun normalBundleOccupancy(identifier: ResourceLocation, context: ItemPredicateContext): Int {
        val item = context.entity?.session?.registries?.item?.get(identifier)
        val maxCount = (item as? StackableItem)?.maxStackSize ?: if (item == null) BUNDLE_CAPACITY else 1
        return BUNDLE_CAPACITY / maxCount.coerceIn(1, BUNDLE_CAPACITY)
    }

    private fun ItemStack.clock(context: ItemPredicateContext): Float {
        val entity = context.entity ?: return 0.0f
        val world = entity.session.world
        val runtime = context.runtime ?: return 0.0f
        return runtime.clock(world.time.age, world.time.time, world.dimension.natural)
    }

    private fun ItemStack.compassAngle(context: ItemPredicateContext): Float? {
        val identifier = item.identifier
        if (identifier.toString() != COMPASS && identifier.toString() != RECOVERY_COMPASS) return null
        val entity = context.entity ?: return 0.0f
        val runtime = context.runtime ?: return 0.0f
        val target = if (identifier.toString() == RECOVERY_COMPASS) {
            val death = (entity as? PlayerEntity)?.lastDeathPosition
            CompassTarget(death?.position, death?.dimension?.identifier)
        } else {
            lodestoneTarget() ?: CompassTarget(
                entity.session.player.compass.position,
                entity.session.world.name,
            )
        }
        return runtime.compass(
            provider = identifier,
            entity = entity,
            target = target.position,
            targetDimension = target.dimension,
            worldAge = entity.session.world.time.age,
            seed = context.seed,
        )
    }

    private fun ItemStack.lodestoneTarget(): CompassTarget? {
        val position = nbt.nbt["LodestonePos"] as? Map<*, *> ?: return null
        val dimension = nbt.nbt["LodestoneDimension"]?.toString()?.resourceLocation() ?: return null
        return CompassTarget(
            position = BlockPosition(
                position["X"].intValue(),
                position["Y"].intValue(),
                position["Z"].intValue(),
            ),
            dimension = dimension,
        )
    }

    private fun ItemStack.trimType(context: ItemPredicateContext): Float? {
        val session = context.entity?.session
        val trimmable = item is ArmorItem || (session != null && item.isIn(session.tags, TRIMMABLE_ARMOR))
        if (!trimmable) return null
        if (session == null) return 0.0f
        val trim = nbt.nbt["Trim"] as? Map<*, *> ?: return 0.0f
        val material = trim["material"]?.toString()?.resourceLocation()?.path ?: return 0.0f
        return TRIM_MATERIAL_INDEX[material] ?: 0.0f
    }

    private fun Any?.intValue(): Int = when (this) {
        is Number -> toInt()
        is String -> toIntOrNull() ?: 0
        else -> 0
    }

    private fun String.resourceLocation(): ResourceLocation? = runCatching { ResourceLocation.of(this) }.getOrNull()

    private fun Any?.boolean(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", ignoreCase = true) || toIntOrNull()?.let { it != 0 } == true
        else -> false
    }

    private fun Boolean.flag(): Float = if (this) 1.0f else 0.0f

    private data class CompassTarget(
        val position: BlockPosition?,
        val dimension: ResourceLocation?,
    )

    private companion object {
        private const val BOW = "minecraft:bow"
        private const val BRUSH = "minecraft:brush"
        private const val BUNDLE = "minecraft:bundle"
        private const val CLOCK = "minecraft:clock"
        private const val COMPASS = "minecraft:compass"
        private const val CROSSBOW = "minecraft:crossbow"
        private const val ELYTRA = "minecraft:elytra"
        private const val BEEHIVE = "minecraft:beehive"
        private const val BEE_NEST = "minecraft:bee_nest"
        private const val FIREWORK_ROCKET = "minecraft:firework_rocket"
        private const val FISHING_ROD = "minecraft:fishing_rod"
        private const val GOAT_HORN = "minecraft:goat_horn"
        private const val LIGHT = "minecraft:light"
        private const val RECOVERY_COMPASS = "minecraft:recovery_compass"
        private const val SHIELD = "minecraft:shield"
        private const val TRIDENT = "minecraft:trident"
        private const val QUICK_CHARGE = "minecraft:quick_charge"
        private val TRIMMABLE_ARMOR = ResourceLocation.of("minecraft:trimmable_armor")
        private val TRIM_MATERIAL_INDEX = mapOf(
            "quartz" to 0.1f,
            "iron" to 0.2f,
            "netherite" to 0.3f,
            "redstone" to 0.4f,
            "copper" to 0.5f,
            "gold" to 0.6f,
            "emerald" to 0.7f,
            "diamond" to 0.8f,
            "lapis" to 0.9f,
            "amethyst" to 1.0f,
        )
        private const val BOW_PULL_TICKS = 20
        private const val BRUSH_MAX_USE_TICKS = 200
        private const val BRUSH_FRAME_TICKS = 10
        private const val BUNDLE_CAPACITY = 64
        private const val BUNDLE_NESTING_COST = 4
        private const val MAX_BUNDLE_DEPTH = 16
        private const val CROSSBOW_PULL_TICKS = 25
        private const val CROSSBOW_QUICK_CHARGE_REDUCTION = 5
        private const val ELYTRA_MIN_USABLE_DURABILITY = 1
    }
}
