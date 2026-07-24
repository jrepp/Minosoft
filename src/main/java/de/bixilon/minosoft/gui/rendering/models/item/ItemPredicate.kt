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
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.DurableItem

data class ItemPredicate(val thresholds: Map<ResourceLocation, Float>) {
    fun matches(stack: ItemStack): Boolean = thresholds.all { (key, threshold) ->
        value(key, stack) >= threshold
    }

    private fun value(key: ResourceLocation, stack: ItemStack): Float {
        if (key.namespace != "minecraft") return 0.0f
        return when (key.path) {
            "custom_model_data" -> {
                val value = stack.nbt.nbt["CustomModelData"] ?: stack.nbt.nbt["custom_model_data"]
                when (value) {
                    is Number -> value.toFloat()
                    is Map<*, *> -> (value["floats"] as? List<*>)?.firstOrNull().let { it as? Number }?.toFloat() ?: 0.0f
                    else -> 0.0f
                }
            }
            "damage" -> {
                val item = stack.item as? DurableItem ?: return 0.0f
                val durability = stack.durability?.durability ?: item.maxDurability
                if (item.maxDurability <= 0) 0.0f else ((item.maxDurability - durability).toFloat() / item.maxDurability).coerceIn(0.0f, 1.0f)
            }
            "damaged" -> {
                val item = stack.item as? DurableItem ?: return 0.0f
                val durability = stack.durability?.durability ?: item.maxDurability
                if (durability < item.maxDurability && stack.durability?.unbreakable != true) 1.0f else 0.0f
            }
            else -> 0.0f
        }
    }
}
