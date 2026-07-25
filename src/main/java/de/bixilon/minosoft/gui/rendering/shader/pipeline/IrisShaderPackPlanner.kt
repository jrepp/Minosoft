/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

package de.bixilon.minosoft.gui.rendering.shader.pipeline

import de.bixilon.minosoft.gui.rendering.graph.RenderOwnerId
import de.bixilon.minosoft.gui.rendering.graph.RenderViewId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderClearPolicy
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorAttachment
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderColorFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderDepthFormat
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourceId
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderResourcePlan
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetDescriptor
import de.bixilon.minosoft.gui.rendering.graph.resource.RenderTargetSize
import de.bixilon.minosoft.gui.rendering.graph.resource.VertexSemantic
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory

object IrisShaderPackPlanner {
    private val INCLUDE = Regex("""(?m)^\s*#include\s+[<"]([^>"]+)[>"]\s*$""")
    private val UNIFORM = Regex("""\buniform\s+\w+\s+([A-Za-z_][A-Za-z0-9_]*)\s*;""")
    private val SAMPLER = Regex("""\buniform\s+sampler\w*\s+([A-Za-z_][A-Za-z0-9_]*)\s*;""")
    private val OPTION = Regex("""(?m)^(\s*)(//\s*)?#define\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+([^\s/]+))?\s*(?://\s*\[([^\]]+)])?\s*$""")
    private const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 1024
    private const val MAX_PROGRAMS = 128
    private const val MAX_INCLUDE_DEPTH = 32
    val OWNER = RenderOwnerId("minosoft:iris-1.7.2-shader-pipeline")
    val SHADOW_VIEW = RenderViewId("minosoft:shadow")

    fun plan(path: Path, overrides: Map<String, String> = emptyMap()): ShaderPipelinePlan {
        val sourceFiles = readPack(path)
        val declaredOptions = options(path).associateBy(ShaderPackOption::name)
        require(overrides.keys.all(declaredOptions::containsKey)) { "Shader pack override references an unknown option." }
        for ((name, value) in overrides) {
            require(value in declaredOptions.getValue(name).values) { "Invalid value '$value' for shader option $name." }
        }
        val files = sourceFiles.mapValues { (_, source) -> applyOptions(source, overrides, declaredOptions) }
        val roots = files.keys.filter { it.endsWith(".vsh") || it.endsWith(".fsh") }
            .map { it.substringBeforeLast('.') }
            .distinct()
            .sorted()
        require(roots.size <= MAX_PROGRAMS) { "Shader pack declares too many program roots: ${roots.size}" }

        val programs = roots.mapNotNull { root ->
            val vertexPath = "$root.vsh"
            val fragmentPath = "$root.fsh"
            val vertex = files[vertexPath] ?: return@mapNotNull null
            val fragment = files[fragmentPath] ?: return@mapNotNull null
            val resolvedVertex = resolve(vertexPath, vertex, files, linkedSetOf(), 0)
            val resolvedFragment = resolve(fragmentPath, fragment, files, linkedSetOf(), 0)
            val combined = "$resolvedVertex\n$resolvedFragment"
            ShaderProgramSource(
                name = root.substringAfterLast('/'),
                phase = phase(root.substringAfterLast('/')),
                vertex = resolvedVertex,
                fragment = resolvedFragment,
                uniforms = UNIFORM.findAll(combined).map { it.groupValues[1] }.toSet(),
                samplers = SAMPLER.findAll(combined).map { it.groupValues[1] }.toSet(),
            )
        }
        require(programs.any { it.phase == ShaderProgramPhase.TERRAIN }) {
            "Shader pack has no paired gbuffers_terrain program"
        }
        require(programs.any { it.phase == ShaderProgramPhase.COMPOSITE || it.phase == ShaderProgramPhase.FINAL }) {
            "Shader pack has no paired composite or final program"
        }

        val shadow = programs.any { it.phase == ShaderProgramPhase.SHADOW }
        val views = buildSet {
            add(RenderViewId.MAIN)
            if (shadow) add(SHADOW_VIEW)
        }
        return ShaderPipelinePlan(
            owner = OWNER,
            packName = path.fileName.toString(),
            fingerprint = fingerprint(files),
            views = views,
            resources = resources(shadow),
            programs = programs,
            requiredTerrainSemantics = requiredSemantics(programs),
        )
    }

