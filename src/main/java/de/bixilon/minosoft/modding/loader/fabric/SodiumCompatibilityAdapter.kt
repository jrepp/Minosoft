/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

object SodiumCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:sodium-0.5.8-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.CHUNK_RENDER_SCHEDULING)
    override val functionality = FabricFunctionalityCatalog.SODIUM

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "sodium" &&
            metadata.version == "0.5.8+mc1.20.4" &&
            metadata.environment == "client" &&
            metadata.entrypoints.containsAll(setOf("client", "preLaunch")) &&
            metadata.mixins > 0 &&
            metadata.accessWidener != null &&
            metadata.nestedJars == 5
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Sodium artifact: ${probe.metadata.version}" }
        scope.own(FabricRendererRegistry.register(id, SodiumRendererHook))
    }
}
