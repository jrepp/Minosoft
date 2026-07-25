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

package de.bixilon.minosoft.gui.rendering.skeletal.baked

import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.instance.SkeletalInstance
import de.bixilon.minosoft.gui.rendering.skeletal.model.animations.SkeletalAnimation
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.SkeletalExpressionBinding
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerBlend
import de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot
import de.bixilon.minosoft.assets.model.generation.ContentGenerationLease
import de.bixilon.minosoft.data.registries.identified.ResourceLocation

data class BakedSkeletalModel(
    val mesh: Mesh,
    val transform: BakedSkeletalTransform,
    val transformCount: Int,
    val animations: Map<String, SkeletalAnimation>,
    val neutralAnimations: Map<String, SkeletalAnimationClip> = emptyMap(),
    val materialMeshes: Map<ResourceLocation, Mesh> = emptyMap(),
    val entityTextureBase: ResourceLocation? = null,
    val entityTextureLayers: Map<ResourceLocation, BakedEntityTextureLayer> = emptyMap(),
    val expressions: List<SkeletalExpressionBinding> = emptyList(),
    val expressionAliases: Map<String, String> = emptyMap(),
    val contentIdentity: SkeletalContentIdentity? = null,
    val geckoRenderLayers: Map<String, BakedGeckoLibRenderLayer> = emptyMap(),
    var contentLease: ContentGenerationLease<ContentFidelitySnapshot>? = null,
) {
    private var state = SkeletalModelStates.PREPARING
    private var instances = 0
    private var retired = false


    @Synchronized
    fun load() {
        check(!retired) { "Can not upload a retired skeletal model." }
        if (state != SkeletalModelStates.PREPARING) throw IllegalStateException("Can not load model!")
        val meshes = meshes()
        try {
            meshes.forEach(Mesh::load)
            state = SkeletalModelStates.LOADED
        } catch (throwable: Throwable) {
            for (loaded in meshes.asReversed()) {
                if (loaded.state != MeshStates.LOADED) continue
                try {
                    loaded.unload()
                } catch (cleanup: Throwable) {
                    throwable.addSuppressed(cleanup)
                }
            }
            throw throwable
        }
    }

    @Synchronized
    fun unload() {
        check(instances == 0) { "Can not unload a skeletal model with $instances retained instances." }
        retired = true
        disposeIfUnused()
    }

    private fun unloadNow() {
        if (state != SkeletalModelStates.LOADED) throw IllegalStateException("Can not unload model!")
        var failure: Throwable? = null
        val meshes = meshes()
        for (loaded in meshes) {
            if (loaded.state != MeshStates.LOADED) continue
            try {
                loaded.unload()
            } catch (throwable: Throwable) {
                failure?.addSuppressed(throwable) ?: run { failure = throwable }
            }
        }
        state = SkeletalModelStates.UNLOADED
        failure?.let { throw it }
    }

    /**
     * Prevents new instances and releases GPU state after the final retained
     * instance closes. A model retired before upload drops its CPU buffers.
     */
    @Synchronized
    fun retire() {
        if (retired) return
        retired = true
        disposeIfUnused()
    }

    @Synchronized
    fun createInstance(context: RenderContext): SkeletalInstance {
        check(!retired) { "Can not create an instance of a retired skeletal model." }
        instances++
        val transforms = this.transform.instance()
        return try {
            SkeletalInstance(context, this, transforms, AutoCloseable(::releaseInstance))
        } catch (error: Throwable) {
            releaseInstance()
            throw error
        }
    }

    fun mesh(material: ResourceLocation?): Mesh = material?.let(materialMeshes::get) ?: mesh

    fun entityTextureMesh(base: ResourceLocation, texture: ResourceLocation): Mesh? =
        entityTextureLayers[base]?.meshes?.get(texture)

    @Synchronized
    private fun releaseInstance() {
        check(instances > 0) { "Skeletal model instance was released too many times." }
        instances--
        disposeIfUnused()
    }

    private fun disposeIfUnused() {
        if (!retired || instances != 0) return
        var failure: Throwable? = null
        try {
            when (state) {
                SkeletalModelStates.PREPARING -> {
                for (candidate in meshes()) {
                    if (candidate.state != MeshStates.PREPARING) continue
                    try {
                        candidate.drop()
                    } catch (throwable: Throwable) {
                        failure?.addSuppressed(throwable) ?: run { failure = throwable }
                    }
                }
                state = SkeletalModelStates.UNLOADED
                }
                SkeletalModelStates.LOADED -> unloadNow()
                SkeletalModelStates.UNLOADED -> Unit
            }
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        try {
            contentLease?.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        failure?.let { throw it }
    }

    private fun meshes(): List<Mesh> = buildList {
        add(mesh)
        addAll(materialMeshes.values)
        entityTextureLayers.values.forEach { addAll(it.meshes.values) }
        geckoRenderLayers.values.forEach { add(it.mesh) }
    }.distinct()
}

data class BakedEntityTextureLayer(
    val materials: Set<ResourceLocation>,
    val meshes: Map<ResourceLocation, Mesh>,
)

data class BakedGeckoLibRenderLayer(
    val registrationId: Long,
    val blend: GeckoLibRenderLayerBlend,
    val fullBright: Boolean,
    val mesh: Mesh,
)
