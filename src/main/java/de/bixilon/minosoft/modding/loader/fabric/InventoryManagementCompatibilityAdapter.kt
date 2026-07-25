/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package de.bixilon.minosoft.modding.loader.fabric

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.config.settings.BooleanConfigControl
import de.bixilon.minosoft.config.settings.ConfigEntry
import de.bixilon.minosoft.config.settings.SettingsSchema
import de.bixilon.minosoft.data.container.types.PlayerInventory
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.registries.identified.Namespaces.minosoft
import de.bixilon.minosoft.gui.rendering.gui.GUIRenderer
import de.bixilon.minosoft.gui.rendering.gui.elements.Element
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.AbstractButtonElement
import de.bixilon.minosoft.gui.rendering.gui.elements.input.button.ButtonElement
import de.bixilon.minosoft.gui.rendering.gui.gui.AbstractLayout
import de.bixilon.minosoft.gui.rendering.gui.mesh.GUIVertexOptions
import de.bixilon.minosoft.gui.rendering.gui.mesh.consumer.GuiVertexConsumer
import de.bixilon.minosoft.gui.rendering.util.vec.vec2.Vec2Util.isGreater
import de.bixilon.minosoft.gui.rendering.util.vec.vec2.Vec2Util.isSmaller
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

object InventoryManagementCompatibilityAdapter : FabricCompatibilityAdapter {
    override val id = "minosoft:inventorymanagement-1.5.0-mc1.20.4"
    override val handledBlockers = FabricCompatibilityBlocker.entries.toSet()
    override val capabilities = setOf(FabricHostCapability.CONTAINER_SCREEN_EXTENSIONS)
    override val functionality = FabricFunctionalityCatalog.INVENTORY_MANAGEMENT

    override fun supports(metadata: FabricMetadata): Boolean {
        return metadata.id == "inventorymanagement" &&
            metadata.version == "1.5.0" &&
            metadata.environment == "*" &&
            metadata.entrypoints.containsAll(setOf("main", "client", "modmenu")) &&
            metadata.mixins == 2 &&
            metadata.accessWidener == null &&
            metadata.nestedJars == 0
    }

    override fun activate(probe: FabricModProbe, scope: FabricRegistrationScope) {
        require(supports(probe.metadata)) { "Unsupported Inventory Management artifact: ${probe.metadata.version}" }
        for (operation in InventoryManagementOperation.entries) {
            val kind = operation.diagnosticKind
            FabricModDiagnostics.hookInstalled(id, kind)
            scope.own(AutoCloseable { FabricModDiagnostics.hookUninstalled(id, kind) })
        }
        scope.own(
            FabricContainerScreenExtensions.register(id, minosoft("inventory_management")) { context ->
                FabricContainerScreenExtension(
                    offset = Vec2f(context.contentSize.x + 7.0f, 8.0f),
                    element = InventoryManagementControlsElement(
                        context.renderer,
                        context.container is PlayerInventory,
                        InventoryManagementOptions.sortButtons(),
                        InventoryManagementOptions.transferButtons(),
                        InventoryManagementOptions.stackButtons(),
                    ),
                )
            },
        )
        scope.own(
            FabricSettings.register(id, minosoft("inventory_management_options"), "Inventory Management settings") {
                SettingsSchema(
                    title = "Inventory Management settings",
                    entries = listOf(
                        ConfigEntry(
                            id = "sort_buttons",
                            label = "Sort buttons",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = InventoryManagementOptions::sortButtons,
                            write = InventoryManagementOptions::setSortButtons,
                        ),
                        ConfigEntry(
                            id = "transfer_buttons",
                            label = "Bulk transfer buttons",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = InventoryManagementOptions::transferButtons,
                            write = InventoryManagementOptions::setTransferButtons,
                        ),
                        ConfigEntry(
                            id = "stack_buttons",
                            label = "Auto-stack buttons",
                            defaultValue = true,
                            control = BooleanConfigControl,
                            read = InventoryManagementOptions::stackButtons,
                            write = InventoryManagementOptions::setStackButtons,
                        ),
                    ),
                    persist = InventoryManagementOptions::persist,
                )
            },
        )
        Log.log(LogMessageType.MOD_LOADING, LogLevels.INFO) {
            "INVENTORY_MANAGEMENT_HOOK_INSTALLED hook=container-screen-extensions protocol=inventorymanagement-1.5"
        }
    }
}