    fun options(path: Path): List<ShaderPackOption> {
        val options = linkedMapOf<String, ShaderPackOption>()
        for (source in readPack(path).values) {
            for (match in OPTION.findAll(source)) {
                val commented = match.groupValues[2].isNotEmpty()
                val name = match.groupValues[3]
                val value = match.groupValues[4]
                val declared = match.groupValues[5].trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                val option = when {
                    declared.isNotEmpty() && value.isNotEmpty() -> ShaderPackOption(name, value, declared)
                    value.isEmpty() -> ShaderPackOption(name, (!commented).toString(), listOf("false", "true"))
                    else -> continue
                }
                require(option.values.size <= MAX_OPTION_VALUES) { "Shader option $name declares too many values." }
                require(option.defaultValue in option.values) { "Shader option $name default is not in its declared values." }
                val previous = options.putIfAbsent(name, option)
                require(previous == null || previous == option) { "Shader option $name is declared inconsistently." }
                require(options.size <= MAX_OPTIONS) { "Shader pack exceeds $MAX_OPTIONS options." }
            }
        }
        return options.values.sortedBy(ShaderPackOption::name)
    }

    private fun applyOptions(
        source: String,
        overrides: Map<String, String>,
        options: Map<String, ShaderPackOption>,
    ): String = OPTION.replace(source) { match ->
        val name = match.groupValues[3]
        val option = options[name] ?: return@replace match.value
        val value = overrides[name] ?: option.defaultValue
        val indent = match.groupValues[1]
        if (option.values == BOOLEAN_VALUES) {
            "$indent${if (value == "true") "" else "//"}#define $name"
        } else {
            val values = option.values.joinToString(" ")
            "$indent#define $name $value // [$values]"
        }
    }

    private fun readPack(path: Path): Map<String, String> {
        val bytes = linkedMapOf<String, ByteArray>()
        var totalBytes = 0

        fun add(relative: String, source: ByteArray) {
            require(relative.isNotBlank() && relative != "." && relative != ".." && !relative.startsWith("../") && !relative.startsWith('/')) {
                "Shader-pack entry escapes the shaders directory: $relative"
            }
            require(bytes.size < MAX_FILES) { "Shader pack exceeds $MAX_FILES files" }
            require(relative !in bytes) { "Shader pack contains duplicate entry: $relative" }
            totalBytes = Math.addExact(totalBytes, source.size)
            require(totalBytes <= MAX_TOTAL_BYTES) { "Shader pack exceeds $MAX_TOTAL_BYTES bytes" }
            bytes[relative] = source
        }

        if (path.isDirectory()) {
            val shaders = path.resolve("shaders")
            require(shaders.isDirectory()) { "Shader pack has no shaders directory: $path" }
            Files.walk(shaders).use { paths ->
                paths.filter(Files::isRegularFile).sorted().forEach { source ->
                    val relative = normalize(shaders.relativize(source).toString())
                    val content = Files.newInputStream(source).use { it.readNBytes(MAX_SOURCE_BYTES + 1) }
                    add(relative, readBounded(content, relative))
                }
            }
        } else {
            ZipFile(path.toFile()).use { zip ->
                val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                val shaderEntries = entries.filter { normalize(it.name).contains("shaders/") }
                require(shaderEntries.isNotEmpty()) { "Shader-pack archive has no shaders directory: $path" }
                val marker = shaderEntries.minOf { normalize(it.name).substringBefore("shaders/").length }
                shaderEntries.sortedBy { it.name }.forEach { entry ->
                    val normalized = normalize(entry.name)
                    val shadersIndex = normalized.indexOf("shaders/", marker)
                    require(shadersIndex >= 0) { "Shader-pack entry has an inconsistent shaders root: ${entry.name}" }
                    val relative = normalized.substring(shadersIndex + "shaders/".length)
                    val content = zip.getInputStream(entry).use { input ->
                        readBounded(input.readNBytes(MAX_SOURCE_BYTES + 1), relative)
                    }
                    add(relative, content)
                }
            }
        }
        return bytes.mapValues { (_, value) -> value.toString(StandardCharsets.UTF_8) }
    }

    private fun readBounded(bytes: ByteArray, source: String): ByteArray {
        require(bytes.size <= MAX_SOURCE_BYTES) { "Shader source exceeds $MAX_SOURCE_BYTES bytes: $source" }
        return bytes
    }

