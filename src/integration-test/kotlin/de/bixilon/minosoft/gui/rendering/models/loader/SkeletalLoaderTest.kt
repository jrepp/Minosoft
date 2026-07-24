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
import de.bixilon.minosoft.assets.session.SessionDataPackManager
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferManager
import de.bixilon.minosoft.gui.rendering.framebuffer.world.WorldFramebuffer
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
import org.testng.Assert.assertSame
import org.testng.Assert.assertThrows
import org.testng.annotations.Test

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

    fun `bake dummy model`() {
        val loader = createLoader()
        loader.loadDummyModel()
        loader.bake(SimpleLatch(0))
        val baked = loader[dummyModel]!!


        assertEquals(baked.transform.children, mapOf("body" to BakedSkeletalTransform(1, Vec3f(0.0f, 0.5f, 0.0f), mapOf("head" to BakedSkeletalTransform(2, Vec3f(0.0f, 1.0f, 0.0f), emptyMap())))))
    }

    fun `content reload swaps uploaded models and rejects missing texture candidate`() {
        val parser = SkeletalContentParsers.register(CEM_PARSER_REGISTRATION)
        val context = createContext()
        val session = context.session
        val dataPacks = SessionDataPackManager()
        session::dataPacks.forceSet(dataPacks)
        context::thread.forceSet(Thread.currentThread())
        val framebuffer = FramebufferManager::class.java.allocate()
        framebuffer::world.forceSet(WorldFramebuffer::class.java.allocate())
        context::framebuffer.forceSet(framebuffer)
        context::renderer.forceSet(RendererManager(context))
        val modelLoader = ModelLoader(context)
        context::models.forceSet(modelLoader)
        val source = ResourceLocation.of("test:optifine/cem/zombie.jem")
        val texture = ResourceLocation.of("test:textures/__content/zombie.png")
        val assets = MemoryAssetsManager()
        session.assets.add(assets)
        context.textures.static.create(texture, loader = DummyTextureLoader)
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

            val first = modelLoader.skeletal[modelName]!!
            val retained = first.createInstance(context)
            retained.load()

            assets.push(source, cem(6))
            assertEquals(modelLoader.skeletal.reloadContentFidelity(), 2L)
            val second = modelLoader.skeletal[modelName]!!
            assertNotSame(second, first)
            assertEquals(modelLoader.skeletal.contentModel(entity), modelName)
            assertThrows(IllegalStateException::class.java) { first.createInstance(context) }

            assets.push(source, cem(8, texture = "textures/entity/new.png"))
            assertThrows(IllegalArgumentException::class.java) {
                modelLoader.skeletal.reloadContentFidelity()
            }
            assertEquals(session.contentFidelity.generationId, 2L)
            assertSame(modelLoader.skeletal[modelName], second)

            retained.unload()
            modelLoader.skeletal.unload()
        } finally {
            session.contentFidelity.close()
            parser.close()
        }
    }

    private fun cem(size: Int, texture: String? = null): String {
        val rootTexture = texture?.let { ",\"texture\":\"$it\"" }.orEmpty()
        return """{"textureSize":[64,64]$rootTexture,"models":[{"id":"body","part":"body","boxes":[{"coordinates":[0,0,0,$size,$size,$size],"textureOffset":[0,0]}]}]}"""
    }
}
