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

package de.bixilon.minosoft.gui.rendering.skeletal.binding

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.models.block.element.face.FaceUV
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalElement
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalFace
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalRotation
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.SkeletalTexture
import de.bixilon.minosoft.gui.rendering.skeletal.model.transforms.SkeletalTransform
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil.rad
import kotlin.math.roundToInt

data class SkeletalModelBinding(
    val model: SkeletalModel,
    val defaultMaterial: ResourceLocation,
    val expressions: List<SkeletalExpressionBinding>,
    val aliases: Map<String, String> = emptyMap(),
    val attachments: Map<String, String> = emptyMap(),
)

/**
 * CPU-side bridge from the shared content IR to Minosoft's retained skeletal
 * renderer model. Texture upload and mesh creation remain render-thread work.
 */
object SkeletalModelBinder {

    fun bind(
        content: SkeletalContent,
        versionId: Int? = null,
        entity: ResourceLocation? = null,
    ): SkeletalModelBinding {
        require((versionId == null) == (entity == null)) {
            "Skeletal aliases require both a protocol version and entity type."
        }
        val aliases = linkedMapOf<String, String>()
        val attachments = linkedMapOf<String, String>()
        fun runtimeName(bone: SkeletalBone): String {
            val part = bone.target ?: bone.name
            val resolved = if (versionId != null && entity != null) {
                SkeletalPartAliases.resolve(versionId, entity, part)
            } else {
                part
            }
            val runtime = if (bone.attach && bone.target != null) {
                "$resolved\$attach\$${safeName(bone.name)}"
            } else {
                resolved
            }
            aliases[bone.name] = runtime
            bone.target?.let { aliases[it] = resolved }
            if (bone.attach && bone.target != null) attachments[runtime] = resolved
            return runtime
        }
        content.bones.values.forEach(::runtimeName)

        val safeIdentifier = content.identifier.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")
        val default = ResourceLocation(content.source.namespace, "__content/$safeIdentifier")
        val textures = linkedMapOf<ResourceLocation, SkeletalTexture>()
        fun material(raw: String?): ResourceLocation {
            val source = raw?.let { resolveTexture(content.source, it) }
            val key = source?.let { ResourceLocation(it.namespace, it.path.removePrefix("textures/").removeSuffix(".png")) } ?: default
            textures.putIfAbsent(key, SkeletalTexture(content.textureSize, source))
            return key
        }
        val modelMaterial = material(content.texture)

        fun transform(bone: SkeletalBone): SkeletalTransform {
            return SkeletalTransform(
                pivot = axes(bone.pivot, bone.invertAxes),
                rotation = rotation(content.format, axes(bone.rotation, bone.invertAxes)),
                scale = bone.scale,
                children = bone.children.associateUnique("transform", content) { runtimeName(it) to transform(it) },
            )
        }

        fun element(bone: SkeletalBone): SkeletalElement {
            val children = linkedMapOf<String, SkeletalElement>()
            bone.cubes.forEachIndexed { index, cube ->
                val cubeMaterial = material(cube.material ?: content.texture)
                val from = cube.origin - cube.inflate
                val to = cube.origin + cube.size + cube.inflate
                val (uv, faces) = when (val sourceUv = cube.uv) {
                    is SkeletalUv.Box -> Vec2i(sourceUv.offset.x.roundToInt(), sourceUv.offset.y.roundToInt()) to
                        Directions.entries.associateWith { SkeletalFace(texture = cubeMaterial) }
                    is SkeletalUv.Faces -> null to sourceUv.faces.mapValues { (_, face) ->
                        val faceMaterial = material(face.material ?: cube.material ?: content.texture)
                        SkeletalFace(
                            uv = FaceUV(face.offset, face.offset + face.size),
                            texture = faceMaterial,
                            rotation = face.rotation,
                        )
                    }
                }
                children["cube_$index"] = SkeletalElement(
                    from = from,
                    to = to,
                    rotation = cube.pivot?.let { SkeletalRotation(cube.rotation, it) }
                        ?: cube.rotation.takeUnless { it == Vec3f.EMPTY }?.let { SkeletalRotation(it) },
                    texture = cubeMaterial,
                    uv = uv,
                    faces = faces,
                )
            }
            bone.children.forEach {
                val name = runtimeName(it)
                require(children.putIfAbsent(name, element(it)) == null) {
                    "Duplicate bound skeletal element '$name' in ${content.source}."
                }
            }
            return SkeletalElement(
                from = Vec3f.EMPTY,
                to = Vec3f.EMPTY,
                transform = runtimeName(bone),
                faces = emptyMap(),
                children = children,
            )
        }

        val model = SkeletalModel(
            elements = content.roots.associateUnique("element", content) { runtimeName(it) to element(it) },
            textures = textures,
            transforms = content.roots.associateUnique("transform", content) { runtimeName(it) to transform(it) },
            neutralAnimations = content.animations,
            expressions = content.expressions,
            expressionAliases = aliases,
        )
        return SkeletalModelBinding(model, modelMaterial, content.expressions, aliases, attachments)
    }

    private fun safeName(name: String) = name.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")

    private inline fun <T, V> Iterable<T>.associateUnique(
        kind: String,
        content: SkeletalContent,
        transform: (T) -> Pair<String, V>,
    ): Map<String, V> {
        val result = linkedMapOf<String, V>()
        for (entry in this) {
            val (name, value) = transform(entry)
            require(result.putIfAbsent(name, value) == null) {
                "Duplicate bound skeletal $kind '$name' in ${content.source}."
            }
        }
        return result
    }

    private fun axes(value: Vec3f, inverted: Set<Axes>) = Vec3f(
        if (Axes.X in inverted) -value.x else value.x,
        if (Axes.Y in inverted) -value.y else value.y,
        if (Axes.Z in inverted) -value.z else value.z,
    )

    private fun rotation(format: SkeletalContentFormat, value: Vec3f): Vec3f {
        return when (format) {
            SkeletalContentFormat.GECKOLIB, SkeletalContentFormat.MINOSOFT -> value.rad
            SkeletalContentFormat.OPTIFINE_CEM -> value
        }
    }

    private fun resolveTexture(owner: ResourceLocation, raw: String): ResourceLocation {
        val parsed = if (':' in raw) ResourceLocation.of(raw) else ResourceLocation(owner.namespace, raw)
        var path = parsed.path
        if (!path.endsWith(".png", ignoreCase = true)) path += ".png"
        if (!path.startsWith("textures/") && !path.startsWith("optifine/")) {
            path = if ('/' in raw) "textures/$path" else owner.path.substringBeforeLast('/', "") + "/" + path
        }
        return ResourceLocation(parsed.namespace, path.removePrefix("/"))
    }
}
