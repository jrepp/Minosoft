/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsCategory
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.config.settings.SteppedConfigControl
import de.bixilon.minosoft.config.settings.TextConfigControl
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.data.world.World
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
        scope.own(
            FabricSettings.register(id, minosoft("entity_culling_options"), "Entity Culling settings") { renderer ->
                val general = renderer.session.profiles.entity.general
                SettingsSchema(
                    title = "Entity Culling settings",
                    entries = listOf(
                        ConfigEntry(
                            id = "enabled",
                            label = "Occlusion culling",
                            description = "Skips entities hidden behind opaque world geometry while retaining distance and frustum checks.",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = EntityCullingOptions::enabled,
                            write = EntityCullingOptions::setEnabled,
                        ),
                        ConfigEntry(
                            id = "render_distance",
                            label = "Entity render distance",
                            description = "Use -1 to follow the terrain view distance.",
                            defaultValue = -1,
                            control = SteppedConfigControl(listOf(-1) + (0..World.MAX_VIEW_DISTANCE).toList()) {
                                if (it < 0) "Follow terrain" else "$it chunks"
                            },
                            read = { general.renderDistance },
                            write = { general.renderDistance = it },
                        ),
                        ConfigEntry(
                            id = "entity_whitelist",
                            label = "Occlusion whitelist",
                            description = "Comma-separated entity identifiers that remain visible through occlusion.",
                            defaultValue = "",
                            control = TextConfigControl(4_096),
                            read = EntityCullingOptions::whitelistText,
                            write = EntityCullingOptions::setWhitelist,
                            validate = { value, _ -> EntityCullingOptions.validateWhitelist(value) },
                            category = "advanced",
                        ),
                    ),
                    categories = listOf(SettingsCategory.GENERAL, SettingsCategory("advanced", "Advanced")),
                    persist = EntityCullingOptions::persist,
                )
            },
        )
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
        if (native != EntityVisibilityLevels.OCCLUDED) return native
        if (!EntityCullingOptions.enabled()) return EntityVisibilityLevels.VISIBLE
        if (EntityCullingOptions.whitelisted(renderer.entity.type.identifier.toString())) return EntityVisibilityLevels.VISIBLE
        return native
    }
}

private object EntityCullingOptions {
    private val store by lazy {
        FabricAdapterOptionStore(
            "entityculling",
            mapOf(
                "enabled" to "true",
                "entity_whitelist" to "",
            ),
        )
    }
    @Volatile private var enabled = true
    @Volatile private var whitelistText = ""
    @Volatile private var whitelist = emptySet<String>()
    @Volatile private var loaded = false

    fun enabled(): Boolean {
        load()
        return enabled
    }

    fun setEnabled(value: Boolean) {
        load()
        enabled = value
        store.set("enabled", value)
    }

    fun whitelistText(): String {
        load()
        return whitelistText
    }

    fun setWhitelist(value: String) {
        require(validateWhitelist(value) == null) { "Invalid entity whitelist." }
        load()
        whitelistText = value
        whitelist = parseWhitelist(value)
        store.set("entity_whitelist", value)
    }

    fun whitelisted(identifier: String): Boolean {
        load()
        return identifier in whitelist
    }

    fun validateWhitelist(value: String): String? {
        val invalid = value.split(',').map(String::trim).filter(String::isNotEmpty).firstOrNull { !IDENTIFIER.matches(it) }
        return invalid?.let { "Invalid entity identifier: $it" }
    }

    fun persist() = store.persist()

    @Synchronized
    private fun load() {
        if (loaded) return
        enabled = store.boolean("enabled")
        whitelistText = store.string("entity_whitelist")
        whitelist = parseWhitelist(whitelistText)
        loaded = true
    }

    private fun parseWhitelist(value: String): Set<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    private val IDENTIFIER = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")
}
