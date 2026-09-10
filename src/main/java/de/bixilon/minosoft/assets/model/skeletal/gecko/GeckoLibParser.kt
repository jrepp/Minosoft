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

package de.bixilon.minosoft.assets.model.skeletal.gecko

import com.fasterxml.jackson.databind.JsonNode
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.json.Jackson
import java.io.InputStream

/**
 * Parser for GeckoLib's Bedrock-style geo and animation JSON. Controller and
 * event APIs intentionally remain outside this data-only boundary.
 */
class GeckoLibParser {

    fun parseGeometry(source: ResourceLocation, input: InputStream): SkeletalContentDocument {
        val root = read(source, input)
        val version = root["format_version"]?.asText()
        val geometries = root["minecraft:geometry"] ?: fail(source, "$['minecraft:geometry']", "Missing geometry array.")
        if (!geometries.isArray) fail(source, "$['minecraft:geometry']", "Expected an array.")

        val models = geometries.mapIndexed { index, geometry -> parseGeometry(source, version, geometry, "$['minecraft:geometry'][$index]") }
        if (models.isEmpty()) fail(source, "$['minecraft:geometry']", "At least one geometry is required.")
        return SkeletalContentDocument(source, SkeletalContentFormat.GECKOLIB, version, models)
    }

    fun parseAnimations(source: ResourceLocation, input: InputStream): Map<String, SkeletalAnimationClip> {
        val root = read(source, input)
        val animations = root["animations"] ?: fail(source, "$.animations", "Missing animations object.")
        if (!animations.isObject) fail(source, "$.animations", "Expected an object.")

        val result = linkedMapOf<String, SkeletalAnimationClip>()
        animations.properties().forEach { (name, animation) ->
            result[name] = parseAnimation(source, name, animation, "$.animations.$name")
        }
        return result
    }

    fun attachAnimations(model: SkeletalContent, animations: Map<String, SkeletalAnimationClip>): SkeletalContent {
        require(model.format == SkeletalContentFormat.GECKOLIB) { "Animations can only be attached to GeckoLib content." }
        val unknown = animations.values.flatMap { it.channels.keys }.filterNot { it in model.bones }.distinct()
        require(unknown.isEmpty()) { "Animations reference unknown bones: ${unknown.joinToString()}" }
        return model.copy(animations = animations.toMap())
    }

    private fun parseGeometry(source: ResourceLocation, version: String?, geometry: JsonNode, path: String): SkeletalContent {
        if (!geometry.isObject) fail(source, path, "Expected a geometry object.")
        val description = geometry["description"] ?: fail(source, "$path.description", "Missing geometry description.")
        val identifier = description["identifier"]?.textOrNull() ?: fail(source, "$path.description.identifier", "Missing identifier.")
        val textureSize = Vec2i(
            description["texture_width"]?.intOrNull() ?: 64,
            description["texture_height"]?.intOrNull() ?: 32,
        )
        val bones = geometry["bones"] ?: fail(source, "$path.bones", "Missing bones array.")
        if (!bones.isArray) fail(source, "$path.bones", "Expected an array.")

        val raw = linkedMapOf<String, RawBone>()
        bones.forEachIndexed { index, node ->
            if (!node.isObject) fail(source, "$path.bones[$index]", "Expected a bone object.")
            val name = node["name"]?.textOrNull() ?: fail(source, "$path.bones[$index].name", "Missing bone name.")
            if (raw.containsKey(name)) fail(source, "$path.bones[$index].name", "Duplicate bone '$name'.")
            raw[name] = RawBone(name, node["parent"]?.textOrNull(), node, "$path.bones[$index]")
        }
        if (raw.size > MAX_BONES) fail(source, "$path.bones", "Geometry exceeds $MAX_BONES bones.")
        raw.values.forEach { bone ->
            if (bone.parent != null && bone.parent !in raw) fail(source, "${bone.path}.parent", "Unknown parent '${bone.parent}'.")
        }
        val childrenByParent = raw.values.groupBy(RawBone::parent)

        val visiting = hashSetOf<String>()
        val built = hashMapOf<String, SkeletalBone>()
        fun build(rawBone: RawBone, depth: Int): SkeletalBone {
            if (depth > MAX_BONE_DEPTH) fail(source, rawBone.path, "Bone hierarchy exceeds $MAX_BONE_DEPTH levels.")
            built[rawBone.name]?.let { return it }
            if (!visiting.add(rawBone.name)) fail(source, rawBone.path, "Bone hierarchy contains a cycle at '${rawBone.name}'.")
            val node = rawBone.node
            val pivot = node.vector3(source, "${rawBone.path}.pivot", "pivot", Vec3f.EMPTY)
            val mirror = node["mirror"]?.booleanValue() ?: false
            val cubes = node["cubes"]?.let { values ->
                if (!values.isArray) fail(source, "${rawBone.path}.cubes", "Expected an array.")
                values.mapIndexed { index, cube ->
                    parseCube(source, cube, "${rawBone.path}.cubes[$index]", pivot, mirror)
                }
            } ?: emptyList()
            val children = childrenByParent[rawBone.name].orEmpty().map { build(it, depth + 1) }
            visiting.remove(rawBone.name)
            return SkeletalBone(
                name = rawBone.name,
                pivot = pivot,
                rotation = node.vector3(source, "${rawBone.path}.rotation", "rotation", Vec3f.EMPTY),
                mirrorTextureU = mirror,
                cubes = cubes,
                children = children,
            ).also { built[rawBone.name] = it }
        }
        val roots = childrenByParent[null].orEmpty().map { build(it, 0) }
        if (built.size != raw.size) {
            raw.values.firstOrNull { it.name !in built }?.let { build(it, 0) }
        }

        val metadata = buildMap {
            listOf("visible_bounds_width", "visible_bounds_height").forEach { key ->
                description[key]?.takeIf(JsonNode::isNumber)?.let { put(key, it.asText()) }
            }
            description["visible_bounds_offset"]?.takeIf(JsonNode::isArray)?.let { put("visible_bounds_offset", it.toString()) }
        }
        return try {
            SkeletalContent(
                source = source,
                format = SkeletalContentFormat.GECKOLIB,
                formatVersion = version,
                identifier = identifier,
                textureSize = textureSize,
                roots = roots,
                metadata = metadata,
            )
        } catch (error: IllegalArgumentException) {
            throw SkeletalContentParseException(source, path, error.message ?: "Invalid GeckoLib geometry.", error)
        }
    }

