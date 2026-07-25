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

package de.bixilon.minosoft.gui.rendering.skeletal.model

import com.fasterxml.jackson.annotation.JsonIgnore
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalTransform
import de.bixilon.minosoft.gui.rendering.skeletal.baked.SkeletalBakeContext
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.AbstractSkeletalMeshBuilder
import de.bixilon.minosoft.gui.rendering.skeletal.model.animations.SkeletalAnimation
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalElement
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.*
import de.bixilon.minosoft.gui.rendering.skeletal.model.transforms.SkeletalTransform
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationClip
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.SkeletalExpressionBinding
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import java.util.concurrent.atomic.AtomicInteger

data class SkeletalModel(
    val elements: Map<String, SkeletalElement>,
    val textures: Map<ResourceLocation, SkeletalTexture>,
    val animations: Map<String, SkeletalAnimation> = emptyMap(),
    val transforms: Map<String, SkeletalTransform> = emptyMap(),
    val neutralAnimations: Map<String, SkeletalAnimationClip> = emptyMap(),
    val expressions: List<SkeletalExpressionBinding> = emptyList(),
    val expressionAliases: Map<String, String> = emptyMap(),
    val contentIdentity: SkeletalContentIdentity? = null,
) {
    @JsonIgnore
    val loadedTextures: MutableSkeletalInstanceTextureMap = mutableMapOf()

    fun load(context: RenderContext, skip: Set<ResourceLocation>) {
        for ((name, properties) in this.textures) {
            if (name in skip) continue

            val file = properties.source ?: name.texture()
            if (file in skip) continue

            val texture = context.textures.static.create(file)
            this.loadedTextures[name] = SkeletalTextureInstance(properties, texture)
        }
    }

    fun bindLoadedTextures(
        skip: Set<ResourceLocation>,
        resolve: (ResourceLocation) -> de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture,
    ) {
        check(loadedTextures.isEmpty()) { "Skeletal textures are already bound." }
        for ((name, properties) in textures) {
            if (name in skip) continue
            val file = properties.source ?: name.texture()
            if (file in skip) continue
            val texture = resolve(file)
            loadedTextures[name] = SkeletalTextureInstance(properties, texture)
        }
    }

    private fun buildTextures(override: SkeletalTextureMap): SkeletalInstanceTextureMap {
        val textures: MutableSkeletalInstanceTextureMap = this.loadedTextures.toMutableMap()

        for ((name, texture) in override) {
            val instance = textures[name]
            if (instance != null) {
                instance.texture = texture
            } else {
                val properties = this.textures[name] ?: continue
                textures[name] = SkeletalTextureInstance(properties, texture)
            }
        }

        return textures
    }

    private fun buildTransforms(): Pair<BakedSkeletalTransform, Int> {
        val transforms: MutableMap<String, BakedSkeletalTransform> = mutableMapOf()

        val transformId = AtomicInteger(1)
        for ((name, transform) in this.transforms) {
            transforms[name] = transform.bake(transformId)
        }
        val baseTransform = BakedSkeletalTransform(0, Vec3f.EMPTY, transforms)

        return Pair(baseTransform, transformId.get())
    }

    private fun buildElements(
        consumer: AbstractSkeletalMeshBuilder,
        textures: SkeletalInstanceTextureMap,
        transform: BakedSkeletalTransform,
        includedMaterials: Set<ResourceLocation>?,
        excludedMaterials: Set<ResourceLocation>,
    ) {
        val context = SkeletalBakeContext(
            transform = transform,
            textures = textures,
            consumer = consumer,
            includedMaterials = includedMaterials,
            excludedMaterials = excludedMaterials,
        )

        for ((name, element) in elements) {
            element.bake(context, name)
        }
    }

    fun bake(
        override: SkeletalTextureMap,
        mesh: AbstractSkeletalMeshBuilder,
        includedMaterials: Set<ResourceLocation>? = null,
        excludedMaterials: Set<ResourceLocation> = emptySet(),
    ): BakedSkeletalModel {
        require(includedMaterials == null || includedMaterials.intersect(excludedMaterials).isEmpty()) {
            "Included and excluded skeletal materials must not overlap."
        }
        val textures = buildTextures(override)
        val (transform, count) = buildTransforms()
        buildElements(mesh, textures, transform, includedMaterials, excludedMaterials)

        return BakedSkeletalModel(
            mesh.bake(),
            transform,
            count,
            animations,
            neutralAnimations,
            expressions = expressions,
            expressionAliases = expressionAliases,
            contentIdentity = contentIdentity,
        )
    }
}
