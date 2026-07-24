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

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.util.jar.JarInputStream
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.toList

enum class FabricCompatibilityBlocker {
    ACTIVATION_ADAPTER,
    ACCESS_WIDENER,
    DEPENDENCY_RESOLUTION,
    ENTRYPOINT_LINKAGE,
    MIXINS,
    NESTED_JARS,
}

data class FabricModProbe(
    val metadata: FabricMetadata,
    val blockers: Set<FabricCompatibilityBlocker>,
    val adapter: FabricCompatibilityAdapter?,
    val nestedMods: List<FabricMetadata> = emptyList(),
    val dependencyIssues: List<String> = emptyList(),
) {
    val activatable: Boolean get() = blockers.isEmpty()
    val functionality: List<FabricFunctionality> get() = adapter?.functionality ?: FabricFunctionalityCatalog.known(metadata)
    val activation: FabricActivationMode get() = when {
        !activatable -> FabricActivationMode.BLOCKED
        adapter != null -> FabricActivationMode.ADAPTED
        else -> FabricActivationMode.DIRECT
    }
}

enum class FabricActivationMode {
    BLOCKED,
    DIRECT,
    ADAPTED,
    PARTIAL,
}

data class FabricPackReport(
    val root: Path,
    val pack: FabricMetadata,
    val mods: List<FabricModProbe>,
) {
    val activatable: Boolean get() = mods.all(FabricModProbe::activatable)
    val activatableMods: List<FabricModProbe> get() = mods.filter(FabricModProbe::activatable)
    val blockedMods: List<FabricModProbe> get() = mods.filterNot(FabricModProbe::activatable)
    val launchable: Boolean get() = activatableMods.isNotEmpty()
    val activation: FabricActivationMode get() = when {
        !launchable -> FabricActivationMode.BLOCKED
        blockedMods.isNotEmpty() -> FabricActivationMode.PARTIAL
        mods.any { it.activation == FabricActivationMode.ADAPTED } -> FabricActivationMode.ADAPTED
        else -> FabricActivationMode.DIRECT
    }

    fun log() {
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "Fabric pack ${pack.name} (${pack.id} ${pack.version}) discovered ${mods.size} staged mod(s)."
        }
        for (probe in mods) {
            val metadata = probe.metadata
            if (probe.activation == FabricActivationMode.ADAPTED) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                    "Fabric mod ${metadata.id} ${metadata.version} is activation-ready through adapter ${probe.adapter!!.id}."
                }
            } else if (probe.activatable) {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) { "Fabric metadata ready for direct activation: ${metadata.id} ${metadata.version}" }
            } else {
                Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                    "Fabric mod ${metadata.id} ${metadata.version} is metadata-only; blockers=${probe.blockers.joinToString()}"
                }
            }
        }
        if (!launchable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "Fabric pack ${pack.id} was preflighted but not activated. The current ladder never passes blocked mods to the native Minosoft loader."
            }
        } else if (!activatable) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.WARN) {
                "Fabric pack ${pack.id} passed partial activation preflight: ${activatableMods.size} adapted mod(s), ${blockedMods.size} catalog-only blocked mod(s)."
            }
        } else {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "Fabric pack ${pack.id} passed activation preflight in ${activation.name.lowercase()} mode."
            }
        }
    }
}

object FabricPackPreflight {
    private const val FABRIC_METADATA = "fabric.mod.json"
    internal const val MAX_NESTED_JAR_BYTES = 32 * 1024 * 1024
    internal const val MAX_TOTAL_NESTED_BYTES = 128L * 1024 * 1024
    internal const val MAX_NESTED_DEPTH = 8
    internal const val MAX_NESTED_MODS = 256

