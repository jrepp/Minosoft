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

package de.bixilon.minosoft.assets.model.skeletal.cem

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.assets.model.skeletal.*
import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.json.Jackson
import java.io.InputStream

fun interface CemPartResolver {
    fun resolve(owner: ResourceLocation, reference: String): InputStream?
}

/**
 * Headless OptiFine CEM JEM/JPM ingestion. It deliberately does not reference
 * EMF, Mojang renderer, or OpenGL classes.
 */
class CemParser(private val resolver: CemPartResolver? = null) {

    fun parseJem(source: ResourceLocation, input: InputStream): SkeletalContent {
        val root = read(source, input, "$")
        val textureSize = root.vector2i(source, "$.textureSize", "textureSize", Vec2i(64, 32))
        val expressions = mutableListOf<SkeletalExpressionBinding>()
        val budget = ParseBudget()
        val models = root["models"] ?: fail(source, "$.models", "Missing CEM models array.")
        if (!models.isArray) fail(source, "$.models", "Expected an array.")

        val roots = models.mapIndexed { index, node ->
            parsePart(source, resolvePart(source, node, "$.models[$index]"), "$.models[$index]", textureSize, expressions, "part_$index", 0, budget)
        }

        val identifier = source.path.substringAfterLast('/').substringBeforeLast('.')
        val metadata = buildMap {
            root["shadow_size"]?.takeIf(JsonNode::isNumber)?.let { put("shadow_size", it.asText()) }
        }
        return content(source, identifier, root["texture"]?.textOrNull(), textureSize, roots, expressions, metadata)
    }

    fun parseJpm(source: ResourceLocation, input: InputStream, textureSize: Vec2i = Vec2i(64, 32)): SkeletalContent {
        val root = read(source, input, "$")
        val expressions = mutableListOf<SkeletalExpressionBinding>()
        val bone = parsePart(source, resolvePart(source, root, "$"), "$", textureSize, expressions, "part", 0, ParseBudget())
        val identifier = source.path.substringAfterLast('/').substringBeforeLast('.')
        return content(source, identifier, root["texture"]?.textOrNull(), textureSize, listOf(bone), expressions, emptyMap())
    }

    private fun content(
        source: ResourceLocation,
        identifier: String,
        texture: String?,
        textureSize: Vec2i,
        roots: List<SkeletalBone>,
        expressions: List<SkeletalExpressionBinding>,
        metadata: Map<String, String>,
    ): SkeletalContent {
        return try {
            SkeletalContent(
                source = source,
                format = SkeletalContentFormat.OPTIFINE_CEM,
                formatVersion = null,
                identifier = identifier,
                textureSize = textureSize,
                texture = texture,
                roots = roots,
                expressions = expressions.toList(),
                metadata = metadata,
            )
        } catch (error: IllegalArgumentException) {
            throw SkeletalContentParseException(source, "$", error.message ?: "Invalid CEM model.", error)
        }
    }

    private fun resolvePart(source: ResourceLocation, inline: JsonNode, path: String): JsonNode {
        val reference = inline["model"]?.textOrNull() ?: return inline
        val stream = resolver?.resolve(source, reference) ?: fail(source, "$path.model", "Unable to resolve JPM reference '$reference'.")
        val base = read(source, stream, "$reference")
        if (!base.isObject || !inline.isObject) fail(source, path, "CEM part must be an object.")

        val merged = (base as ObjectNode).deepCopy()
        inline.properties().forEach { (key, value) ->
            if (key != "model") merged.set<JsonNode>(key, value)
        }
        return merged
    }

