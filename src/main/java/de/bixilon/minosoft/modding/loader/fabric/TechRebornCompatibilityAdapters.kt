/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.assets.ExternalAssetProviders
import de.bixilon.minosoft.assets.file.ZipAssetsManager
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

private val ALL_FABRIC_BLOCKERS = FabricCompatibilityBlocker.entries.toSet()

object FabricApiCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:fabric-api-0.97.3-mc1.20.4"
    override val handledBlockers = ALL_FABRIC_BLOCKERS
    override val capabilities = setOf(
        FabricHostCapability.FABRIC_API_MODULES,
        FabricHostCapability.CLIENT_EVENTS,
        FabricHostCapability.CLIENT_CONNECTION_EVENTS,
        FabricHostCapability.CLIENT_COMMANDS,
        FabricHostCapability.CLIENT_PAYLOAD_CHANNELS,
        FabricHostCapability.CLIENT_TICK_EVENTS,
        FabricHostCapability.BLOCK_MUTATION_EVENTS,
        FabricHostCapability.CHUNK_EVENTS,
        FabricHostCapability.HUD_LAYERS,
        FabricHostCapability.ENTITY_EVENTS,
        FabricHostCapability.INPUT_EVENTS,
        FabricHostCapability.KEY_BINDINGS,
        FabricHostCapability.PARTICLE_EVENTS,
        FabricHostCapability.PLAYER_INTERACTIONS,
        FabricHostCapability.RESOURCE_RELOAD_EVENTS,
        FabricHostCapability.SCREENS,
        FabricHostCapability.SOUND_EVENTS,
        FabricHostCapability.WORLD_EVENTS,
    )
    override val functionality = FabricFunctionalityCatalog.FABRIC_API

    override fun supports(metadata: FabricMetadata): Boolean =
        metadata.id == "fabric-api" &&
            metadata.version == "0.97.3+1.20.4" &&
            metadata.environment == "*" &&
            metadata.entrypoints.isEmpty() &&
            metadata.mixins == 0 &&
            metadata.accessWidener == null &&
            metadata.nestedJars == 50

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Fabric API artifact: ${probe.metadata.version}" }
        val modules = probe.nestedMods.associate { it.id to it.version }.toSortedMap()
        require(modules.size == 50) { "Fabric API module surface changed: expected 50, found ${modules.size}" }
        scope.own(FabricApiModuleRegistry.register(id, FabricApiModules(probe.metadata.version, modules)))
        scope.own(FabricClientEvents.install(id))
        scope.own(FabricClientConnectionEvents.install(id))
        scope.own(FabricClientCommands.install(id))
        scope.own(FabricClientPayloadChannels.install(id))
        scope.own(FabricClientTickEvents.install(id))
        scope.own(FabricBlockMutationEvents.install(id))
        scope.own(FabricChunkEvents.install(id))
        scope.own(FabricHudLayers.install(id))
        scope.own(FabricEntityEvents.install(id))
        scope.own(FabricInputEvents.install(id))
        scope.own(FabricKeyBindings.install(id))
        scope.own(FabricParticleEvents.install(id))
        scope.own(FabricPlayerInteractionHooks.install(id))
        scope.own(FabricResourceReloadEvents.install(id))
        scope.own(FabricScreens.install(id))
        scope.own(FabricSoundEvents.install(id))
        scope.own(FabricWorldEvents.install(id))
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "FABRIC_API_MODULES_ACTIVE version=${probe.metadata.version} modules=${modules.size} clientEvents=true clientConnectionEvents=true clientCommands=true clientPayloadChannels=true clientTickEvents=true worldEvents=true chunkEvents=true blockMutationEvents=true entityEvents=true playerInteractions=true particleEvents=true soundEvents=true inputEvents=true keyBindings=true screens=true hudLayers=true resourceReloadEvents=true"
        }
    }
}

object RebornCoreCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:reborncore-5.10.4-mc1.20.4"
    override val handledBlockers = ALL_FABRIC_BLOCKERS
    override val capabilities = setOf(FabricHostCapability.ENERGY_STORAGE)
    override val functionality = FabricFunctionalityCatalog.REBORN_CORE

    override fun supports(metadata: FabricMetadata): Boolean =
        metadata.id == "reborncore" &&
            metadata.version == "5.10.4" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("main", "client") &&
            metadata.mixins == 2 &&
            metadata.accessWidener == "reborncore.accesswidener" &&
            metadata.nestedJars == 1

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Reborn Core artifact: ${probe.metadata.version}" }
        val energy = probe.nestedMods.singleOrNull { it.id == "team_reborn_energy" }
        requireNotNull(energy) { "Reborn Core does not contain Team Reborn Energy." }
        scope.own(ExternalAssetProviders.register(id) { ZipAssetsManager(probe.metadata.source) })
        scope.own(FabricEnergyCapabilities.register(id, FabricEnergyCapability(energy.id, energy.version)))
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "REBORN_ENERGY_ACTIVE api=${energy.id} version=${energy.version}"
        }
    }
}

object TechRebornCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:techreborn-5.10.4-mc1.20.4"
    override val handledBlockers = ALL_FABRIC_BLOCKERS
    override val capabilities = setOf(
        FabricHostCapability.CONTENT_CATALOG,
        FabricHostCapability.WORLD_CONTENT,
        FabricHostCapability.WORLD_GENERATION,
    )
    override val functionality = FabricFunctionalityCatalog.TECH_REBORN

    override fun supports(metadata: FabricMetadata): Boolean =
        metadata.id == "techreborn" &&
            metadata.version == "5.10.4" &&
            metadata.environment == "*" &&
            metadata.entrypoints == setOf("main", "client", "rei_client") &&
            metadata.mixins == 0 &&
            metadata.accessWidener == "techreborn.accesswidener" &&
            metadata.nestedJars == 2

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Tech Reborn artifact: ${probe.metadata.version}" }
        val catalog = FabricContentCatalogReader.read(probe.metadata)
        val worldContent = FabricWorldContentReader.read(probe.metadata)
        require(catalog.resources > 0) { "Tech Reborn artifact exposed no source-native content." }
        require(worldContent.blocks.isNotEmpty()) { "Tech Reborn artifact exposed no source-native blocks." }
        require(worldContent.ores.isNotEmpty()) { "Tech Reborn artifact exposed no source-native ore features." }
        scope.own(ExternalAssetProviders.register(id) { ZipAssetsManager(probe.metadata.source) })
        scope.own(FabricContentCatalogs.register(id, catalog))
        scope.own(FabricWorldContents.register(id, worldContent))
        scope.own(FabricSessionContentBridge.register(id, worldContent))
        val snapshot = FabricRegistrySnapshot.of(worldContent)
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "TECH_REBORN_CONTENT_ACTIVE resources=${catalog.resources} blocks=${worldContent.blocks.size} items=${worldContent.items.size} states=${snapshot.states.size} ores=${worldContent.ores.size} fingerprint=${worldContent.fingerprint}"
        }
    }
}
