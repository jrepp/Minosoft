/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean

object EntityCullingCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:entityculling-1.10.5-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.ENTITY_VISIBILITY)
    override val functionality = FabricFunctionalityCatalog.ENTITY_CULLING

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "entityculling" &&
            metadata.version == "1.10.5" &&
            metadata.environment == "*" &&
            metadata.entrypoints.containsAll(setOf("client", "modmenu")) &&
            metadata.mixins == 1 &&
            metadata.accessWidener == null &&
            metadata.nestedJars == 2
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Entity Culling artifact: ${probe.metadata.version}" }
        EntityCullingVisibilityHook.reset()
        scope.own(FabricEntityVisibilityHooks.register(id, EntityCullingVisibilityHook))
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "ENTITY_CULLING_HOOK_INSTALLED hook=entity-visibility implementation=minosoft-native-occlusion"
        }
    }
}

object EntityCullingVisibilityHook : FabricEntityVisibilityHook {
    private val invoked = AtomicBoolean()

    fun reset() = invoked.set(false)

    override fun refine(
        renderer: de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer<*>,
        native: EntityVisibilityLevels,
    ): EntityVisibilityLevels {
        if (invoked.compareAndSet(false, true)) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "ENTITY_CULLING_HOOK_INVOKED hook=entity-visibility first=${native.name.lowercase()} implementation=minosoft-native-occlusion"
            }
        }
        return native
    }
}