    private fun parseCube(source: ResourceLocation, node: JsonNode, path: String, bonePivot: Vec3f, boneMirror: Boolean): SkeletalCube {
        if (!node.isObject) fail(source, path, "Expected a cube object.")
        val origin = node.vector3(source, "$path.origin", "origin")
        val size = node.vector3(source, "$path.size", "size")
        val uvNode = node["uv"] ?: fail(source, "$path.uv", "Missing cube UV.")
        val uv = when {
            uvNode.isArray -> SkeletalUv.Box(uvNode.vector2(source, "$path.uv"))
            uvNode.isObject -> {
                val faces = linkedMapOf<Directions, SkeletalFaceUv>()
                for (direction in Directions) {
                    val face = uvNode[direction.name.lowercase()] ?: continue
                    if (!face.isObject) fail(source, "$path.uv.${direction.name.lowercase()}", "Expected a face object.")
                    val offset = face["uv"]?.vector2(source, "$path.uv.${direction.name.lowercase()}.uv")
                        ?: fail(source, "$path.uv.${direction.name.lowercase()}.uv", "Missing face UV.")
                    val dimensions = face["uv_size"]?.vector2(source, "$path.uv.${direction.name.lowercase()}.uv_size")
                        ?: Vec2f(size.x, size.y)
                    faces[direction] = SkeletalFaceUv(offset, dimensions)
                }
                if (faces.isEmpty()) fail(source, "$path.uv", "Per-face UV object contains no directions.")
                SkeletalUv.Faces(faces)
            }
            else -> fail(source, "$path.uv", "Expected a box UV array or per-face object.")
        }
        val inflate = node["inflate"]?.numberOrNull()?.toFloat() ?: 0.0f
        return SkeletalCube(
            origin = origin,
            size = size,
            pivot = node["pivot"]?.let { node.vector3(source, "$path.pivot", "pivot") } ?: bonePivot,
            rotation = node.vector3(source, "$path.rotation", "rotation", Vec3f.EMPTY),
            inflate = Vec3f(inflate),
            mirror = node["mirror"]?.booleanValue() ?: boneMirror,
            uv = uv,
        )
    }

