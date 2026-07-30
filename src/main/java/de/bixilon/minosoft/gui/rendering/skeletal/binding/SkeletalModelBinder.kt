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
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.assets.model.skeletal.binding.SkeletalPartAliases
import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.models.block.element.face.FaceUV
import de.bixilon.minosoft.gui.rendering.models.util.CuboidUtil
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalElement
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalFace
import de.bixilon.minosoft.gui.rendering.skeletal.model.elements.SkeletalRotation
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.SkeletalTexture
import de.bixilon.minosoft.gui.rendering.skeletal.model.transforms.SkeletalTransform
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3fUtil.rad
import kotlin.math.floor
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
                val (from, to) = cubeBounds(content.format, cube)
                val uv = (cube.uv as? SkeletalUv.Box)?.let {
                    Vec2i(it.offset.x.roundToInt(), it.offset.y.roundToInt())
                }
                val boxUvSize = if (uv == null) null else when (content.format) {
                    SkeletalContentFormat.GECKOLIB -> Vec3f(
                        floor(cube.size.x),
                        floor(cube.size.y),
                        floor(cube.size.z),
                    )
                    SkeletalContentFormat.MINOSOFT, SkeletalContentFormat.OPTIFINE_CEM -> cube.size
                }
                val faces = when (val sourceUv = cube.uv) {
                    is SkeletalUv.Box -> Directions.entries.associateWith { direction ->
                        val faceUv = if (content.format == SkeletalContentFormat.GECKOLIB) {
                            geckoBoxUv(uv!!, boxUvSize!!, direction, cube.mirror)
                        } else {
                            null
                        }
                        SkeletalFace(uv = faceUv, texture = cubeMaterial)
                    }
                    is SkeletalUv.Faces -> sourceUv.faces.mapValues { (_, face) ->
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
                    boxUvSize = boxUvSize,
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
            contentIdentity = SkeletalContentIdentity(content.source, content.format, content.identifier),
        )
        return SkeletalModelBinding(model, modelMaterial, content.expressions, aliases, attachments)
    }

    private fun safeName(name: String) = name.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")

    /**
     * Bedrock models commonly author fins, wings, tails, and antennae as
     * zero-thickness cubes whose hinge lies exactly on a parent boundary.
     * Keeping the UV size untouched while extending the geometry a fraction
     * of a source pixel into the hinge prevents projection/raster seams.
     */
    internal fun cubeBounds(format: SkeletalContentFormat, cube: SkeletalCube): Pair<Vec3f, Vec3f> {
        var from = cube.origin - cube.inflate
        var to = cube.origin + cube.size + cube.inflate
        if (format != SkeletalContentFormat.GECKOLIB) return from to to
        val pivot = cube.pivot ?: return from to to
        val zeroAxes = listOf(cube.size.x, cube.size.y, cube.size.z).count { it == 0.0f }
        if (zeroAxes != 1) return from to to
        val planeShift = Vec3f(
            if (cube.size.x == 0.0f) snapPlane(cube.origin.x, pivot.x) else 0.0f,
            if (cube.size.y == 0.0f) snapPlane(cube.origin.y, pivot.y) else 0.0f,
            if (cube.size.z == 0.0f) snapPlane(cube.origin.z, pivot.z) else 0.0f,
        )
        from += planeShift
        to += planeShift

        fun overlap(
            sourceOrigin: Float,
            sourceSize: Float,
            hinge: Float,
            inflatedFrom: Float,
            inflatedTo: Float,
        ): Pair<Float, Float> {
            if (sourceSize <= 0.0f) return inflatedFrom to inflatedTo
            val end = sourceOrigin + sourceSize
            return when {
                kotlin.math.abs(hinge - sourceOrigin) <= JOINT_EPSILON ->
                    inflatedFrom - PLANE_JOINT_OVERLAP to inflatedTo
                kotlin.math.abs(hinge - end) <= JOINT_EPSILON ->
                    inflatedFrom to inflatedTo + PLANE_JOINT_OVERLAP
                else -> inflatedFrom to inflatedTo
            }
        }

        val (fromX, toX) = overlap(cube.origin.x, cube.size.x, pivot.x, from.x, to.x)
        val (fromY, toY) = overlap(cube.origin.y, cube.size.y, pivot.y, from.y, to.y)
        val (fromZ, toZ) = overlap(cube.origin.z, cube.size.z, pivot.z, from.z, to.z)
        from = Vec3f(fromX, fromY, fromZ)
        to = Vec3f(toX, toY, toZ)
        return from to to
    }

    private fun snapPlane(origin: Float, hinge: Float): Float {
        val delta = hinge - origin
        return if (kotlin.math.abs(delta) <= PLANE_HINGE_SNAP) delta else 0.0f
    }

    /**
     * Bedrock box UVs place the negative-Z/front face directly after the west
     * slot. Vanilla Java's helper assigns that slot to south instead, which
     * puts authored faces such as duck eyes on the back of the head.
     */
    internal fun geckoBoxUv(
        offset: Vec2i,
        size: Vec3f,
        direction: Directions,
        mirror: Boolean,
    ): FaceUV {
        val sourceDirection = when (direction) {
            Directions.NORTH -> Directions.SOUTH
            Directions.SOUTH -> Directions.NORTH
            else -> direction
        }
        val uv = CuboidUtil.cubeUV(offset, size, sourceDirection)
        if (!mirror) return uv
        return FaceUV(Vec2f(uv.end.x, uv.start.y), Vec2f(uv.start.x, uv.end.y))
    }

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
            // Bedrock/Gecko model-space rotation is clockwise when viewed
            // along the positive axis. Minosoft's retained transform matrices
            // use the opposite right-handed convention.
            SkeletalContentFormat.GECKOLIB -> -value.rad
            SkeletalContentFormat.MINOSOFT -> value.rad
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

    private const val PLANE_JOINT_OVERLAP = 0.05f
    private const val PLANE_HINGE_SNAP = 0.25f
    private const val JOINT_EPSILON = 0.0001f
}