internal enum class InventoryManagementOperation(
    val label: String,
    val channel: ResourceLocation,
) {
    SORT("Sort", ResourceLocation("inventorymanagement", "sort_inventory_packet")),
    TRANSFER("all", ResourceLocation("inventorymanagement", "transfer_all_packet")),
    STACK("stack", ResourceLocation("inventorymanagement", "auto_stack_packet")),
    ;

    val diagnosticKind: String get() = "inventory-operation:${name.lowercase()}"
}

internal object InventoryManagementProtocol {
    fun send(renderer: GUIRenderer, operation: InventoryManagementOperation, playerInventory: Boolean) {
        val started = System.nanoTime()
        try {
            FabricClientPayloadChannels.send(
                renderer.session,
                operation.channel,
                byteArrayOf(if (playerInventory) 1 else 0),
            )
        } finally {
            FabricModDiagnostics.hookInvoked(
                InventoryManagementCompatibilityAdapter.id,
                operation.diagnosticKind,
                System.nanoTime() - started,
            )
        }
    }
}

internal class InventoryManagementControlsElement(
    guiRenderer: GUIRenderer,
    playerInventoryOnly: Boolean,
    sortButtons: Boolean = true,
    transferButtons: Boolean = true,
    stackButtons: Boolean = true,
) : Element(guiRenderer, 24), AbstractLayout<AbstractButtonElement> {
    private data class PositionedButton(val button: AbstractButtonElement, val offset: Vec2f)

    private val buttons = mutableListOf<PositionedButton>()
    override var activeElement: AbstractButtonElement? = null
    override var activeDragElement: AbstractButtonElement? = null

    init {
        if (sortButtons) add("Sort inventory", InventoryManagementOperation.SORT, true)
        if (!playerInventoryOnly) {
            if (sortButtons) add("Sort container", InventoryManagementOperation.SORT, false)
            if (transferButtons) {
                add("Put all", InventoryManagementOperation.TRANSFER, true)
                add("Take all", InventoryManagementOperation.TRANSFER, false)
            }
            if (stackButtons) {
                add("Stack in", InventoryManagementOperation.STACK, true)
                add("Stack out", InventoryManagementOperation.STACK, false)
            }
        }
        _size = Vec2f(BUTTON_WIDTH, maxOf(0.0f, buttons.size * (BUTTON_HEIGHT + SPACING) - SPACING))
    }

    private fun add(label: String, operation: InventoryManagementOperation, playerInventory: Boolean) {
        val button = ButtonElement(guiRenderer, label) {
            InventoryManagementProtocol.send(guiRenderer, operation, playerInventory)
        }.apply {
            size = Vec2f(BUTTON_WIDTH, BUTTON_HEIGHT)
            parent = this@InventoryManagementControlsElement
        }
        buttons += PositionedButton(button, Vec2f(0.0f, buttons.size * (BUTTON_HEIGHT + SPACING)))
    }

    override fun forceRender(offset: Vec2f, consumer: GuiVertexConsumer, options: GUIVertexOptions?) {
        for ((button, buttonOffset) in buttons) button.render(offset + buttonOffset, consumer, options)
    }

    override fun forceSilentApply() {
        buttons.forEach { it.button.silentApply() }
        cacheUpToDate = false
    }

    override fun getAt(position: Vec2f): Pair<AbstractButtonElement, Vec2f>? {
        for ((button, offset) in buttons) {
            if (position isSmaller offset) continue
            val inner = position - offset
            if (inner isGreater button.size) continue
            return Pair(button, inner)
        }
        return null
    }

    private companion object {
        const val BUTTON_WIDTH = 78.0f
        const val BUTTON_HEIGHT = 16.0f
        const val SPACING = 2.0f
    }
}

private object InventoryManagementOptions {
    private val store by lazy {
        FabricAdapterOptionStore(
            "inventorymanagement",
            mapOf(
                "sort_buttons" to "true",
                "transfer_buttons" to "true",
                "stack_buttons" to "true",
            ),
        )
    }

    fun sortButtons() = store.boolean("sort_buttons")
    fun transferButtons() = store.boolean("transfer_buttons")
    fun stackButtons() = store.boolean("stack_buttons")
    fun setSortButtons(value: Boolean) = store.set("sort_buttons", value)
    fun setTransferButtons(value: Boolean) = store.set("transfer_buttons", value)
    fun setStackButtons(value: Boolean) = store.set("stack_buttons", value)
    fun persist() = store.persist()
}
