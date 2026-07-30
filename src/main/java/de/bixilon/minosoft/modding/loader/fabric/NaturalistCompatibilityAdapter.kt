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
import de.bixilon.minosoft.assets.model.skeletal.SkeletalAnimationEventType
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibAnimationPredicate
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerDecision
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityTextureDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityTextureRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityTextureSelector
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostEvents
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateInput
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibHostStateQuery
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRawAnimation
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEffectRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEffectResolution
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRuntimeEventContext
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibTrackedDataInput
import de.bixilon.minosoft.data.entities.EntityAnimations
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
import de.bixilon.minosoft.util.json.Jackson
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

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
            scope.own(
                GeckoLibEntityTextureRegistry.register(
                    id,
                    model.identity,
                    TEXTURES.getValue(model.path),
                ),
            )
        }
        scope.own(
            GeckoLibRuntimeEffectRegistry.register(
                id,
                model("snake").identity,
                ::snakeEffect,
            ),
        )
        scope.own(FabricRemoteRegistrySync.register(id, REMOTE_ENTITIES))
        val modelOptions = NaturalistModelOptions()
        scope.own(modelOptions)
        scope.own(
            FabricSettings.register(id, minosoft("naturalist_options"), "Naturalist settings") {
                modelOptions.schema()
            },
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "NATURALIST_CONTENT_ACTIVE version=${probe.metadata.version} entityRoutes=${MODELS.size} geometries=${MODELS.values.distinct().size} textureResolvers=${TEXTURES.size} remoteEntities=${REMOTE_ENTITIES.size} upstreamGameplay=false"
        }
    }

    private fun controller(context: de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibControllerBindingContext): List<GeckoLibControllerDefinition> {
        val clips = context.animations.keys.sorted()
        if (clips.isEmpty()) return listOf(GeckoLibControllerDefinition("naturalist"))
        if (context.identity.identifier == "geometry.snake") {
            snakeControllers(clips)?.let { return it }
        }
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

    /**
     * Mirrors Naturalist 5.0.0-pre.4's primary snake controller. The upstream
     * class assigns ClimbingAnimal.CLIMB_FLAG before Snake's accessors, so the
     * pinned 1.20.4 wire layout exposes climbing at 17 and sleeping at 19.
     * Tongue and rattle remain independent controllers, with only their exact
     * bounded host queries resolved by the retained entity renderer.
     */
    private fun snakeControllers(clips: List<String>): List<GeckoLibControllerDefinition>? {
        val move = clips.singleOrNull { it == "animation.sf_nba.snake.move" } ?: return null
        val climb = clips.singleOrNull { it == "animation.sf_nba.snake.climb" } ?: return null
        val sleep = clips.singleOrNull { it == "animation.sf_nba.snake.sleep" } ?: return null
        val attack = clips.singleOrNull { it == "animation.sf_nba.snake.attack" } ?: return null
        val tongue = clips.singleOrNull { it == SNAKE_TONGUE_CLIP } ?: return null
        val rattle = clips.singleOrNull { it == SNAKE_RATTLE_CLIP } ?: return null
        val tongueAnimation = GeckoLibRawAnimation.begin().thenPlay(tongue)
        val rattleAnimation = GeckoLibRawAnimation.begin().thenLoop(rattle)
        return listOf(
            GeckoLibControllerDefinition(
                name = "controller",
                transitionSeconds = 0.5f,
                trackedDataInputs = listOf(
                    GeckoLibTrackedDataInput(SNAKE_CLIMBING, 17),
                    GeckoLibTrackedDataInput(SNAKE_SLEEPING, 19),
                ),
                predicate = GeckoLibAnimationPredicate { state, current ->
                    val desired = when {
                        state.data[SNAKE_SLEEPING] == 1.0 -> sleep
                        state.data[SNAKE_CLIMBING]?.toInt()?.and(1) == 1 -> climb
                        state.moving -> move
                        else -> null
                    }
                    when {
                        desired == null && current != null -> GeckoLibControllerDecision.Stop
                        desired == null || desired == current -> GeckoLibControllerDecision.Keep
                        else -> GeckoLibControllerDecision.Play(desired)
                    }
                },
            ),
            GeckoLibControllerDefinition(
                name = "attackController",
                triggerableAnimations = mapOf(SNAKE_ATTACK_TRIGGER to attack),
                eventTriggers = mapOf(
                    GeckoLibHostEvents.entityAnimation(EntityAnimations.SWING_MAIN_ARM) to SNAKE_ATTACK_TRIGGER,
                    GeckoLibHostEvents.entityAnimation(EntityAnimations.SWING_OFF_ARM) to SNAKE_ATTACK_TRIGGER,
                ),
            ),
            GeckoLibControllerDefinition(
                name = "tongueController",
                trackedDataInputs = listOf(GeckoLibTrackedDataInput(SNAKE_SLEEPING, 19)),
                hostStateInputs = listOf(
                    GeckoLibHostStateInput(
                        SNAKE_TONGUE_RANDOM,
                        GeckoLibHostStateQuery.RandomInteger(SNAKE_TONGUE_RANDOM_BOUND),
                        defaultValue = (SNAKE_TONGUE_RANDOM_BOUND - 1).toDouble(),
                    ),
                ),
                predicate = GeckoLibAnimationPredicate { state, current ->
                    val ageTicks = (state.ageSeconds * TICKS_PER_SECOND).toInt()
                    if (
                        current == null &&
                        state.data[SNAKE_SLEEPING] != 1.0 &&
                        (state.data[SNAKE_TONGUE_RANDOM]?.toInt()
                            ?: SNAKE_TONGUE_RANDOM_BOUND - 1) < ageTicks
                    ) {
                        GeckoLibControllerDecision.PlayRaw(tongueAnimation, restart = true)
                    } else {
                        GeckoLibControllerDecision.Keep
                    }
                },
            ),
            GeckoLibControllerDefinition(
                name = "rattleController",
                trackedDataInputs = listOf(GeckoLibTrackedDataInput(SNAKE_SLEEPING, 19)),
                hostStateInputs = listOf(
                    GeckoLibHostStateInput(
                        SNAKE_IS_RATTLESNAKE,
                        GeckoLibHostStateQuery.EntityType(naturalist("rattlesnake")),
                    ),
                    GeckoLibHostStateInput(
                        SNAKE_NEARBY_PLAYER,
                        GeckoLibHostStateQuery.NearbyPlayer(
                            range = 4.0,
                            horizontalExpansion = 4.0,
                            verticalExpansion = 2.0,
                        ),
                    ),
                ),
                predicate = GeckoLibAnimationPredicate { state, current ->
                    val shouldRattle =
                        state.data[SNAKE_SLEEPING] != 1.0 &&
                            state.data[SNAKE_IS_RATTLESNAKE] == 1.0 &&
                            state.data[SNAKE_NEARBY_PLAYER] == 1.0
                    when {
                        shouldRattle && current != rattle ->
                            GeckoLibControllerDecision.PlayRaw(rattleAnimation, restart = true)
                        !shouldRattle && current != null -> GeckoLibControllerDecision.Stop
                        else -> GeckoLibControllerDecision.Keep
                    }
                },
            ),
        )
    }

    /**
     * Mirrors the pinned Snake sound-handler surface rather than treating
     * authoring aliases as resource locations. Only tongueController installs
     * a handler, and that handler accepts only "hiss". The current tongue
     * timeline emits "idle", while rattling is played by entity sound packets,
     * so both animation aliases must remain silent.
     */
    private fun snakeEffect(context: GeckoLibRuntimeEventContext): GeckoLibRuntimeEffectResolution {
        if (context.event.type != SkeletalAnimationEventType.SOUND) {
            return GeckoLibRuntimeEffectResolution.PassThrough
        }
        return if (
            context.animation == SNAKE_TONGUE_CLIP &&
            context.event.payload.trim() == "hiss"
        ) {
            GeckoLibRuntimeEffectResolution.Play(SNAKE_HISS)
        } else {
            GeckoLibRuntimeEffectResolution.Ignore
        }
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
        return model in SUPPORTED_MODELS
    }

    private fun naturalist(path: String) = ResourceLocation("naturalist", path)
    private fun entityTexture(path: String) = naturalist("textures/entity/$path.png")

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
            var failure: Throwable? = null
            for (registration in registrations.values.toList().asReversed()) {
                try {
                    registration.close()
                } catch (error: Throwable) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            registrations.clear()
            failure?.let { throw it }
        }
    }

    private class NaturalistAssetsManager(path: String) :
        ZipAssetsManager(path, entryFilter = ::includeArtifactEntry) {

        override fun load(latch: AbstractLatch?) {
            super.load(latch)
            correctGeometry("bird", NaturalistGeometryCorrections::bird)
            correctGeometry("boar", NaturalistGeometryCorrections::boar)
            correctGeometry("catfish", NaturalistGeometryCorrections::catfish)
            correctGeometry("caterpillar", NaturalistGeometryCorrections::caterpillar)
            correctGeometry("firefly", NaturalistGeometryCorrections::firefly)
            assets[naturalist("geo/entity/zebra.geo.json")] = NaturalistNativeModels.zebra()
            alias("textures/entity/lizard/beardie.png", "textures/entity/lizard.png")
            alias("textures/entity/lizard/green_tail.png", "textures/entity/lizard_tail.png")
            alias("textures/entity/caterpillar.png", "textures/__content/geometry.unknown.png")
        }

        private fun alias(source: String, target: String) {
            assets[naturalist(source)]?.let { assets[naturalist(target)] = it }
        }

        private fun correctGeometry(path: String, correction: (ByteArray) -> ByteArray) {
            val source = naturalist("geo/entity/$path.geo.json")
            val bytes = assets[source] ?: return
            assets[source] = correction(bytes)
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
        Model("lizard_tail", "geometry.lizard_tail"),
        Model("moose", "geometry.sf_nba.moose"),
        Model("rhino", "geometry.sf_nba.rhino"),
        Model("snail", "geometry.snail"),
        Model("snake", "geometry.snake"),
        Model("tortoise", "geometry.sf_nba.tortoise"),
        Model("vulture", "geometry.sf_nba.vulture"),
        Model("zebra", "geometry.minosoft.naturalist.zebra_native"),
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
        "lizard_tail" to model("lizard_tail"),
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

    private fun staticTexture(path: String) = entityTexture(path).let {
        GeckoLibEntityTextureDefinition(it, setOf(it))
    }

    private fun indexedTextures(
        fallback: String,
        index: Int,
        vararg paths: String,
    ): GeckoLibEntityTextureDefinition {
        val textures = paths.map(::entityTexture)
        val fallbackTexture = entityTexture(fallback)
        return GeckoLibEntityTextureDefinition(
            fallback = fallbackTexture,
            textures = textures.toSet(),
            selector = GeckoLibEntityTextureSelector { state ->
                textures[state.int(index).coerceIn(textures.indices)]
            },
        )
    }

    /**
     * Exact GeoModel texture surface from the pinned Naturalist sources.
     * Absolute tracked-data indices follow Minecraft 1.20.4's Entity (0..7),
     * LivingEntity (8..14), Mob (15), AgeableMob (16), and TamableAnimal
     * (17..18) allocation before each Naturalist subclass.
     */
    private val TEXTURES = linkedMapOf(
        "alligator" to staticTexture("alligator/alligator"),
        "bass" to staticTexture("bass"),
        "bear" to run {
            val normal = entityTexture("bear/bear")
            val angry = entityTexture("bear/bear_angry")
            val sleeping = entityTexture("bear/bear_sleep")
            GeckoLibEntityTextureDefinition(
                normal,
                setOf(normal, angry, sleeping),
                GeckoLibEntityTextureSelector { state ->
                    when {
                        state.int(22) > 0 || state.aggressive -> angry
                        state.boolean(17) -> sleeping
                        else -> normal
                    }
                },
            )
        },
        "bird" to run {
            val paths = listOf("bluejay", "canary", "cardinal", "finch", "robin", "sparrow")
                .associateWith { entityTexture("bird/$it") }
            GeckoLibEntityTextureDefinition(
                paths.getValue("robin"),
                paths.values.toSet(),
                GeckoLibEntityTextureSelector { state -> paths[state.entity.path] ?: paths.getValue("robin") },
            )
        },
        "boar" to staticTexture("boar"),
        "butterfly" to indexedTextures(
            "butterfly/monarch",
            18,
            "butterfly/cabbage_white",
            "butterfly/monarch",
            "butterfly/clouded_yellow",
            "butterfly/swallowtail",
            "butterfly/blue_morpho",
        ),
        "caterpillar" to staticTexture("caterpillar"),
        "catfish" to staticTexture("catfish"),
        "deer" to run {
            val adult = entityTexture("deer")
            val baby = entityTexture("fawn")
            GeckoLibEntityTextureDefinition(
                adult,
                setOf(adult, baby),
                GeckoLibEntityTextureSelector { if (it.baby) baby else adult },
            )
        },
        "dragonfly" to indexedTextures(
            "dragonfly/blue",
            16,
            "dragonfly/blue",
            "dragonfly/green",
            "dragonfly/red",
        ),
        "duck" to run {
            val normal = entityTexture("duck/duck")
            val queso = entityTexture("duck/queso")
            val donald = entityTexture("duck/donald")
            GeckoLibEntityTextureDefinition(
                normal,
                setOf(normal, queso, donald),
                GeckoLibEntityTextureSelector { state ->
                    when {
                        state.name.equals("Queso", ignoreCase = true) -> queso
                        state.name.equals("Donald", ignoreCase = true) -> donald
                        else -> normal
                    }
                },
            )
        },
        "elephant" to staticTexture("elephant"),
        "firefly" to staticTexture("firefly/firefly"),
        "giraffe" to staticTexture("giraffe"),
        "hippo" to staticTexture("hippo"),
        "lion" to run {
            val lion = entityTexture("lion/lion")
            val lionSleep = entityTexture("lion/lion_sleep")
            val lionAngry = entityTexture("lion/lion_angry")
            val lioness = entityTexture("lion/lioness")
            val lionessSleep = entityTexture("lion/lioness_sleep")
            val lionessAngry = entityTexture("lion/lioness_angry")
            GeckoLibEntityTextureDefinition(
                lion,
                setOf(lion, lionSleep, lionAngry, lioness, lionessSleep, lionessAngry),
                GeckoLibEntityTextureSelector { state ->
                    val sleeping = state.boolean(17)
                    val mane = state.boolean(18)
                    when {
                        sleeping && mane && !state.baby -> lionSleep
                        sleeping -> lionessSleep
                        state.aggressive && mane && !state.baby -> lionAngry
                        state.aggressive -> lionessAngry
                        !mane || state.baby -> lioness
                        else -> lion
                    }
                },
            )
        },
        "lizard" to indexedTextures(
            "lizard/green",
            19,
            "lizard/green",
            "lizard/brown",
            "lizard/beardie",
            "lizard/leopard_gecko",
        ),
        "lizard_tail" to indexedTextures(
            "lizard/green_tail",
            16,
            "lizard/green_tail",
            "lizard/brown_tail",
            "lizard/beardie_tail",
            "lizard/leopard_gecko_tail",
        ),
        "moose" to staticTexture("moose"),
        "rhino" to staticTexture("rhino"),
        "snail" to run {
            val colors = listOf(
                "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
                "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
            ).map { entityTexture("snail/$it") }
            val brown = colors[12]
            val gary = entityTexture("snail/gary")
            GeckoLibEntityTextureDefinition(
                brown,
                (colors + gary).toSet(),
                GeckoLibEntityTextureSelector { state ->
                    if (state.name?.contains("Gary") == true) gary else colors[state.int(21, 12).coerceIn(colors.indices)]
                },
            )
        },
        "snake" to run {
            val normal = entityTexture("snake/snake")
            val coral = entityTexture("snake/coral_snake")
            val rattlesnake = entityTexture("snake/rattlesnake")
            GeckoLibEntityTextureDefinition(
                normal,
                setOf(normal, coral, rattlesnake),
                GeckoLibEntityTextureSelector { state ->
                    when (state.entity.path) {
                        "coral_snake" -> coral
                        "rattlesnake" -> rattlesnake
                        else -> normal
                    }
                },
            )
        },
        "tortoise" to indexedTextures(
            "tortoise/brown",
            19,
            "tortoise/brown",
            "tortoise/green",
            "tortoise/black",
        ),
        "vulture" to staticTexture("vulture"),
        "zebra" to staticTexture("zebra"),
    )
    private val SUPPORTED_MODELS = MODEL_DEFINITIONS.keys
    private fun remoteEntity(
        path: String,
        width: Float,
        height: Float,
        kind: FabricRemoteEntityKind = FabricRemoteEntityKind.LIVING,
    ) = FabricRemoteEntityDefinition(naturalist(path), width, height, kind)

    /**
     * Width and height values are pinned to NaturalistEntityTypes from the
     * verified 5.0.0-pre.4 artifact. Bird and snake variants intentionally
     * share their upstream dimensions.
     */
    private val REMOTE_ENTITIES = listOf(
        remoteEntity("alligator", 1.8f, 0.8f),
        remoteEntity("bass", 0.7f, 0.4f),
        remoteEntity("bear", 1.4f, 1.7f),
        remoteEntity("bluejay", 0.5f, 0.6f),
        remoteEntity("boar", 0.9f, 0.9f),
        remoteEntity("butterfly", 0.7f, 0.6f),
        remoteEntity("canary", 0.5f, 0.6f),
        remoteEntity("cardinal", 0.5f, 0.6f),
        remoteEntity("caterpillar", 0.4f, 0.4f),
        remoteEntity("catfish", 0.7f, 0.4f),
        remoteEntity("coral_snake", 0.6f, 0.7f),
        remoteEntity("deer", 1.3f, 1.6f),
        remoteEntity("dragonfly", 0.9f, 0.7f),
        remoteEntity("duck", 0.6f, 1.0f),
        remoteEntity("duck_egg", 0.25f, 0.25f, FabricRemoteEntityKind.ENTITY),
        remoteEntity("elephant", 2.5f, 3.5f),
        remoteEntity("firefly", 0.7f, 0.6f),
        remoteEntity("finch", 0.5f, 0.6f),
        remoteEntity("giraffe", 1.9f, 5.4f),
        remoteEntity("hippo", 1.8f, 1.8f),
        remoteEntity("lion", 1.5f, 1.8f),
        remoteEntity("lizard", 0.8f, 0.5f),
        remoteEntity("lizard_tail", 0.7f, 0.5f),
        remoteEntity("moose", 1.7f, 2.0f),
        remoteEntity("rattlesnake", 0.6f, 0.7f),
        remoteEntity("rhino", 2.5f, 3.0f),
        remoteEntity("robin", 0.5f, 0.6f),
        remoteEntity("snail", 0.7f, 0.7f),
        remoteEntity("snake", 0.6f, 0.7f),
        remoteEntity("sparrow", 0.5f, 0.6f),
        remoteEntity("tortoise", 1.2f, 0.875f),
        remoteEntity("vulture", 0.9f, 0.5f),
        remoteEntity("zebra", 1.3964844f, 1.5f),
    )
    private val MOVEMENT_SUFFIXES = listOf("walk", "move", "swim", "fly", "crawl", "run", "flop")
    private const val SNAKE_CLIMBING = "naturalist.snake.climbing"
    private const val SNAKE_SLEEPING = "naturalist.snake.sleeping"
    private const val SNAKE_ATTACK_TRIGGER = "naturalist.snake.attack"
    private const val SNAKE_TONGUE_CLIP = "animation.sf_nba.snake.tongue"
    private const val SNAKE_RATTLE_CLIP = "animation.sf_nba.snake.rattle"
    private const val SNAKE_TONGUE_RANDOM = "naturalist.snake.tongue_random"
    private const val SNAKE_IS_RATTLESNAKE = "naturalist.snake.is_rattlesnake"
    private const val SNAKE_NEARBY_PLAYER = "naturalist.snake.nearby_player"
    private const val SNAKE_TONGUE_RANDOM_BOUND = 1_000
    private const val TICKS_PER_SECOND = 20.0f
    private val SNAKE_HISS = ResourceLocation.of("naturalist:entity.snake.hiss")
    private const val GEOMETRY_PREFIX = "assets/naturalist/geo/entity/"
    private const val ANIMATION_PREFIX = "assets/naturalist/animations/"
    private val NESTED_JARS = setOf(
        "META-INF/jars/midnightlib-1.5.3-fabric.jar",
        "META-INF/jars/cloth-config-fabric-13.0.138-fabric.jar",
    )
}

/**
 * Source-native bridges for pinned Naturalist renderers which use Minecraft
 * model layers instead of the GeckoLib geometry files shipped in the mod.
 */
internal object NaturalistNativeModels {
    /**
     * Naturalist's ZebraRenderer constructs ZebraModel from Minecraft 1.20.4's
     * undilated HorseEntityModel layer. The artifact contains no zebra geometry;
     * its unrelated ostrich file must not be used as a fallback. This neutral
     * representation retains the adult, unsaddled, unchested default surface
     * and names the control bones used by Naturalist's shipped zebra timelines.
     */
    fun zebra(): ByteArray = """
        {
          "format_version": "1.12.0",
          "minecraft:geometry": [
            {
              "description": {
                "identifier": "geometry.minosoft.naturalist.zebra_native",
                "texture_width": 64,
                "texture_height": 64,
                "visible_bounds_width": 3,
                "visible_bounds_height": 3,
                "visible_bounds_offset": [0, 1.5, 0]
              },
              "bones": [
                {
                  "name": "root",
                  "pivot": [0, 0, 0]
                },
                {
                  "name": "body",
                  "parent": "root",
                  "pivot": [0, 13, 5],
                  "cubes": [
                    {"origin": [-5, 11, -12], "size": [10, 10, 22], "inflate": 0.05, "uv": [0, 32]}
                  ]
                },
                {
                  "name": "tail",
                  "parent": "body",
                  "pivot": [0, 18, 7],
                  "rotation": [30, 0, 0],
                  "cubes": [
                    {"origin": [-1.5, 4, 7], "size": [3, 14, 4], "uv": [42, 36]}
                  ]
                },
                {
                  "name": "neck",
                  "parent": "root",
                  "pivot": [0, 20, -12],
                  "rotation": [30, 0, 0],
                  "cubes": [
                    {"origin": [-2.05, 14, -14], "size": [4, 12, 7], "uv": [0, 35]}
                  ]
                },
                {
                  "name": "head",
                  "parent": "neck",
                  "pivot": [0, 20, -12],
                  "cubes": [
                    {"origin": [-3, 26, -14], "size": [6, 5, 7], "uv": [0, 13]}
                  ]
                },
                {
                  "name": "mane",
                  "parent": "head",
                  "pivot": [0, 20, -12],
                  "cubes": [
                    {"origin": [-1, 15, -6.99], "size": [2, 16, 2], "uv": [56, 36]}
                  ]
                },
                {
                  "name": "upper_mouth",
                  "parent": "head",
                  "pivot": [0, 20, -12],
                  "cubes": [
                    {"origin": [-2, 26, -19], "size": [4, 5, 5], "uv": [0, 25]}
                  ]
                },
                {
                  "name": "left_ear",
                  "parent": "head",
                  "pivot": [0, 20, -12],
                  "cubes": [
                    {"origin": [0.55, 30, -8], "size": [2, 3, 1], "inflate": -0.001, "uv": [19, 16]}
                  ]
                },
                {
                  "name": "right_ear",
                  "parent": "head",
                  "pivot": [0, 20, -12],
                  "cubes": [
                    {"origin": [-2.55, 30, -8], "size": [2, 3, 1], "inflate": -0.001, "uv": [19, 16]}
                  ]
                },
                {
                  "name": "front_legs",
                  "parent": "root",
                  "pivot": [0, 0, 0]
                },
                {
                  "name": "left_arm",
                  "parent": "front_legs",
                  "pivot": [4, 10, -12]
                },
                {
                  "name": "legfl",
                  "parent": "left_arm",
                  "pivot": [4, 10, -12],
                  "cubes": [
                    {"origin": [1, 0.01, -13.9], "size": [4, 11, 4], "uv": [48, 21], "mirror": true}
                  ]
                },
                {
                  "name": "right_arm",
                  "parent": "front_legs",
                  "pivot": [-4, 10, -12]
                },
                {
                  "name": "legfr",
                  "parent": "right_arm",
                  "pivot": [-4, 10, -12],
                  "cubes": [
                    {"origin": [-5, 0.01, -13.9], "size": [4, 11, 4], "uv": [48, 21]}
                  ]
                },
                {
                  "name": "back_legs",
                  "parent": "root",
                  "pivot": [0, 0, 0]
                },
                {
                  "name": "left_leg",
                  "parent": "back_legs",
                  "pivot": [4, 10, 7]
                },
                {
                  "name": "legbl",
                  "parent": "left_leg",
                  "pivot": [4, 10, 7],
                  "cubes": [
                    {"origin": [1, 0.01, 6], "size": [4, 11, 4], "uv": [48, 21], "mirror": true}
                  ]
                },
                {
                  "name": "right_leg",
                  "parent": "back_legs",
                  "pivot": [-4, 10, 7]
                },
                {
                  "name": "legbr",
                  "parent": "right_leg",
                  "pivot": [-4, 10, 7],
                  "cubes": [
                    {"origin": [-5, 0.01, 6], "size": [4, 11, 4], "uv": [48, 21]}
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent().encodeToByteArray()
}

/**
 * Narrow, fail-closed corrections for defects in the exact pinned Naturalist
 * geometry. They are applied only inside that adapter's private asset view and
 * never rewrite the third-party artifact.
 */
internal object NaturalistGeometryCorrections {
    fun bird(bytes: ByteArray): ByteArray = geometry(bytes) { bones ->
        val left = bones.bone("leftLeg")
        val right = bones.bone("rightLeg")
        val leftCubes = left.requiredArray("cubes")
        val rightCubes = right.requiredArray("cubes")
        require(leftCubes.size() == 2 && rightCubes.size() == 1) {
            "Pinned Naturalist bird foot geometry changed."
        }

        val leftToe = leftCubes[1] as? ObjectNode
            ?: error("Pinned Naturalist left toe is not an object.")
        leftToe.requiredArray("origin").setNumber(2, -1.0)

        val rightToe = leftToe.deepCopy()
        rightToe.requiredArray("origin").setNumber(0, -1.5)
        rightToe.put("mirror", true)
        rightCubes.add(rightToe)
    }

    fun boar(bytes: ByteArray): ByteArray = geometry(bytes) { bones ->
        val bodyCubes = bones.bone("body").requiredArray("cubes")
        require(bodyCubes.size() == 2) { "Pinned Naturalist boar body geometry changed." }
        val ridge = bodyCubes[1] as? ObjectNode
            ?: error("Pinned Naturalist boar ridge is not an object.")
        val uv = Jackson.MAPPER.createObjectNode()
        uv.set<ObjectNode>("west", face(0.0, 46.0, 11.0, -18.0))
        uv.set<ObjectNode>("east", face(11.0, 46.0, -11.0, -18.0))
        ridge.set<ObjectNode>("uv", uv)
    }

    fun catfish(bytes: ByteArray): ByteArray = geometry(bytes) { bones ->
        val whiskerCubes = bones.bone("whiskers").requiredArray("cubes")
        require(whiskerCubes.size() == 1) { "Pinned Naturalist catfish whisker geometry changed." }
        val whiskers = whiskerCubes[0] as? ObjectNode
            ?: error("Pinned Naturalist catfish whiskers are not an object.")
        whiskers.requiredArray("origin").addNumber(1, 0.25)
        whiskers.requiredArray("pivot").addNumber(1, 0.25)
    }

    fun caterpillar(bytes: ByteArray): ByteArray = geometry(bytes) { bones ->
        val antennaCubes = bones.bone("antennae").requiredArray("cubes")
        require(antennaCubes.size() == 1) { "Pinned Naturalist caterpillar antenna geometry changed." }
        val antennae = antennaCubes[0] as? ObjectNode
            ?: error("Pinned Naturalist caterpillar antennae are not an object.")
        antennae.requiredArray("origin").apply {
            addNumber(1, -0.25)
            addNumber(2, 0.25)
        }
    }

    fun firefly(bytes: ByteArray): ByteArray = geometry(bytes) { bones ->
        val antennaCubes = bones.bone("antennae").requiredArray("cubes")
        require(antennaCubes.size() == 1) { "Pinned Naturalist firefly antenna geometry changed." }
        val antennae = antennaCubes[0] as? ObjectNode
            ?: error("Pinned Naturalist firefly antennae are not an object.")
        antennae.requiredArray("origin").apply {
            addNumber(1, -0.25)
            addNumber(2, 0.25)
        }
    }

    private fun geometry(bytes: ByteArray, correction: (ArrayNode) -> Unit): ByteArray {
        val root = Jackson.MAPPER.readTree(bytes) as? ObjectNode
            ?: error("Pinned Naturalist geometry root is not an object.")
        val geometries = root.requiredArray("minecraft:geometry")
        require(geometries.size() == 1) { "Pinned Naturalist geometry document changed." }
        val geometry = geometries[0] as? ObjectNode
            ?: error("Pinned Naturalist geometry entry is not an object.")
        correction(geometry.requiredArray("bones"))
        return Jackson.MAPPER.writeValueAsBytes(root)
    }

    private fun ArrayNode.bone(name: String): ObjectNode {
        return firstOrNull { it.path("name").asText() == name } as? ObjectNode
            ?: error("Pinned Naturalist geometry is missing bone '$name'.")
    }

    private fun ObjectNode.requiredArray(name: String): ArrayNode {
        return get(name) as? ArrayNode
            ?: error("Pinned Naturalist geometry field '$name' is not an array.")
    }

    private fun ArrayNode.setNumber(index: Int, value: Double) {
        require(index in 0 until size()) { "Pinned Naturalist vector is too short." }
        set(index, Jackson.MAPPER.nodeFactory.numberNode(value))
    }

    private fun ArrayNode.addNumber(index: Int, delta: Double) {
        require(index in 0 until size() && this[index].isNumber) {
            "Pinned Naturalist vector component is missing."
        }
        setNumber(index, this[index].asDouble() + delta)
    }

    private fun face(u: Double, v: Double, width: Double, height: Double): ObjectNode {
        return Jackson.MAPPER.createObjectNode().apply {
            set<ArrayNode>("uv", Jackson.MAPPER.createArrayNode().add(u).add(v))
            set<ArrayNode>("uv_size", Jackson.MAPPER.createArrayNode().add(width).add(height))
        }
    }
}
