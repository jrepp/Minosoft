/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.models.loader

import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.cast.CastUtil.unsafeCast
import de.bixilon.kutil.latch.SimpleLatch
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.kutil.reflection.ReflectionUtil.getFieldOrNull
import de.bixilon.kutil.stream.InputStreamUtil.readAll
import de.bixilon.minosoft.assets.MemoryAssetsManager
import de.bixilon.minosoft.assets.model.generation.ContentFidelityLoader
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentParsers
import de.bixilon.minosoft.assets.model.skeletal.cem.CEM_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.gecko.GECKO_PARSER_REGISTRATION
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibArmorModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibBlockEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibItemModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerRegistry
import de.bixilon.minosoft.assets.session.SessionDataPackManager
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferManager
import de.bixilon.minosoft.gui.rendering.framebuffer.world.MainWorldTarget
import de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoader.Companion.sModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.system.dummy.DummyRenderSystem
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTexture
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureManager
import de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyTextureLoader
import de.bixilon.minosoft.gui.rendering.renderer.renderer.RendererManager
import de.bixilon.minosoft.protocol.network.session.play.SessionTestUtil
import de.bixilon.minosoft.test.ITUtil.allocate
import org.testng.Assert.assertEquals
import org.testng.Assert.assertNotSame
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertThrows
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import kotlin.math.cos

@Test(groups = ["skeletal"])
class SkeletalLoaderTest {
    private val dummyModel = minosoft("model/dummy").sModel()


    private fun createContext(): RenderContext {
        val context = RenderContext::class.java.allocate()
        context::session.forceSet(SessionTestUtil.createSession())
        context::profile.forceSet(context.session.profiles.rendering)
        context::system.forceSet(DummyRenderSystem(context))
        context::textures.forceSet(DummyTextureManager(context))

        val manager = MemoryAssetsManager()
        manager.push(dummyModel, SkeletalLoaderTest::class.java.getResourceAsStream("/model/skeletal/dummy.smodel")!!.readAll())
        context.session.assets.add(manager)


        return context
    }

    private fun createLoader(): SkeletalLoader {
        val context = createContext()
        val loader = ModelLoader(context)

        return loader.skeletal
    }

    private fun SkeletalLoader.loadDummyModel() {
        register(dummyModel, override = mapOf(minosoft("dummy_texture") to DummyTexture(), minosoft("second_texture") to DummyTexture()))
        load(SimpleLatch(0))
    }

    private fun SkeletalLoader.getRegisteredModel(name: ResourceLocation): SkeletalModel {
        val map = SkeletalLoader::class.java.getFieldOrNull("registered")!!.get(this).unsafeCast<Map<ResourceLocation, Any>>()
        val registered = map[name] ?: throw IllegalArgumentException("Can not find model!")
        return registered::class.java.getFieldOrNull("model")!!.get(registered).unsafeCast()
    }

    fun `load dummy model from file`() {
        val loader = createLoader()
        loader.loadDummyModel()
        val raw = loader.getRegisteredModel(dummyModel)

        assertEquals(raw.elements.size, 1)
        assertEquals(raw.elements["body"]!!.children["head1"]!!.transform, "head")
    }

