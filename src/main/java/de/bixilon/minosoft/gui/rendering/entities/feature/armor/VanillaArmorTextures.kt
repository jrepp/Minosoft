/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This software is not affiliated with Mojang AB, the original developer of
 * Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.feature.armor

import de.bixilon.minosoft.data.container.equipment.EquipmentSlots
import de.bixilon.minosoft.data.container.equipment.ArmorSlots
import de.bixilon.minosoft.data.container.stack.ItemStack
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.item.items.armor.ArmorItem
import de.bixilon.minosoft.data.registries.item.items.dye.DyeableItem
import de.bixilon.minosoft.data.text.formatting.color.RGBColor
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.PalettedTexturePermutationLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture

data class VanillaArmorMaterial(
    val base: Texture,
    val overlay: Texture?,
    val trim: Texture?,
    val tint: RGBColor,
    val enchanted: Boolean,
)

class VanillaArmorTextures(
    renderer: EntitiesRenderer,
) {
    private val direct = linkedMapOf<ResourceLocation, Texture>()
    private val trims = linkedMapOf<TrimKey, Texture>()
    private val static = renderer.context.textures.static

    init {
        for (item in renderer.session.registries.item) {
            if (item !is ArmorItem) continue
            val material = materialName(item.identifier)
            val layer = if (ArmorSlots.LEGS in item.armorSlot) 2 else 1
            declare(armorTexture(item.identifier.namespace, material, layer, overlay = false))
            if (item is DyeableItem) {
                declare(armorTexture(item.identifier.namespace, material, layer, overlay = true))
            }
        }
        declareTrims(renderer)
    }

    fun resolve(stack: ItemStack, slot: EquipmentSlots): VanillaArmorMaterial? {
        if (stack.item !is ArmorItem) return null
        val item = stack.item.identifier
        val material = materialName(item)
        val layer = if (slot == EquipmentSlots.LEGS) 2 else 1
        val base = direct[armorTexture(item.namespace, material, layer, overlay = false)] ?: return null
        val overlay = if (stack.item is DyeableItem) {
            direct[armorTexture(item.namespace, material, layer, overlay = true)]
        } else {
            null
        }
        val tint = if (stack.item is DyeableItem) {
            stack.display.dyeColor ?: DEFAULT_LEATHER_COLOR
        } else {
            WHITE
        }
        return VanillaArmorMaterial(
            base = base,
            overlay = overlay,
            trim = trim(stack, slot, material),
            tint = tint,
            enchanted = stack.enchanting.enchantments.isNotEmpty(),
        )
    }

    private fun trim(stack: ItemStack, slot: EquipmentSlots, armorMaterial: String): Texture? {
        val raw = stack.nbt.nbt["Trim"] as? Map<*, *> ?: return null
        val pattern = raw["pattern"]?.toString()?.resourcePath() ?: return null
        val material = raw["material"]?.toString()?.resourcePath() ?: return null
        val palette = if (material == armorMaterial && material in DARKER_TRIM_MATERIALS) {
            "${material}_darker"
        } else {
            material
        }
        return trims[TrimKey(pattern, palette, slot == EquipmentSlots.LEGS)]
    }

    private fun declare(name: ResourceLocation) {
        direct[name] = static.create(name)
    }

    private fun declareTrims(renderer: EntitiesRenderer) {
        val assets = renderer.session.assets
        val probe = trimTemplate(TRIM_PATTERNS.first(), leggings = false)
        if (probe !in assets) return
        for (pattern in TRIM_PATTERNS) {
            for (palette in TRIM_PALETTES) {
                for (leggings in booleanArrayOf(false, true)) {
                    val key = TrimKey(pattern, palette, leggings)
                    val generated = minecraft(
                        "generated/trims/models/armor/${pattern}_${palette}" +
                            if (leggings) "_leggings" else "",
                    )
                    trims[key] = static.create(
                        generated,
                        loader = PalettedTexturePermutationLoader(
                            trimTemplate(pattern, leggings),
                            TRIM_PALETTE_KEY,
                            trimPalette(palette),
                        ),
                    )
                }
            }
        }
    }

    private data class TrimKey(
        val pattern: String,
        val palette: String,
        val leggings: Boolean,
    )

    companion object {
        private val WHITE = RGBColor(0xFF, 0xFF, 0xFF)
        private val DEFAULT_LEATHER_COLOR = RGBColor(0xA0, 0x65, 0x40)
        private val TRIM_PALETTE_KEY = minecraft("trims/color_palettes/trim_palette").texture()
        private val DARKER_TRIM_MATERIALS = setOf("iron", "gold", "diamond", "netherite")
        private val TRIM_PATTERNS = listOf(
            "coast", "dune", "eye", "host", "raiser", "rib", "sentry", "shaper",
            "silence", "snout", "spire", "tide", "vex", "ward", "wayfinder", "wild",
        )
        private val TRIM_PALETTES = listOf(
            "quartz", "iron", "netherite", "redstone", "copper", "gold", "emerald",
            "diamond", "lapis", "amethyst", "iron_darker", "gold_darker",
            "diamond_darker", "netherite_darker",
        )

        internal fun materialName(identifier: ResourceLocation): String {
            var path = identifier.path.substringAfterLast('/')
            for (suffix in SLOT_SUFFIXES) {
                if (!path.endsWith(suffix)) continue
                path = path.removeSuffix(suffix)
                break
            }
            return if (path == "golden") "gold" else path
        }

        internal fun armorTexture(
            namespace: String,
            material: String,
            layer: Int,
            overlay: Boolean,
        ) = ResourceLocation(
            namespace,
            "models/armor/${material}_layer_$layer${if (overlay) "_overlay" else ""}",
        ).texture()

        private fun trimTemplate(pattern: String, leggings: Boolean) =
            minecraft("trims/models/armor/$pattern${if (leggings) "_leggings" else ""}").texture()

        private fun trimPalette(palette: String) =
            minecraft("trims/color_palettes/$palette").texture()

        private fun String.resourcePath() = substringAfter(':')

        private val SLOT_SUFFIXES = arrayOf("_helmet", "_chestplate", "_leggings", "_boots")
    }
}
