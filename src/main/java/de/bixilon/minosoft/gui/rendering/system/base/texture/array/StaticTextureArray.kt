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

package de.bixilon.minosoft.gui.rendering.system.base.texture.array

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.concurrent.lock.RWLock
import de.bixilon.kutil.concurrent.pool.DefaultThreadPool
import de.bixilon.kutil.concurrent.pool.ThreadPool
import de.bixilon.kutil.concurrent.pool.runnable.ThreadPoolRunnable
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.latch.AbstractLatch.Companion.child
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.TextureStates
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.SpriteAnimator
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.TextureAnimation
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.PNGTextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import kotlin.time.Duration

abstract class StaticTextureArray(
    val context: RenderContext,
    val async: Boolean,
    val mipmaps: Int,
) : TextureArray {
    protected val named: MutableMap<ResourceLocation, Texture> = mutableMapOf()
    protected val other: MutableSet<Texture> = mutableSetOf()
    private val lock = RWLock.rwlock()

    val animator = SpriteAnimator(context)
    var state: TextureArrayStates = TextureArrayStates.PREPARING
        protected set


    operator fun get(name: ResourceLocation): Texture? {
        lock.acquire()
        return try {
            this.named[name]
        } finally {
            lock.release()
        }
    }

    operator fun plusAssign(texture: Texture) = push(texture)

    fun push(texture: Texture) {
        if (state != TextureArrayStates.PREPARING) throw IllegalStateException("Already loaded!")
        lock.lock()
        other += texture
        lock.unlock()
        if (texture.state != TextureStates.LOADED && async) {
            DefaultThreadPool += ThreadPoolRunnable(forcePool = true) { texture.load(context) }
        }
    }

    open fun create(name: ResourceLocation, mipmaps: Boolean = true, loader: TextureLoader = PNGTextureLoader(name)): Texture {
        if (state != TextureArrayStates.PREPARING) throw IllegalStateException("Already loaded!")
        lock.lock()
        named[name]?.let { lock.unlock(); return it }
        val texture = Texture(loader, if (mipmaps) this.mipmaps else 0)

        named[name] = texture
        lock.unlock()
        if (async) {
            DefaultThreadPool += ThreadPoolRunnable(forcePool = true) { texture.load(context) }
        }

        return texture
    }

    abstract fun findResolution(size: Vec2i): Vec2i


    private fun load(latch: AbstractLatch, textures: Collection<Texture>) {
        for (texture in textures) {
            if (texture.state != TextureStates.DECLARED) continue

            latch.inc()
            DefaultThreadPool += ThreadPoolRunnable(ThreadPool.Priorities.HIGH) { texture.load(context); latch.dec() }
        }
    }

    protected abstract fun upload(textures: Collection<Texture>)

    abstract fun update(texture: Texture)

    /**
     * Advances material companions that own an animation timeline independent
     * from the diffuse texture. Implementations may synchronize to the base
     * sprite's [TextureAnimation] when one exists.
     */
    open fun advanceMaterialAnimations(delta: Duration) = Unit

    /**
     * Publishes material companion frames prepared by
     * [advanceMaterialAnimations] on the render thread.
     */
    open fun uploadMaterialAnimations() = Unit

    abstract fun beginUpdate(): StaticTextureArrayUpdate

    /**
     * Retains the shader slots used by one content generation. Only textures
     * in [reclaimable] may turn their slots into reusable holes after the
     * returned lifetime closes; pre-existing resource-pack textures remain
     * permanent even when a content model references them.
     */
    abstract fun retainGeneration(
        textures: Collection<Texture>,
        reclaimable: Collection<Texture> = textures,
    ): AutoCloseable

    protected fun publishNamed(textures: Map<ResourceLocation, Texture>) {
        lock.lock()
        named.putAll(textures)
        lock.unlock()
    }

    protected fun restoreNamed(textures: Map<ResourceLocation, Texture?>) {
        lock.lock()
        for ((name, texture) in textures) {
            if (texture == null) {
                named.remove(name)
            } else {
                named[name] = texture
            }
        }
        lock.unlock()
    }

    protected fun removeNamed(predicate: (Texture) -> Boolean): Map<ResourceLocation, Texture> {
        lock.lock()
        return try {
            val removed = linkedMapOf<ResourceLocation, Texture>()
            val iterator = named.iterator()
            while (iterator.hasNext()) {
                val (name, texture) = iterator.next()
                if (!predicate(texture)) continue
                removed[name] = texture
                iterator.remove()
            }
            removed
        } finally {
            lock.unlock()
        }
    }

    override fun load(latch: AbstractLatch?) {
        if (state != TextureArrayStates.PREPARING) throw IllegalStateException("Already loaded!")
        val latch = latch.child(0)
        load(latch, named.values)
        load(latch, other)
        latch.await()
        state = TextureArrayStates.LOADED
    }

    override fun upload(latch: AbstractLatch?) {
        if (state != TextureArrayStates.LOADED) throw IllegalStateException("Not loaded!")

        upload(named.values)
        upload(other)

        state = TextureArrayStates.UPLOADED
    }
}