    private fun resolve(
        sourcePath: String,
        source: String,
        files: Map<String, String>,
        stack: MutableSet<String>,
        depth: Int,
    ): String {
        require(depth <= MAX_INCLUDE_DEPTH) { "Shader include depth exceeds $MAX_INCLUDE_DEPTH at $sourcePath" }
        require(stack.add(sourcePath)) { "Shader include cycle: ${stack.joinToString(" -> ")} -> $sourcePath" }
        try {
            return INCLUDE.replace(source) { match ->
                val requested = match.groupValues[1]
                if (':' in requested) return@replace match.value
                val relative = if (requested.startsWith('/')) {
                    requested.removePrefix("/")
                } else {
                    sourcePath.substringBeforeLast('/', "").let { parent ->
                        if (parent.isEmpty()) requested else "$parent/$requested"
                    }
                }
                val normalized = normalize(relative)
                require(!normalized.startsWith("../") && normalized != "..") {
                    "Shader include escapes pack root: $requested"
                }
                val included = requireNotNull(files[normalized]) {
                    "Shader include is missing: $sourcePath -> $normalized"
                }
                resolve(normalized, included, files, stack, depth + 1)
            }
        } finally {
            stack.remove(sourcePath)
        }
    }

    private fun normalize(path: String): String = Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/')

    private fun phase(name: String): ShaderProgramPhase = when {
        name.startsWith("shadow") -> ShaderProgramPhase.SHADOW
        name.startsWith("gbuffers_terrain") -> ShaderProgramPhase.TERRAIN
        name.startsWith("gbuffers_entities") -> ShaderProgramPhase.ENTITY
        name.startsWith("gbuffers_sky") -> ShaderProgramPhase.SKY
        name.startsWith("gbuffers_weather") -> ShaderProgramPhase.WEATHER
        name.startsWith("composite") -> ShaderProgramPhase.COMPOSITE
        name == "final" -> ShaderProgramPhase.FINAL
        else -> ShaderProgramPhase.COMPOSITE
    }

    private fun requiredSemantics(programs: List<ShaderProgramSource>): Set<VertexSemantic> {
        val terrain = programs.filter { it.phase == ShaderProgramPhase.TERRAIN }.joinToString("\n") { "${it.vertex}\n${it.fragment}" }
        return buildSet {
            add(VertexSemantic.POSITION)
            add(VertexSemantic.TEXTURE_COORDINATE)
            if ("vinLightTint" in terrain) {
                add(VertexSemantic.PACKED_LIGHT_COLOR)
            } else {
                add(VertexSemantic.COLOR)
                add(VertexSemantic.PACKED_LIGHT)
            }
            if ("vinTexture" in terrain) add(VertexSemantic.TEXTURE_LAYER)
            if ("mc_Entity" in terrain) add(VertexSemantic.BLOCK_ID)
            if ("at_midTexCoord" in terrain) add(VertexSemantic.MID_TEXTURE_COORDINATE)
            if ("at_tangent" in terrain) add(VertexSemantic.TANGENT)
        }
    }

    private fun resources(shadow: Boolean): RenderResourcePlan {
        val targets = mutableListOf(
            RenderTargetDescriptor(
                id = RenderResourceId("minosoft:main-world"),
                size = RenderTargetSize.Relative(1.0f),
                colorAttachments = listOf(
                    RenderColorAttachment(RenderResourceId("minosoft:main-world-color"), RenderColorFormat.RGBA8),
                ),
                depth = RenderDepthFormat.DEPTH24,
            ),
        )
        if (shadow) {
            targets += RenderTargetDescriptor(
                id = RenderResourceId("iris:shadow"),
                size = RenderTargetSize.Fixed(1024, 1024),
                colorAttachments = listOf(
                    RenderColorAttachment(
                        RenderResourceId("iris:shadowcolor0"),
                        RenderColorFormat.RGBA8,
                        RenderClearPolicy.CLEAR,
                    ),
                ),
                depth = RenderDepthFormat.DEPTH24,
            )
        }
        return RenderResourcePlan(targets, emptyList())
    }

    private fun fingerprint(files: Map<String, String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.toSortedMap().forEach { (path, source) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            digest.update(source.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val BOOLEAN_VALUES = listOf("false", "true")
    private const val MAX_OPTIONS = 1_024
    private const val MAX_OPTION_VALUES = 256
}

data class ShaderPackOption(
    val name: String,
    val defaultValue: String,
    val values: List<String>,
)