    fun `adapted Gecko route and render layer bake through stable content identity without upstream code`() {
        val parser = SkeletalContentParsers.register(GECKO_PARSER_REGISTRATION)
        val context = createContext()
        val session = context.session
        val dataPacks = SessionDataPackManager()
        session::dataPacks.forceSet(dataPacks)
        val source = ResourceLocation.of("test:geo/zombie.geo.json")
        val assets = MemoryAssetsManager()
        assets.push(
            source,
            requireNotNull(
                SkeletalLoaderTest::class.java.getResourceAsStream(
                    "/content_fidelity/multi-version/assets/test/geo/zombie.geo.json",
                ),
            ).readAll(),
        )
        val textureBytes = requireNotNull(
            SkeletalLoaderTest::class.java.getResourceAsStream(
                "/skins/7af7c07d1ded61b1d3312685b32e4568ffdda762ec8d808895cc329a93d606e0.png",
            ),
        ).readAll()
        val layerTexture = ResourceLocation.of("test:textures/entity/zombie_glow.png")
        assets.push(ResourceLocation.of("test:textures/__content/geometry.zombie.png"), textureBytes)
        assets.push(layerTexture, textureBytes)
        session.assets.add(assets)
        val identity = SkeletalContentIdentity(source, SkeletalContentFormat.GECKOLIB, "geometry.zombie")
        val entity = ResourceLocation.of("test:clockwork_zombie")
        val route = GeckoLibEntityModelRegistry.register("loader-test", entity, identity)
        val blockRoute = GeckoLibBlockEntityModelRegistry.register("loader-test", entity, identity)
        val itemRoute = GeckoLibItemModelRegistry.register("loader-test", entity, identity)
        val armorRoute = GeckoLibArmorModelRegistry.register("loader-test", entity, identity)
        val renderLayer = GeckoLibRenderLayerRegistry.register(
            "loader-test",
            identity,
            listOf(
                GeckoLibRenderLayerDefinition(
                    name = "glow",
                    texture = layerTexture,
                    blend = GeckoLibRenderLayerBlend.ADDITIVE,
                    fullBright = true,
                ),
            ),
        )
        try {
            session.contentFidelity.reload {
                ContentFidelityLoader(session.assets, dataPacks).prepare()
            }
            val loader = ModelLoader(context)
            loader.skeletal.registerContentFidelity()

            assertEquals(
                loader.skeletal.contentModel(entity),
                ResourceLocation.of("minosoft:content/test/geo/zombie.geo.json/geometry.zombie.smodel"),
            )
            assertEquals(
                loader.skeletal.contentModel(GeckoLibModelTarget.BLOCK_ENTITY, entity),
                ResourceLocation.of("minosoft:content/test/geo/zombie.geo.json/geometry.zombie.smodel"),
            )
            assertEquals(
                loader.skeletal.contentModel(GeckoLibModelTarget.ITEM, entity),
                ResourceLocation.of("minosoft:content/test/geo/zombie.geo.json/geometry.zombie.smodel"),
            )
            assertEquals(
                loader.skeletal.contentModel(GeckoLibModelTarget.ARMOR, entity),
                ResourceLocation.of("minosoft:content/test/geo/zombie.geo.json/geometry.zombie.smodel"),
            )
            loader.skeletal.load(SimpleLatch(0))
            context.textures.static.load(SimpleLatch(0))
            context.textures.static.upload(SimpleLatch(0))
            loader.skeletal.bake(SimpleLatch(0))
            val baked = requireNotNull(
                loader.skeletal[ResourceLocation.of("minosoft:content/test/geo/zombie.geo.json/geometry.zombie.smodel")],
            )
            assertEquals(baked.geckoRenderLayers.getValue("glow").blend, GeckoLibRenderLayerBlend.ADDITIVE)
            assertEquals(baked.geckoRenderLayers.getValue("glow").fullBright, true)

            route.close()
            assertEquals(loader.skeletal.contentModel(entity), null)
            blockRoute.close()
            assertEquals(loader.skeletal.contentModel(GeckoLibModelTarget.BLOCK_ENTITY, entity), null)
            loader.skeletal.unload()
        } finally {
            renderLayer.close()
            armorRoute.close()
            itemRoute.close()
            blockRoute.close()
            route.close()
            session.contentFidelity.close()
            parser.close()
        }
    }

    fun `bake dummy model`() {
        val loader = createLoader()
        loader.loadDummyModel()
        loader.bake(SimpleLatch(0))
        val baked = loader[dummyModel]!!


        assertEquals(baked.transform.children, mapOf("body" to BakedSkeletalTransform(1, Vec3f(0.0f, 0.5f, 0.0f), mapOf("head" to BakedSkeletalTransform(2, Vec3f(0.0f, 1.0f, 0.0f), emptyMap())))))
    }