    fun inspect(root: Path): FabricPackReport {
        require(root.isDirectory()) { "Fabric pack root is not a directory: $root" }
        val packMetadata = root.resolve("metadata").resolve(FABRIC_METADATA)
        require(packMetadata.isRegularFile()) { "Fabric pack entry is missing: $packMetadata" }
        val pack = Files.newInputStream(packMetadata).use { FabricMetadataReader.read(it, packMetadata.toString()) }

        val modsDirectory = root.resolve("mods")
        require(modsDirectory.isDirectory()) { "Fabric pack mods directory is missing: $modsDirectory" }
        val jars = Files.list(modsDirectory).use { paths ->
            paths.filter { it.isRegularFile() && it.extension.equals("jar", ignoreCase = true) }
                .sorted()
                .toList()
        }
        val probes = resolveDependencies(jars.map(::inspectJar))
        require(probes.map { it.metadata.id }.distinct().size == probes.size) { "Fabric pack contains duplicate mod ids." }
        return FabricPackReport(root, pack, orderForActivation(probes))
    }

    private fun inspectJar(path: Path): FabricModProbe {
        val (metadata, nestedMods) = JarFile(path.toFile()).use { jar ->
            val entry = jar.getJarEntry(FABRIC_METADATA) ?: throw IllegalArgumentException("Fabric mod has no $FABRIC_METADATA: $path")
            val metadata = jar.getInputStream(entry).use { FabricMetadataReader.read(it, path.toString()) }
            val budget = NestedBudget()
            val nested = metadata.nestedJarPaths.flatMap { nestedPath ->
                val nestedEntry = requireNotNull(jar.getJarEntry(nestedPath)) { "Fabric nested JAR is missing: $path!/$nestedPath" }
                val source = "$path!/$nestedPath"
                val bytes = jar.getInputStream(nestedEntry).use { readBounded(it, MAX_NESTED_JAR_BYTES, source) }
                budget.consume(bytes.size, source)
                readNestedJar(bytes, source, 1, budget)
            }
            metadata to nested
        }
        val declaredBlockers = buildSet {
            add(FabricCompatibilityBlocker.ACTIVATION_ADAPTER)
            if (metadata.entrypoints.isNotEmpty()) add(FabricCompatibilityBlocker.ENTRYPOINT_LINKAGE)
            if (metadata.mixins > 0) add(FabricCompatibilityBlocker.MIXINS)
            if (metadata.accessWidener != null) add(FabricCompatibilityBlocker.ACCESS_WIDENER)
            if (metadata.nestedJars > 0) add(FabricCompatibilityBlocker.NESTED_JARS)
        }
        val adapter = FabricCompatibilityAdapters.resolve(metadata)
        val blockers = declaredBlockers - (adapter?.handledBlockers ?: emptySet())
        return FabricModProbe(metadata, blockers, adapter, nestedMods)
    }

