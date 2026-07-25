/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import java.util.concurrent.atomic.AtomicBoolean

object ImmediatelyFastCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:immediatelyfast-1.5.5-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.FRAME_BATCHING)
    override val functionality = FabricFunctionalityCatalog.IMMEDIATELY_FAST

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "immediatelyfast" &&
            metadata.version == "1.5.5+1.20.4" &&
            metadata.environment == "client" &&
            metadata.entrypoints.isEmpty() &&
            metadata.mixins == 2 &&
            metadata.accessWidener == "immediatelyfast.accesswidener" &&
            metadata.nestedJars == 1
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported ImmediatelyFast artifact: ${probe.metadata.version}" }
        ImmediatelyFastFrameHook.reset()
        scope.own(FabricFrameHooks.register(id, ImmediatelyFastFrameHook))
        scope.own(
            FabricSettings.register(id, minosoft("immediately_fast_options"), "ImmediatelyFast settings") {
                SettingsSchema(
                    title = "ImmediatelyFast settings",
                    entries = listOf(
                        ConfigEntry(
                            id = "frame_batching",
                            label = "Retained frame batching",
                            description = "Enables the source-native retained queue-flush integration.",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = ImmediatelyFastOptions::frameBatching,
                            write = ImmediatelyFastOptions::setFrameBatching,
                        ),
                        ConfigEntry(
                            id = "hud_batching",
                            label = "HUD batching",
                            description = "Records the desired compatibility policy; Minosoft HUD meshes are already retained.",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = ImmediatelyFastOptions::hudBatching,
                            write = ImmediatelyFastOptions::setHudBatching,
                        ),
                        ConfigEntry(
                            id = "screen_batching",
                            label = "Screen batching",
                            description = "Records the desired compatibility policy; Minosoft screens are already retained.",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = ImmediatelyFastOptions::screenBatching,
                            write = ImmediatelyFastOptions::setScreenBatching,
                        ),
                    ),
                    persist = ImmediatelyFastOptions::persist,
                )
            },
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "IMMEDIATELY_FAST_HOOK_INSTALLED hook=frame-batching implementation=minosoft-retained-rendering"
        }
    }
}

object ImmediatelyFastFrameHook : FabricFrameHook {
    private val invoked = AtomicBoolean()

    fun reset() = invoked.set(false)

    override fun beforeQueueFlush(context: RenderContext) {
        if (!ImmediatelyFastOptions.frameBatching()) return
        if (invoked.compareAndSet(false, true)) {
            Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
                "IMMEDIATELY_FAST_HOOK_INVOKED hook=frame-batching implementation=minosoft-retained-rendering"
            }
        }
    }
}

private object ImmediatelyFastOptions {
    private val store by lazy {
        FabricAdapterOptionStore(
            "immediatelyfast",
            mapOf(
                "frame_batching" to "true",
                "hud_batching" to "true",
                "screen_batching" to "true",
            ),
        )
    }

    fun frameBatching() = store.boolean("frame_batching")
    fun hudBatching() = store.boolean("hud_batching")
    fun screenBatching() = store.boolean("screen_batching")
    fun setFrameBatching(value: Boolean) = store.set("frame_batching", value)
    fun setHudBatching(value: Boolean) = store.set("hud_batching", value)
    fun setScreenBatching(value: Boolean) = store.set("screen_batching", value)
    fun persist() = store.persist()
}