    private fun parseAnimation(source: ResourceLocation, name: String, node: JsonNode, path: String): SkeletalAnimationClip {
        if (!node.isObject) fail(source, path, "Expected an animation object.")
        val channels = linkedMapOf<String, List<SkeletalAnimationChannel>>()
        var maximumTime = 0.0f
        val bones = node["bones"]
        if (bones != null) {
            if (!bones.isObject) fail(source, "$path.bones", "Expected a bones object.")
            bones.properties().forEach { (bone, animation) ->
                if (!animation.isObject) fail(source, "$path.bones.$bone", "Expected a bone animation object.")
                val boneChannels = mutableListOf<SkeletalAnimationChannel>()
                listOf(
                    "rotation" to SkeletalAnimationTarget.ROTATION,
                    "position" to SkeletalAnimationTarget.TRANSLATION,
                    "scale" to SkeletalAnimationTarget.SCALE,
                ).forEach { (field, target) ->
                    animation[field]?.let {
                        val keyframes = parseChannel(source, it, "$path.bones.$bone.$field")
                        keyframes.lastOrNull()?.let { frame -> maximumTime = maxOf(maximumTime, frame.timeSeconds) }
                        boneChannels += SkeletalAnimationChannel(target, keyframes)
                    }
                }
                if (boneChannels.isNotEmpty()) channels[bone] = boneChannels
            }
        }
        val length = node["animation_length"]?.numberOrNull()?.toFloat() ?: maximumTime
        val loopValue = node["loop"]
        val loop = when (val value = loopValue) {
            null -> SkeletalAnimationLoop.ONCE
            else -> when {
                value.isBoolean && value.booleanValue() -> SkeletalAnimationLoop.LOOP
                value.isTextual && value.asText().equals("hold_on_last_frame", true) -> SkeletalAnimationLoop.HOLD
                value.isTextual && value.asText().equals("true", true) -> SkeletalAnimationLoop.LOOP
                else -> SkeletalAnimationLoop.ONCE
            }
        }
        val sourceLoopType = loopValue
            ?.takeIf(JsonNode::isTextual)
            ?.asText()
            ?.lowercase()
            ?.takeUnless { it in BUILT_IN_LOOP_TYPES }
        return SkeletalAnimationClip(
            name = name,
            lengthSeconds = length,
            loop = loop,
            channels = channels,
            events = parseEvents(source, node, path),
            sourceLoopType = sourceLoopType,
        )
    }

    private fun parseEvents(source: ResourceLocation, animation: JsonNode, path: String): List<SkeletalAnimationEvent> {
        val events = mutableListOf<SkeletalAnimationEvent>()

        fun entries(field: String, consume: (Float, JsonNode, String) -> Unit) {
            val values = animation[field] ?: return
            if (!values.isObject) fail(source, "$path.$field", "Expected an event timeline object.")
            values.properties().forEach { (rawTime, value) ->
                if (events.size >= MAX_EVENTS) fail(source, "$path.$field", "Animation exceeds $MAX_EVENTS events.")
                val time = rawTime.toFloatOrNull()
                    ?.takeIf { it.isFinite() && it >= 0.0f }
                    ?: fail(source, "$path.$field.$rawTime", "Event key must be a non-negative time in seconds.")
                consume(time, value, "$path.$field.$rawTime")
            }
        }

        entries("sound_effects") { time, value, eventPath ->
            if (!value.isObject) fail(source, eventPath, "Expected a sound event object.")
            val effect = value["effect"]?.textOrNull()
                ?: fail(source, "$eventPath.effect", "Missing sound effect.")
            events += SkeletalAnimationEvent(time, SkeletalAnimationEventType.SOUND, effect)
        }
        entries("particle_effects") { time, value, eventPath ->
            if (!value.isObject) fail(source, eventPath, "Expected a particle event object.")
            events += SkeletalAnimationEvent(
                timeSeconds = time,
                type = SkeletalAnimationEventType.PARTICLE,
                payload = value["effect"]?.textOrNull().orEmpty(),
                locator = value["locator"]?.textOrNull()?.takeIf(String::isNotEmpty),
                preEffectScript = value["pre_effect_script"]?.textOrNull()?.takeIf(String::isNotEmpty),
            )
        }
        entries("timeline") { time, value, eventPath ->
            val instructions = when {
                value.isTextual -> value.asText()
                value.isArray && value.all(JsonNode::isTextual) -> value.joinToString(";") { it.asText() }
                else -> fail(source, eventPath, "Expected a custom instruction string or string array.")
            }
            events += SkeletalAnimationEvent(time, SkeletalAnimationEventType.CUSTOM_INSTRUCTION, instructions)
        }
        return events.sortedBy(SkeletalAnimationEvent::timeSeconds)
    }