    private fun parsePart(
        source: ResourceLocation,
        node: JsonNode,
        path: String,
        inheritedTextureSize: Vec2i,
        expressions: MutableList<SkeletalExpressionBinding>,
        fallbackName: String,
        depth: Int,
        budget: ParseBudget,
    ): SkeletalBone {
        budget.enter(source, path, depth)
        if (!node.isObject) fail(source, path, "Expected a CEM part object.")

        val name = node["id"]?.textOrNull() ?: node["part"]?.textOrNull() ?: fallbackName
        val textureSize = node.vector2i(source, "$path.textureSize", "textureSize", inheritedTextureSize)
        val texture = node["texture"]?.textOrNull()
        val boxes = node["boxes"]?.let { boxArray ->
            if (!boxArray.isArray) fail(source, "$path.boxes", "Expected an array.")
            boxArray.mapIndexed { index, box -> parseBox(source, box, "$path.boxes[$index]", textureSize, texture) }
        } ?: emptyList()

        node["animations"]?.let { animations ->
            if (!animations.isArray) fail(source, "$path.animations", "Expected an array.")
            animations.forEachIndexed { index, animation ->
                if (!animation.isObject) fail(source, "$path.animations[$index]", "Expected an expression object.")
                animation.properties().forEach { (target, expression) ->
                    if (!expression.isTextual) fail(source, "$path.animations[$index].$target", "Expected an expression string.")
                    try {
                        expressions += SkeletalExpressionBinding(name, target, expression.asText())
                    } catch (error: IllegalArgumentException) {
                        throw SkeletalContentParseException(source, "$path.animations[$index].$target", error.message ?: "Invalid expression.", error)
                    }
                }
            }
        }

        val childNodes = mutableListOf<Pair<String, JsonNode>>()
        node["submodel"]?.let { childNodes += "submodel" to it }
        node["submodels"]?.let { submodels ->
            if (!submodels.isArray) fail(source, "$path.submodels", "Expected an array.")
            submodels.forEachIndexed { index, child -> childNodes += "submodels[$index]" to child }
        }
        val children = childNodes.mapIndexed { index, (childPath, child) ->
            parsePart(
                source,
                resolvePart(source, child, "$path.$childPath"),
                "$path.$childPath",
                textureSize,
                expressions,
                "${name}_$index",
                depth + 1,
                budget,
            )
        }

        val scale = node["scale"]?.numberOrNull()?.toFloat() ?: 1.0f
        return SkeletalBone(
            name = name,
            target = node["part"]?.textOrNull(),
            attach = node["attach"]?.booleanValue() ?: false,
            pivot = node.vector3(source, "$path.translate", "translate", Vec3f.EMPTY),
            rotation = node.vector3(source, "$path.rotate", "rotate", Vec3f.EMPTY),
            scale = Vec3f(scale),
            invertAxes = node["invertAxis"]?.textOrNull().orEmpty().mapNotNullTo(linkedSetOf()) {
                when (it.lowercaseChar()) {
                    'x' -> Axes.X
                    'y' -> Axes.Y
                    'z' -> Axes.Z
                    else -> null
                }
            },
            mirrorTextureU = 'u' in node["mirrorTexture"]?.textOrNull().orEmpty().lowercase(),
            mirrorTextureV = 'v' in node["mirrorTexture"]?.textOrNull().orEmpty().lowercase(),
            cubes = boxes,
            children = children,
        )
    }

    private fun parseBox(source: ResourceLocation, node: JsonNode, path: String, textureSize: Vec2i, material: String?): SkeletalCube {
        if (!node.isObject) fail(source, path, "Expected a CEM box object.")
        val coordinates = node["coordinates"] ?: fail(source, "$path.coordinates", "Missing box coordinates.")
        if (!coordinates.isArray || coordinates.size() != 6 || coordinates.any { !it.isNumber }) {
            fail(source, "$path.coordinates", "Expected six numeric coordinates.")
        }
        val origin = Vec3f(coordinates[0].floatValue(), coordinates[1].floatValue(), coordinates[2].floatValue())
        val size = Vec3f(coordinates[3].floatValue(), coordinates[4].floatValue(), coordinates[5].floatValue())
        val commonInflate = node["sizeAdd"]?.numberOrNull()?.toFloat() ?: 0.0f
        val inflate = Vec3f(
            commonInflate + (node["sizeAddX"]?.numberOrNull()?.toFloat() ?: 0.0f),
            commonInflate + (node["sizeAddY"]?.numberOrNull()?.toFloat() ?: 0.0f),
            commonInflate + (node["sizeAddZ"]?.numberOrNull()?.toFloat() ?: 0.0f),
        )

        val textureOffset = node["textureOffset"]
        val uv = if (textureOffset != null) {
            if (!textureOffset.isArray || textureOffset.size() != 2 || textureOffset.any { !it.isNumber }) {
                fail(source, "$path.textureOffset", "Expected two numeric UV coordinates.")
            }
            SkeletalUv.Box(Vec2f(textureOffset[0].floatValue(), textureOffset[1].floatValue()))
        } else {
            val faces = linkedMapOf<Directions, SkeletalFaceUv>()
            readFace(source, node, path, Directions.DOWN, material, "uvDown")?.let { faces[Directions.DOWN] = it }
            readFace(source, node, path, Directions.UP, material, "uvUp")?.let { faces[Directions.UP] = it }
            readFace(source, node, path, Directions.NORTH, material, "uvNorth", "uvFront")?.let { faces[Directions.NORTH] = it }
            readFace(source, node, path, Directions.SOUTH, material, "uvSouth", "uvBack")?.let { faces[Directions.SOUTH] = it }
            readFace(source, node, path, Directions.WEST, material, "uvWest", "uvLeft")?.let { faces[Directions.WEST] = it }
            readFace(source, node, path, Directions.EAST, material, "uvEast", "uvRight")?.let { faces[Directions.EAST] = it }
            if (faces.isEmpty()) fail(source, path, "Box requires textureOffset or per-face UV coordinates.")
            SkeletalUv.Faces(faces)
        }

        return SkeletalCube(origin = origin, size = size, inflate = inflate, material = material, uv = uv)
    }