    fun `content reload swaps uploaded models adds textures and rejects missing assets`() {
        val parser = SkeletalContentParsers.register(CEM_PARSER_REGISTRATION)
        val context = createContext()
        val session = context.session
        val dataPacks = SessionDataPackManager()
        session::dataPacks.forceSet(dataPacks)
        context::thread.forceSet(Thread.currentThread())
        val framebuffer = FramebufferManager::class.java.allocate()
        framebuffer::main.forceSet(MainWorldTarget::class.java.allocate())
        context::framebuffer.forceSet(framebuffer)
        context::renderer.forceSet(RendererManager(context))
        val modelLoader = ModelLoader(context)
        context::models.forceSet(modelLoader)
        val source = ResourceLocation.of("test:optifine/cem/zombie.jem")
        val texture = ResourceLocation.of("test:textures/__content/zombie.png")
        val assets = MemoryAssetsManager()
        session.assets.add(assets)
        val textureBytes = SkeletalLoaderTest::class.java.getResourceAsStream(
            "/skins/7af7c07d1ded61b1d3312685b32e4568ffdda762ec8d808895cc329a93d606e0.png",
        )!!.readAll()
        assets.push(texture, textureBytes)
        assets.push(source, cem(4))

        try {
            session.contentFidelity.reload {
                ContentFidelityLoader(session.assets, dataPacks).prepare()
            }
            modelLoader.skeletal.registerContentFidelity()
            val entity = session.contentFidelity.lease()!!.use { lease ->
                val content = lease.value.skeletal.values.flatten().single()
                ResourceLocation(content.source.namespace, content.source.path.substringAfterLast('/').substringBeforeLast('.'))
            }
            val modelName = requireNotNull(modelLoader.skeletal.contentModel(entity))
            modelLoader.skeletal.load(SimpleLatch(0))
            context.textures.static.load(SimpleLatch(0))
            context.textures.static.upload(SimpleLatch(0))
            modelLoader.skeletal.bake(SimpleLatch(0))
            modelLoader.skeletal.upload()
            val staticTextures =
                context.textures.static as de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyStaticTextureArray
            assertEquals(staticTextures.liveTextureSlots, 1)
            assertEquals(staticTextures.managedTextureSlots, 1)

            val first = modelLoader.skeletal[modelName]!!
            val retained = first.createInstance(context)
            retained.load()
            assertCemAbsolutePartRead(retained)

            assets.push(source, cem(6))
            assertEquals(modelLoader.skeletal.reloadContentFidelity(), 2L)
            val second = modelLoader.skeletal[modelName]!!
            assertNotSame(second, first)
            assertEquals(modelLoader.skeletal.contentModel(entity), modelName)
            assertThrows(IllegalStateException::class.java) { first.createInstance(context) }
            assertEquals(staticTextures.liveTextureSlots, 2)
            second.createInstance(context).also {
                assertCemAbsolutePartRead(it)
                it.drop()
            }

            val newTexture = ResourceLocation.of("test:textures/entity/new.png")
            assets.push(source, cem(8, texture = "textures/entity/new.png"))
            assertThrows(IllegalArgumentException::class.java) {
                modelLoader.skeletal.reloadContentFidelity()
            }
            assertEquals(session.contentFidelity.generationId, 2L)
            assertSame(modelLoader.skeletal[modelName], second)

            assets.push(
                newTexture,
                SkeletalLoaderTest::class.java.getResourceAsStream(
                    "/skins/7af7c07d1ded61b1d3312685b32e4568ffdda762ec8d808895cc329a93d606e0.png",
                )!!.readAll(),
            )
            assertEquals(modelLoader.skeletal.reloadContentFidelity(), 3L)
            val third = modelLoader.skeletal[modelName]!!
            assertNotSame(third, second)
            assertNotNull(context.textures.static[newTexture])

            retained.unload()
            context.textures.static.beginUpdate().close()
            assertEquals(staticTextures.liveTextureSlots, 1)
            assertEquals(staticTextures.managedTextureSlots, 1)
            modelLoader.skeletal.unload()
            session.contentFidelity.close()
            context.textures.static.beginUpdate().close()
            assertEquals(staticTextures.liveTextureSlots, 0)
            assertEquals(staticTextures.managedTextureSlots, 0)
        } finally {
            session.contentFidelity.close()
            parser.close()
        }
    }

