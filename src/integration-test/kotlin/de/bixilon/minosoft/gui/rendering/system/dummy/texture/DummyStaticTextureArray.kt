/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.dummy.texture

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.shader.types.TextureShader
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureStates
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureArray
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureArrayUpdate
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture

class DummyStaticTextureArray(context: RenderContext) : StaticTextureArray(context, false, 0) {
    var liveUpdateResources = 0
        private set
    var completedUpdates = 0
        private set
    var rolledBackUpdates = 0
        private set
    private val managed = linkedSetOf<Texture>()
    private val references = linkedMapOf<Texture, Int>()

    val managedTextureSlots: Int
        get() = synchronized(references) { managed.size }
    val liveTextureSlots: Int
        get() = synchronized(references) { references.count { it.value > 0 } }
    val freeTextureSlots: Int
        get() = synchronized(references) { managed.count { (references[it] ?: 0) == 0 } }

    override fun upload(textures: Collection<Texture>) {
        for (texture in textures) {
            texture.renderData = DummyTextureRenderData
            if (texture.loader !is DummyTextureLoader) continue
            texture.state = TextureStates.LOADED
        }
    }

    override fun upload(latch: AbstractLatch?) {
        super.upload(latch)
        animator.init()
    }

    override fun update(texture: Texture) = Unit

    override fun beginUpdate(): StaticTextureArrayUpdate {
        val free = synchronized(references) {
            managed.filterTo(linkedSetOf()) { (references[it] ?: 0) == 0 }
        }
        val removed = removeNamed { it in free }
        removed.values.forEach(animator::remove)
        synchronized(references) {
            managed.removeAll(free)
            free.forEach(references::remove)
        }
        return Update()
    }

    override fun retainGeneration(
        textures: Collection<Texture>,
        reclaimable: Collection<Texture>,
    ): AutoCloseable = retain(textures, reclaimable)

    override fun findResolution(size: Vec2i) = size

    override fun use(shader: TextureShader, name: String) = Unit

    private inner class Update : StaticTextureArrayUpdate {
        private val candidates = linkedMapOf<ResourceLocation, Texture>()
        private val previous = linkedMapOf<ResourceLocation, Texture?>()
        private val retained = linkedSetOf<Texture>()
        private val reclaimable = linkedSetOf<Texture>()
        private var uploaded = false
        private var published = false
        private var completed = false
        private var closed = false

        override fun resolve(name: ResourceLocation, mipmaps: Boolean, loader: TextureLoader): Texture {
            check(!uploaded && !closed)
            candidates[name]?.let { return it }
            val texture = Texture(loader, if (mipmaps) this@DummyStaticTextureArray.mipmaps else 0)
            texture.load(context)
            texture.renderData = DummyTextureRenderData
            val old = this@DummyStaticTextureArray[name]
            previous[name] = old
            candidates[name] = texture
            retained += texture
            val inheritsPermanent = synchronized(references) { old != null && old !in managed }
            if (!inheritsPermanent) reclaimable += texture
            return texture
        }

        override fun retain(texture: Texture): Texture {
            check(!uploaded && !closed)
            retained += texture
            return texture
        }

        override fun upload() {
            check(!uploaded && !closed)
            liveUpdateResources++
            uploaded = true
        }

        override fun publish() {
            check(uploaded && !published && !closed)
            publishNamed(candidates)
            published = true
        }

        override fun complete(): AutoCloseable {
            check(published && !completed && !closed)
            for (texture in previous.values) {
                if (texture != null) animator.remove(texture)
            }
            liveUpdateResources--
            completedUpdates++
            completed = true
            closed = true
            return this@DummyStaticTextureArray.retain(retained, reclaimable)
        }

        override fun close() {
            if (closed || completed) return
            if (published) restoreNamed(previous)
            for (texture in candidates.values) animator.remove(texture)
            if (uploaded) {
                liveUpdateResources--
                rolledBackUpdates++
            }
            closed = true
        }
    }

    private fun retain(textures: Collection<Texture>, reclaimable: Collection<Texture>): AutoCloseable {
        val retained = synchronized(references) {
            managed += reclaimable
            textures.filterTo(linkedSetOf()) { texture ->
                if (texture !in managed) return@filterTo false
                references[texture] = (references[texture] ?: 0) + 1
                true
            }
        }
        return object : AutoCloseable {
            private var closed = false

            override fun close() {
                synchronized(this) {
                    if (closed) return
                    closed = true
                }
                synchronized(references) {
                    for (texture in retained) {
                        val count = references.getValue(texture)
                        check(count > 0)
                        if (count == 1) references.remove(texture) else references[texture] = count - 1
                    }
                }
            }
        }
    }
}