    private fun readFace(
        source: ResourceLocation,
        node: JsonNode,
        path: String,
        direction: Directions,
        material: String?,
        vararg names: String,
    ): SkeletalFaceUv? {
        val name = names.firstOrNull { node[it] != null } ?: return null
        val value = node[name]
        if (!value.isArray || value.size() != 4 || value.any { !it.isNumber }) {
            fail(source, "$path.$name", "Expected four numeric UV coordinates.")
        }
        val start = Vec2f(value[0].floatValue(), value[1].floatValue())
        val end = Vec2f(value[2].floatValue(), value[3].floatValue())
        return SkeletalFaceUv(start, end - start, material = material)
    }

    private fun read(source: ResourceLocation, input: InputStream, path: String): JsonNode {
        return try {
            val bytes = input.use { it.readNBytes(MAX_JSON_BYTES + 1) }
            if (bytes.size > MAX_JSON_BYTES) fail(source, path, "JSON exceeds $MAX_JSON_BYTES bytes.")
            Jackson.MAPPER.readTree(bytes)
        } catch (error: Exception) {
            throw SkeletalContentParseException(source, path, "Invalid JSON: ${error.message}", error)
        } ?: fail(source, path, "Empty JSON document.")
    }

    private fun JsonNode.vector2i(source: ResourceLocation, path: String, field: String, default: Vec2i): Vec2i {
        val value = this[field] ?: return default
        if (!value.isArray || value.size() != 2 || value.any { !it.isNumber }) fail(source, path, "Expected two integers.")
        return Vec2i(value[0].intValue(), value[1].intValue())
    }

    private fun JsonNode.vector3(source: ResourceLocation, path: String, field: String, default: Vec3f): Vec3f {
        val value = this[field] ?: return default
        if (!value.isArray || value.size() != 3 || value.any { !it.isNumber }) fail(source, path, "Expected three numbers.")
        return Vec3f(value[0].floatValue(), value[1].floatValue(), value[2].floatValue())
    }

    private fun JsonNode?.textOrNull() = this?.takeIf(JsonNode::isTextual)?.asText()
    private fun JsonNode?.numberOrNull() = this?.takeIf(JsonNode::isNumber)?.numberValue()

    private fun fail(source: ResourceLocation, path: String, message: String): Nothing {
        throw SkeletalContentParseException(source, path, message)
    }

    private class ParseBudget {
        private var parts = 0

        fun enter(source: ResourceLocation, path: String, depth: Int) {
            if (depth > MAX_PART_DEPTH) {
                throw SkeletalContentParseException(source, path, "CEM hierarchy exceeds $MAX_PART_DEPTH levels.")
            }
            if (++parts > MAX_PARTS) {
                throw SkeletalContentParseException(source, path, "CEM model exceeds $MAX_PARTS parts.")
            }
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
        const val MAX_PART_DEPTH = 64
        const val MAX_PARTS = 65_536
    }
}