    fun `static texture update rolls published names back and commits replacements`() {
        val context = createContext()
        val array = context.textures.static
        val dummy = array as de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyStaticTextureArray
        val originalName = ResourceLocation.of("test:textures/original.png")
        val addedName = ResourceLocation.of("test:textures/added.png")
        val original = array.create(originalName, loader = DummyTextureLoader)
        array.load(SimpleLatch(0))
        array.upload(SimpleLatch(0))
        val originalGeneration = array.retainGeneration(listOf(original))

        array.beginUpdate().use { update ->
            val replacement = update.resolve(originalName, loader = DummyTextureLoader)
            update.resolve(addedName, loader = DummyTextureLoader)
            update.upload()
            update.publish()
            assertSame(array[originalName], replacement)
            assertEquals(array[addedName] != null, true)
        }
        assertSame(array[originalName], original)
        assertEquals(array[addedName], null)
        assertEquals(dummy.liveUpdateResources, 0)
        assertEquals(dummy.rolledBackUpdates, 1)

        lateinit var generation: AutoCloseable
        val replacement = array.beginUpdate().let { update ->
            val candidate = update.resolve(originalName, loader = DummyTextureLoader)
            update.resolve(addedName, loader = DummyTextureLoader)
            update.upload()
            update.publish()
            generation = update.complete()
            candidate
        }
        assertSame(array[originalName], replacement)
        assertEquals(array[addedName] != null, true)
        assertEquals(dummy.liveUpdateResources, 0)
        assertEquals(dummy.completedUpdates, 1)
        originalGeneration.close()
        generation.close()
        array.beginUpdate().close()
        assertEquals(array[originalName], null)
        assertEquals(array[addedName], null)
        assertEquals(dummy.managedTextureSlots, 0)
    }

    fun `repeated static texture generations keep transaction and slot counts bounded`() {
        val context = createContext()
        val array = context.textures.static
        val dummy = array as de.bixilon.minosoft.gui.rendering.system.dummy.texture.DummyStaticTextureArray
        val name = ResourceLocation.of("test:textures/reloaded.png")
        array.load(SimpleLatch(0))
        array.upload(SimpleLatch(0))

        var previous: AutoCloseable? = null
        repeat(32) {
            val current = array.beginUpdate().let { update ->
                update.resolve(name, loader = DummyTextureLoader)
                update.upload()
                update.publish()
                update.complete()
            }
            previous?.close()
            previous = current

            assertEquals(0, dummy.liveUpdateResources)
            assertEquals(1, dummy.liveTextureSlots)
            assertTrue(dummy.managedTextureSlots <= 2)
            assertTrue(dummy.freeTextureSlots <= 1)
        }

        previous?.close()
        array.beginUpdate().close()
        assertEquals(0, dummy.liveTextureSlots)
        assertEquals(0, dummy.freeTextureSlots)
        assertEquals(0, dummy.managedTextureSlots)
        assertEquals(32, dummy.completedUpdates)
    }

