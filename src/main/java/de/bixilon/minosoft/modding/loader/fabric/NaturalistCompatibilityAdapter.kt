/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.assets.ExternalAssetProviders
import de.bixilon.minosoft.assets.file.ZipAssetsManager
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationPredicate
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerDecision
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft

object NaturalistCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:naturalist-5.0.0-pre.4-fabric-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.SKELETAL_CONTENT)
    override val functionality = FabricFunctionalityCatalog.NATURALIST

    override fun supports(metadata: FabricMetadata): Boolean =
        metadata.id == "naturalist" &&
            metadata.version == "5.0.0-pre.4" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("main", "client") &&
            metadata.mixins == 2 &&
            metadata.accessWidener == null &&
            metadata.nestedJarPaths.toSet() == NESTED_JARS

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Naturalist artifact: ${probe.metadata.version}" }
        scope.own(
            ExternalAssetProviders.register(id) {
                NaturalistAssetsManager(probe.metadata.source)
            },
        )
        for (model in MODELS.values.distinct()) {
            scope.own(GeckoLibControllerBindingRegistry.register(id, model.identity, ::controller))
        }
        val modelOptions = NaturalistModelOptions()
        scope.own(modelOptions)
        scope.own(
            FabricSettings.register(id, minosoft("naturalist_options"), "Naturalist settings") {
                modelOptions.schema()
            },
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "NATURALIST_CONTENT_ACTIVE version=${probe.metadata.version} entityRoutes=${MODELS.size} geometries=${MODELS.values.distinct().size} upstreamGameplay=false"
        }
    }

    private fun controller(context: de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingContext): List<GeckoLibControllerDefinition> {
        val clips = context.animations.keys.sorted()
        if (clips.isEmpty()) return listOf(GeckoLibControllerDefinition("naturalist"))
        val idle = clips.firstOrNull { it.endsWith(".idle") } ?: clips.first()
        val movement = MOVEMENT_SUFFIXES.firstNotNullOfOrNull { suffix ->
            clips.firstOrNull { it.endsWith(".$suffix") }
        } ?: idle
        return listOf(
            GeckoLibControllerDefinition(
                name = "naturalist",
                initialClip = idle,
                transitionSeconds = 0.15f,
                predicate = GeckoLibAnimationPredicate { state, current ->
                    val desired = if (state.moving) movement else idle
                    if (current == desired) GeckoLibControllerDecision.Keep else GeckoLibControllerDecision.Play(desired)
                },
            ),
        )
    }

    private fun includeArtifactEntry(entry: String): Boolean {
        if (entry.startsWith("assets/naturalist/animations/0_old/")) return false
        val model = when {
            entry.startsWith(GEOMETRY_PREFIX) && entry.endsWith(".geo.json") ->
                entry.removePrefix(GEOMETRY_PREFIX).removeSuffix(".geo.json")
            entry.startsWith(ANIMATION_PREFIX) && entry.endsWith(".rp_anim.json") ->
                entry.removePrefix(ANIMATION_PREFIX).removeSuffix(".rp_anim.json")
            else -> return true
        }
        return model in SUPPORTED_MODELS ||
            entry.startsWith(GEOMETRY_PREFIX) && model == ZEBRA_GEOMETRY_SOURCE
    }

    private fun naturalist(path: String) = ResourceLocation("naturalist", path)

    private class NaturalistModelOptions : AutoCloseable {
        private val defaults = MODELS.keys.associate { "removed.$it" to "false" }
        private val store = FabricAdapterOptionStore("naturalist", defaults)
        private val registrations = linkedMapOf<String, AutoCloseable>()
        private var closed = false

        init {
            for ((entity, model) in MODELS) {
                if (!removed(entity)) {
                    registrations[entity] = GeckoLibEntityModelRegistry.register(id, naturalist(entity), model.identity)
                }
            }
        }

        fun schema(): SettingsSchema = SettingsSchema(
            title = "Naturalist settings",
            entries = MODELS.keys.sorted().map { entity ->
                ConfigEntry(
                    id = "remove_$entity",
                    label = "Remove ${entity.replace('_', ' ')}",
                    description = "Disables Naturalist's source-native client model route for this entity.",
                    defaultValue = false,
                    control = BooleanConfigControl,
                    read = { removed(entity) },
                    write = { setRemoved(entity, it) },
                    category = "mob_removal",
                )
            },
            categories = listOf(
                SettingsCategory(
                    "mob_removal",
                    "Mob removal",
                    "Client model-route equivalents of Naturalist's MidnightLib removal values.",
                ),
            ),
            persist = store::persist,
        )

        private fun removed(entity: String): Boolean = store.boolean("removed.$entity")

        @Synchronized
        private fun setRemoved(entity: String, removed: Boolean) {
            check(!closed) { "Naturalist model options are closed." }
            val model = requireNotNull(MODELS[entity]) { "Unknown Naturalist entity: $entity" }
            store.set("removed.$entity", removed)
            if (removed) {
                registrations.remove(entity)?.close()
            } else if (entity !in registrations) {
                registrations[entity] = GeckoLibEntityModelRegistry.register(id, naturalist(entity), model.identity)
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            registrations.values.toList().asReversed().forEach(AutoCloseable::close)
            registrations.clear()
        }
    }

    private class NaturalistAssetsManager(path: String) :
        ZipAssetsManager(path, entryFilter = ::includeArtifactEntry) {

        override fun load(latch: AbstractLatch?) {
            super.load(latch)
            alias("geo/entity/ostrich.geo.json", "geo/entity/zebra.geo.json")
            assets.remove(naturalist("geo/entity/ostrich.geo.json"))
            alias("textures/entity/lizard/beardie.png", "textures/entity/lizard.png")
            alias("textures/entity/zebra.png", "textures/entity/ostrich.png")
            alias("textures/entity/caterpillar.png", "textures/__content/geometry.unknown.png")
        }

        private fun alias(source: String, target: String) {
            assets[naturalist(source)]?.let { assets[naturalist(target)] = it }
        }
    }

    private data class Model(val path: String, val identifier: String) {
        val identity = SkeletalContentIdentity(
            ResourceLocation("naturalist", "geo/entity/$path.geo.json"),
            SkeletalContentFormat.GECKOLIB,
            identifier,
        )
    }

    private val MODEL_DEFINITIONS = listOf(
        Model("alligator", "geometry.sf_nba.alligator"),
        Model("bass", "geometry.sf_nba.bass"),
        Model("bear", "geometry.sf_nba.bear"),
        Model("bird", "geometry.sf_nba.bird"),
        Model("boar", "geometry.sf_nba.boar"),
        Model("butterfly", "geometry.sf_nba.butterfly"),
        Model("caterpillar", "geometry.unknown"),
        Model("catfish", "geometry.Catfish"),
        Model("deer", "geometry.sf_nba.deer"),
        Model("dragonfly", "geometry.dragonfly"),
        Model("duck", "geometry.sf_nba.duck"),
        Model("elephant", "geometry.sf_nba.elephant"),
        Model("firefly", "geometry.sf_nba.firefly"),
        Model("giraffe", "geometry.sf_nba.giraffe"),
        Model("hippo", "geometry.sf_nba.hippo"),
        Model("lion", "geometry.sf_nba.lion"),
        Model("lizard", "geometry.unknown"),
        Model("moose", "geometry.sf_nba.moose"),
        Model("rhino", "geometry.sf_nba.rhino"),
        Model("snail", "geometry.snail"),
        Model("snake", "geometry.snake"),
        Model("tortoise", "geometry.sf_nba.tortoise"),
        Model("vulture", "geometry.sf_nba.vulture"),
        Model("zebra", "geometry.sf_nba.ostrich"),
    ).associateBy(Model::path)

    private fun model(path: String) = MODEL_DEFINITIONS.getValue(path)

    private val MODELS = linkedMapOf(
        "alligator" to model("alligator"),
        "bass" to model("bass"),
        "bear" to model("bear"),
        "bluejay" to model("bird"),
        "boar" to model("boar"),
        "butterfly" to model("butterfly"),
        "canary" to model("bird"),
        "cardinal" to model("bird"),
        "caterpillar" to model("caterpillar"),
        "catfish" to model("catfish"),
        "coral_snake" to model("snake"),
        "deer" to model("deer"),
        "dragonfly" to model("dragonfly"),
        "duck" to model("duck"),
        "elephant" to model("elephant"),
        "finch" to model("bird"),
        "firefly" to model("firefly"),
        "giraffe" to model("giraffe"),
        "hippo" to model("hippo"),
        "lion" to model("lion"),
        "lizard" to model("lizard"),
        "moose" to model("moose"),
        "rattlesnake" to model("snake"),
        "rhino" to model("rhino"),
        "robin" to model("bird"),
        "snail" to model("snail"),
        "snake" to model("snake"),
        "sparrow" to model("bird"),
        "tortoise" to model("tortoise"),
        "vulture" to model("vulture"),
        "zebra" to model("zebra"),
    )
    private val SUPPORTED_MODELS = MODEL_DEFINITIONS.keys
    private val MOVEMENT_SUFFIXES = listOf("walk", "move", "swim", "fly", "crawl", "run", "flop")
    private const val GEOMETRY_PREFIX = "assets/naturalist/geo/entity/"
    private const val ANIMATION_PREFIX = "assets/naturalist/animations/"
    private const val ZEBRA_GEOMETRY_SOURCE = "ostrich"
    private val NESTED_JARS = setOf(
        "META-INF/jars/midnightlib-1.5.3-fabric.jar",
        "META-INF/jars/cloth-config-fabric-13.0.138-fabric.jar",
    )
}
