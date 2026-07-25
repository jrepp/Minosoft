/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.assets.model.skeletal.gecko.runtime

import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeckoLibControllerBindingRegistryTest {
    private val identity = SkeletalContentIdentity(
        ResourceLocation.of("test:geo/registry.geo.json"),
        SkeletalContentFormat.GECKOLIB,
        "geometry.registry_test",
    )

    @Test
    fun `binding ownership is identity scoped and close disables retained bindings`() {
        var seen: GeckoLibControllerBindingContext? = null
        val registration = GeckoLibControllerBindingRegistry.register("test-owner", identity) { context ->
            seen = context
            listOf(GeckoLibControllerDefinition("main"))
        }
        try {
            assertEquals("test-owner", GeckoLibControllerBindingRegistry.owners()[identity])
            assertFailsWith<IllegalArgumentException> {
                GeckoLibControllerBindingRegistry.register("duplicate", identity) {
                    listOf(GeckoLibControllerDefinition("duplicate"))
                }
            }

            val binding = GeckoLibControllerBindingRegistry.bind(identity, emptyMap())
            assertEquals(identity, seen?.identity)
            assertEquals(emptyMap(), seen?.animations)
            assertTrue(requireNotNull(binding).active)

            registration.close()

            assertFalse(binding.active)
            assertNull(GeckoLibControllerBindingRegistry.bind(identity, emptyMap()))
            assertFalse(identity in GeckoLibControllerBindingRegistry.owners())
        } finally {
            registration.close()
        }
    }

    @Test
    fun `non Gecko identities are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            GeckoLibControllerBindingRegistry.register(
                "test-owner",
                identity.copy(format = SkeletalContentFormat.OPTIFINE_CEM),
            ) {
                listOf(GeckoLibControllerDefinition("main"))
            }
        }
    }

    @Test
    fun `controller close is quiescent with an in flight binding callback`() {
        val registration = GeckoLibControllerBindingRegistry.register("test-owner", identity) {
            listOf(GeckoLibControllerDefinition("main"))
        }
        val binding = requireNotNull(GeckoLibControllerBindingRegistry.bind(identity, emptyMap()))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val invocation = thread {
            binding.invoke {
                started.countDown()
                release.await()
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val closer = thread {
            closeStarted.countDown()
            registration.close()
            closed.countDown()
        }
        assertTrue(closeStarted.await(5, TimeUnit.SECONDS))
        assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
        release.countDown()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        invocation.join()
        closer.join()
        assertFalse(binding.active)
    }

    @Test
    fun `entity routes are owner scoped and close restores fallback lookup`() {
        val entity = ResourceLocation.of("test:clockwork_bird")
        val registration = GeckoLibEntityModelRegistry.register("route-owner", entity, identity)
        try {
            assertEquals(identity, GeckoLibEntityModelRegistry.identity(entity))
            assertEquals("route-owner", GeckoLibEntityModelRegistry.owners()[entity])
            assertFailsWith<IllegalArgumentException> {
                GeckoLibEntityModelRegistry.register("duplicate", entity, identity)
            }
        } finally {
            registration.close()
        }

        assertNull(GeckoLibEntityModelRegistry.identity(entity))
        assertFalse(entity in GeckoLibEntityModelRegistry.owners())
    }

    @Test
    fun `model routes isolate entity block item and armor targets`() {
        val identifier = ResourceLocation.of("test:clockwork")
        val entity = GeckoLibEntityModelRegistry.register("entity-owner", identifier, identity)
        val block = GeckoLibBlockEntityModelRegistry.register("block-owner", identifier, identity)
        val item = GeckoLibItemModelRegistry.register("item-owner", identifier, identity)
        val armor = GeckoLibArmorModelRegistry.register("armor-owner", identifier, identity)
        try {
            assertEquals(identity, GeckoLibEntityModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibBlockEntityModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibItemModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibArmorModelRegistry.identity(identifier))
            assertEquals("entity-owner", GeckoLibEntityModelRegistry.owners()[identifier])
            assertEquals("block-owner", GeckoLibBlockEntityModelRegistry.owners()[identifier])
            assertEquals("item-owner", GeckoLibItemModelRegistry.owners()[identifier])
            assertEquals("armor-owner", GeckoLibArmorModelRegistry.owners()[identifier])

            block.close()
            assertNull(GeckoLibBlockEntityModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibEntityModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibItemModelRegistry.identity(identifier))
            assertEquals(identity, GeckoLibArmorModelRegistry.identity(identifier))
        } finally {
            armor.close()
            item.close()
            block.close()
            entity.close()
        }
    }

    @Test
    fun `render layers retain predicates only while their owner is active`() {
        val layer = GeckoLibRenderLayerDefinition(
            name = "glow",
            texture = ResourceLocation.of("test:textures/entity/glow.png"),
            blend = GeckoLibRenderLayerBlend.ADDITIVE,
            fullBright = true,
            predicate = GeckoLibRenderLayerPredicate { it.moving },
        )
        val registration = GeckoLibRenderLayerRegistry.register("layer-owner", identity, listOf(layer))
        var firstRegistrationId = -1L
        try {
            assertEquals("layer-owner", GeckoLibRenderLayerRegistry.owners()[identity])
            assertEquals(listOf(layer), GeckoLibRenderLayerRegistry.snapshot(identity)?.definitions)
            val binding = requireNotNull(GeckoLibRenderLayerRegistry.bind(identity))
            firstRegistrationId = binding.registrationId
            assertTrue(binding.active)
            assertEquals(layer, binding.visible("glow", GeckoLibAnimationState(0.0f, moving = true)))

            registration.close()
            assertFalse(binding.active)
            assertNull(GeckoLibRenderLayerRegistry.snapshot(identity))
        } finally {
            registration.close()
        }

        GeckoLibRenderLayerRegistry.register("replacement-owner", identity, listOf(layer)).use {
            val replacement = requireNotNull(GeckoLibRenderLayerRegistry.bind(identity))
            assertTrue(replacement.registrationId != firstRegistrationId)
        }
    }
}