    fun `native skeletal feature materials select independent ETF layers`() {
        val context = createContext()
        val session = context.session
        session::dataPacks.forceSet(SessionDataPackManager())
        val modelLoader = ModelLoader(context)
        context::models.forceSet(modelLoader)
        context::thread.forceSet(Thread.currentThread())
        val framebuffer = FramebufferManager::class.java.allocate()
        framebuffer::main.forceSet(MainWorldTarget::class.java.allocate())
        context::framebuffer.forceSet(framebuffer)
        context::renderer.forceSet(RendererManager(context))
        val assets = MemoryAssetsManager()
        session.assets.add(assets)
        val png = SkeletalLoaderTest::class.java.getResourceAsStream(
            "/skins/7af7c07d1ded61b1d3312685b32e4568ffdda762ec8d808895cc329a93d606e0.png",
        )!!.readAll()
        val pig = ResourceLocation.of("minecraft:textures/entity/pig/pig.png")
        val pigEmissive = ResourceLocation.of("minecraft:textures/entity/pig/pig_e.png")
        val saddle = ResourceLocation.of("minecraft:textures/entity/pig/pig_saddle.png")
        val saddleEmissive = ResourceLocation.of("minecraft:textures/entity/pig/pig_saddle_e.png")
        val saddledModel = minecraft("entities/pig/saddled").sModel()
        listOf(pig, pigEmissive, saddle, saddleEmissive).forEach { assets.push(it, png) }
        assets.push(
            saddledModel,
            SkeletalLoaderTest::class.java.getResourceAsStream("/assets/minecraft/models/entities/pig/saddled.smodel")!!.readAll(),
        )

        session.contentFidelity.reload {
            ContentFidelityLoader(session.assets).prepare()
        }
        try {
            modelLoader.skeletal.registerContentFidelity()
            modelLoader.skeletal.register(saddledModel)
            modelLoader.skeletal.load(SimpleLatch(0))
            context.textures.static.load(SimpleLatch(0))
            context.textures.static.upload(SimpleLatch(0))
            modelLoader.skeletal.bake(SimpleLatch(0))
            modelLoader.skeletal.upload()

            val baked = modelLoader.skeletal[saddledModel]!!
            assertEquals(baked.entityTextureBase, null)
            assertEquals(baked.entityTextureLayers.keys, setOf(pig, saddle))
            assertEquals(baked.entityTextureLayers.getValue(pig).meshes.keys, setOf(pig, pigEmissive))
            assertEquals(baked.entityTextureLayers.getValue(saddle).meshes.keys, setOf(saddle, saddleEmissive))
            assertNotNull(baked.contentLease)

            val retained = baked.createInstance(context)
            retained.load()
            val pigBlink = ResourceLocation.of("minecraft:textures/entity/pig/pig_blink.png")
            assets.push(pigBlink, png)
            assertEquals(modelLoader.skeletal.reloadContentFidelity(), 2L)
            val reloaded = modelLoader.skeletal[saddledModel]!!
            assertNotSame(reloaded, baked)
            assertEquals(reloaded.entityTextureLayers.getValue(pig).meshes.keys, setOf(pig, pigEmissive, pigBlink))
            assertEquals(reloaded.entityTextureLayers.getValue(saddle).meshes.keys, setOf(saddle, saddleEmissive))
            assertThrows(IllegalStateException::class.java) { baked.createInstance(context) }
            retained.unload()
            modelLoader.skeletal.unload()
        } finally {
            session.contentFidelity.close()
        }
    }

    private fun cem(size: Int, texture: String? = null): String {
        val rootTexture = texture?.let { ",\"texture\":\"$it\"" }.orEmpty()
        return """{"textureSize":[64,64]$rootTexture,"models":[{"id":"body","part":"body","translate":[4,0,0],"boxes":[{"coordinates":[0,0,0,$size,$size,$size],"textureOffset":[0,0]}],"animations":[{"this.ry":"this.tx / 16"}]}]}"""
    }

    private fun assertCemAbsolutePartRead(instance: de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance) {
        instance.transform.reset()
        instance.cemExpression.draw()
        assertEquals(
            instance.transform["body"]!!.matrix[0, 0],
            cos(0.25f),
            0.0001f,
        )
    }
}