    private fun readNestedJar(bytes: ByteArray, source: String, depth: Int, budget: NestedBudget): List<FabricMetadata> {
        budget.enter(depth, source)
        val entries = linkedMapOf<String, ByteArray>()
        JarInputStream(ByteArrayInputStream(bytes)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (!entry.isDirectory && (entry.name == FABRIC_METADATA || entry.name.endsWith(".jar"))) {
                    require(entries.size < MAX_NESTED_MODS + 1) { "Fabric nested mod has too many relevant entries: $source" }
                    val entrySource = "$source!/${entry.name}"
                    val contents = readBounded(
                        jar,
                        if (entry.name == FABRIC_METADATA) FabricMetadataReader.MAX_METADATA_BYTES else MAX_NESTED_JAR_BYTES,
                        entrySource,
                    )
                    budget.consume(contents.size, entrySource)
                    entries[entry.name] = contents
                }
            }
        }
        val metadataBytes = requireNotNull(entries[FABRIC_METADATA]) { "Fabric nested mod has no $FABRIC_METADATA: $source" }
        val metadata = FabricMetadataReader.read(ByteArrayInputStream(metadataBytes), source)
        val children = metadata.nestedJarPaths.flatMap { path ->
            val child = requireNotNull(entries[path]) { "Fabric nested JAR is missing: $source!/$path" }
            readNestedJar(child, "$source!/$path", depth + 1, budget)
        }
        return listOf(metadata) + children
    }

    private fun readBounded(input: java.io.InputStream, limit: Int, source: String): ByteArray {
        val bytes = input.readNBytes(limit + 1)
        require(bytes.size <= limit) { "Fabric nested entry exceeds the $limit byte limit: $source" }
        return bytes
    }

    private class NestedBudget {
        private var mods = 0
        private var bytes = 0L

        fun enter(depth: Int, source: String) {
            require(depth <= MAX_NESTED_DEPTH) { "Fabric nested JAR depth exceeds $MAX_NESTED_DEPTH: $source" }
            require(++mods <= MAX_NESTED_MODS) { "Fabric nested mod count exceeds $MAX_NESTED_MODS: $source" }
        }

        fun consume(count: Int, source: String) {
            bytes += count
            require(bytes <= MAX_TOTAL_NESTED_BYTES) {
                "Fabric nested entries exceed the $MAX_TOTAL_NESTED_BYTES byte aggregate limit: $source"
            }
        }
    }

    private fun resolveDependencies(probes: List<FabricModProbe>): List<FabricModProbe> {
        val providers = linkedMapOf<String, MutableList<FabricMetadata>>()
        for (metadata in probes.flatMap { listOf(it.metadata) + it.nestedMods }) {
            providers.getOrPut(metadata.id) { mutableListOf() } += metadata
            for (provided in metadata.provides) providers.getOrPut(provided) { mutableListOf() } += metadata
        }
        return probes.map { probe ->
            val issues = (listOf(probe.metadata) + probe.nestedMods).flatMap { validateDependencies(it, providers) }.distinct()
            if (issues.isEmpty()) probe else probe.copy(
                blockers = probe.blockers + FabricCompatibilityBlocker.DEPENDENCY_RESOLUTION,
                dependencyIssues = issues,
            )
        }
    }

    private fun validateDependencies(
        metadata: FabricMetadata,
        providers: Map<String, List<FabricMetadata>>,
    ): List<String> = buildList {
        for (dependency in metadata.dependencies["depends"].orEmpty()) {
            if (dependency.id in PLATFORM_DEPENDENCIES) continue
            val candidates = providers[dependency.id].orEmpty()
            if (candidates.isEmpty()) {
                add("${metadata.id} requires missing ${dependency.id} ${dependency.predicates.joinToString(" || ")}")
            } else if (candidates.none { dependency.matches(it.version) }) {
                add("${metadata.id} requires ${dependency.id} ${dependency.predicates.joinToString(" || ")}, found ${candidates.joinToString { it.version }}")
            }
        }
        for (key in listOf("breaks", "conflicts")) {
            for (dependency in metadata.dependencies[key].orEmpty()) {
                val matching = providers[dependency.id].orEmpty().filter { dependency.matches(it.version) }
                if (matching.isNotEmpty()) add("${metadata.id} $key ${dependency.id} ${matching.joinToString { it.version }}")
            }
        }
    }

    private fun FabricDependency.matches(version: String): Boolean {
        if (predicates.isEmpty()) return true
        val parsed = net.fabricmc.loader.api.Version.parse(version)
        return predicates.any { net.fabricmc.loader.api.metadata.version.VersionPredicate.parse(it).test(parsed) }
    }

    private fun orderForActivation(probes: List<FabricModProbe>): List<FabricModProbe> {
        val byId = probes.associateBy { it.metadata.id }
        val remaining = probes.associate { probe ->
            probe.metadata.id to probe.metadata.dependencies["depends"].orEmpty().map { it.id }.filterTo(mutableSetOf()) { it in byId }
        }.toMutableMap()
        val ordered = mutableListOf<FabricModProbe>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues { it.isEmpty() }.keys.sorted()
            if (ready.isEmpty()) return probes.map { probe ->
                probe.copy(
                    blockers = probe.blockers + FabricCompatibilityBlocker.DEPENDENCY_RESOLUTION,
                    dependencyIssues = probe.dependencyIssues + "Top-level dependency cycle: ${remaining.keys.sorted().joinToString()}",
                )
            }
            for (id in ready) {
                remaining.remove(id)
                ordered += byId.getValue(id)
                remaining.values.forEach { it -= id }
            }
        }
        return ordered
    }

    private val PLATFORM_DEPENDENCIES = setOf("fabricloader", "java", "minecraft")
}
