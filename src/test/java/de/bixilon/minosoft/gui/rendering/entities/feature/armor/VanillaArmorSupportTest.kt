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
import de.bixilon.minosoft.data.entities.entities.player.SkinParts
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanillaArmorSupportTest {

    @Test
    fun `armor item identifiers resolve vanilla material names`() {
        assertEquals("iron", VanillaArmorTextures.materialName(ResourceLocation.of("minecraft:iron_helmet")))
        assertEquals("gold", VanillaArmorTextures.materialName(ResourceLocation.of("minecraft:golden_chestplate")))
        assertEquals("turtle", VanillaArmorTextures.materialName(ResourceLocation.of("minecraft:turtle_helmet")))
    }

    @Test
    fun `armor texture paths distinguish base overlay and leggings`() {
        assertEquals(
            ResourceLocation.of("minecraft:textures/models/armor/leather_layer_1.png"),
            VanillaArmorTextures.armorTexture("minecraft", "leather", 1, overlay = false),
        )
        assertEquals(
            ResourceLocation.of("minecraft:textures/models/armor/leather_layer_2_overlay.png"),
            VanillaArmorTextures.armorTexture("minecraft", "leather", 2, overlay = true),
        )
    }

    @Test
    fun `equipment slots select only matching armor mesh parts`() {
        assertEquals(SkinParts.HAT.bitmask, VanillaArmorFeature.parts(EquipmentSlots.HEAD))
        assertEquals(
            SkinParts.JACKET.bitmask or SkinParts.LEFT_SLEEVE.bitmask or SkinParts.RIGHT_SLEEVE.bitmask,
            VanillaArmorFeature.parts(EquipmentSlots.CHEST),
        )
        assertEquals(
            SkinParts.JACKET.bitmask or SkinParts.LEFT_PANTS.bitmask or SkinParts.RIGHT_PANTS.bitmask,
            VanillaArmorFeature.parts(EquipmentSlots.LEGS),
        )
        assertEquals(
            SkinParts.LEFT_PANTS.bitmask or SkinParts.RIGHT_PANTS.bitmask,
            VanillaArmorFeature.parts(EquipmentSlots.FEET),
        )
    }

    @Test
    fun `retained armor gets one cleanup update after equipment removal`() {
        assertTrue(VanillaArmorFeature.retainsForSync(hasArmor = true, entryCount = 0))
        assertTrue(VanillaArmorFeature.retainsForSync(hasArmor = false, entryCount = 4))
        assertFalse(VanillaArmorFeature.retainsForSync(hasArmor = false, entryCount = 0))
    }
}