    private fun parseChannel(source: ResourceLocation, node: JsonNode, path: String): List<SkeletalAnimationKeyframe> {
        if (node.isArray || node.isNumber || node.isTextual || node["vector"] != null) {
            return listOf(parseKeyframe(source, 0.0f, node, path))
        }
        if (!node.isObject) fail(source, path, "Expected a vector or keyed channel object.")
        val keyframes = node.properties().asSequence().map { (time, value) ->
            val seconds = time.toFloatOrNull() ?: fail(source, "$path.$time", "Keyframe key must be a time in seconds.")
            parseKeyframe(source, seconds, value, "$path.$time")
        }.sortedBy { it.timeSeconds }.toList()
        if (keyframes.isEmpty()) fail(source, path, "Animation channel must not be empty.")
        return keyframes
    }

    private fun parseKeyframe(source: ResourceLocation, time: Float, node: JsonNode, path: String): SkeletalAnimationKeyframe {
        val vectorNode = when {
            node.isObject && node["vector"] != null -> node["vector"]
            node.isObject && node["post"] != null -> node["post"]
            else -> node
        }
        val value = vectorValue(source, vectorNode, "$path.vector")
        val interpolation = when (node["lerp_mode"]?.asText()?.lowercase()) {
            "catmullrom", "catmull_rom" -> SkeletalInterpolation.CATMULL_ROM
            "step" -> SkeletalInterpolation.STEP
            else -> SkeletalInterpolation.LINEAR
        }
        val easingArguments = node["easingArgs"]?.takeIf(JsonNode::isArray)?.mapNotNull { it.numberOrNull()?.toFloat() } ?: emptyList()
        return SkeletalAnimationKeyframe(time, value, interpolation, node["easing"]?.textOrNull(), easingArguments)
    }

    private fun vectorValue(source: ResourceLocation, node: JsonNode, path: String): SkeletalVectorValue {
        if (node.isNumber) {
            val value = node.floatValue()
            return SkeletalVectorValue.Constant(Vec3f(value))
        }
        if (node.isTextual) {
            return SkeletalVectorValue.Expression(List(3) { node.asText() })
        }
        if (!node.isArray || node.size() != 3) fail(source, path, "Expected a three-component vector.")
        return if (node.all(JsonNode::isNumber)) {
            SkeletalVectorValue.Constant(Vec3f(node[0].floatValue(), node[1].floatValue(), node[2].floatValue()))
        } else {
            SkeletalVectorValue.Expression(node.map { it.asText() })
        }
    }

    private fun read(source: ResourceLocation, input: InputStream): JsonNode {
        return try {
            val bytes = input.use { it.readNBytes(MAX_JSON_BYTES + 1) }
            if (bytes.size > MAX_JSON_BYTES) fail(source, "$", "JSON exceeds $MAX_JSON_BYTES bytes.")
            Jackson.MAPPER.readTree(bytes)
        } catch (error: Exception) {
            throw SkeletalContentParseException(source, "$", "Invalid JSON: ${error.message}", error)
        } ?: fail(source, "$", "Empty JSON document.")
    }

    private fun JsonNode.vector3(source: ResourceLocation, path: String, field: String, default: Vec3f? = null): Vec3f {
        val value = this[field] ?: default?.let { return it } ?: fail(source, path, "Missing vector.")
        if (!value.isArray || value.size() != 3 || value.any { !it.isNumber }) fail(source, path, "Expected three numbers.")
        return Vec3f(value[0].floatValue(), value[1].floatValue(), value[2].floatValue())
    }

    private fun JsonNode.vector2(source: ResourceLocation, path: String): Vec2f {
        if (!isArray || size() != 2 || any { !it.isNumber }) fail(source, path, "Expected two numbers.")
        return Vec2f(this[0].floatValue(), this[1].floatValue())
    }

    private fun JsonNode?.textOrNull() = this?.takeIf(JsonNode::isTextual)?.asText()
    private fun JsonNode?.numberOrNull() = this?.takeIf(JsonNode::isNumber)?.numberValue()
    private fun JsonNode?.intOrNull() = this?.takeIf(JsonNode::isIntegralNumber)?.intValue()

    private fun fail(source: ResourceLocation, path: String, message: String): Nothing {
        throw SkeletalContentParseException(source, path, message)
    }

    private data class RawBone(
        val name: String,
        val parent: String?,
        val node: JsonNode,
        val path: String,
    )

    private companion object {
        val BUILT_IN_LOOP_TYPES = setOf("false", "play_once", "hold_on_last_frame", "true", "loop")
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
        const val MAX_BONES = 65_536
        const val MAX_BONE_DEPTH = 64
        const val MAX_EVENTS = 16_384
    }
}
