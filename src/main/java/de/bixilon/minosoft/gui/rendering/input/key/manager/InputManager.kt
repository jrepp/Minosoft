/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.input.key.manager

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kutil.enums.BitEnumSet
import de.bixilon.kutil.time.TimeUtil.now
import de.bixilon.minosoft.config.key.KeyCodes
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.events.input.CharInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.KeyInputEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseMoveEvent
import de.bixilon.minosoft.gui.rendering.events.input.MouseScrollEvent
import de.bixilon.minosoft.gui.rendering.gui.input.ModifierKeys
import de.bixilon.minosoft.gui.rendering.input.CameraInput
import de.bixilon.minosoft.gui.rendering.input.interaction.InteractionManagerKeys
import de.bixilon.minosoft.gui.rendering.input.key.manager.binding.BindingsManager
import de.bixilon.minosoft.gui.rendering.system.window.KeyChangeTypes
import de.bixilon.minosoft.modding.EventPriorities
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.modding.loader.fabric.FabricCharInput
import de.bixilon.minosoft.modding.loader.fabric.FabricInputEventType
import de.bixilon.minosoft.modding.loader.fabric.FabricInputEvents
import de.bixilon.minosoft.modding.loader.fabric.FabricKeyBindings
import de.bixilon.minosoft.modding.loader.fabric.FabricKeyInput
import de.bixilon.minosoft.modding.loader.fabric.FabricMouseMoveInput
import de.bixilon.minosoft.modding.loader.fabric.FabricMouseScrollInput
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import java.util.*
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

class InputManager(
    val context: RenderContext,
) {
    private var time = now()
    val session: PlaySession = context.session
    val cameraInput = CameraInput(context, context.camera.matrix)
    val bindings = BindingsManager(this)
    val handler = InputHandlerManager(this)
    val interaction = InteractionManagerKeys(this, session.camera.interactions)


    private val pressed: BitEnumSet<KeyCodes> = KeyCodes.set()
    private val times: EnumMap<KeyCodes, ValueTimeMark> = EnumMap(KeyCodes::class.java)
    private var fabricKeyBindings: AutoCloseable? = null

    var mousePosition: Vec2f = Vec2f.EMPTY
        private set


    fun init() {
        check(fabricKeyBindings == null) { "Input manager is already initialized." }
        interaction.register()

        session.events.listen<CharInputEvent> { onChar(it.char) }
        session.events.listen<KeyInputEvent> { onKey(it.code, it.change) }
        session.events.listen<MouseScrollEvent>(priority = EventPriorities.LOW) { scroll(it.offset) }
        session.events.listen<MouseMoveEvent> { onMouse(it.delta, it.position) }

        fabricKeyBindings = FabricKeyBindings.attach(this)
        cameraInput.init()
    }

    fun unload() {
        try {
            fabricKeyBindings?.close()
        } finally {
            fabricKeyBindings = null
            clear()
        }
    }

    fun clear() {
        pressed.clear()
        times.clear()
        bindings.clear()
    }

    private fun onMouse(delta: Vec2f, position: Vec2f) {
        this.mousePosition = position
        val consumed = handler.onMouse(position)
        if (!consumed) cameraInput.updateMouse(delta)
        FabricInputEvents.dispatch(
            FabricInputEventType.MOUSE_MOVE,
            FabricMouseMoveInput(context, position, delta, consumed),
        )
    }

    private fun onKey(code: KeyCodes, change: KeyChangeTypes) {
        val handler = this.handler.handler
        this.handler.onKey(code, change)

        val pressed = when (change) {
            KeyChangeTypes.PRESS -> true
            KeyChangeTypes.RELEASE -> false
            KeyChangeTypes.REPEAT -> {
                FabricInputEvents.dispatch(
                    FabricInputEventType.KEY,
                    FabricKeyInput(context, code, change, handler != null),
                )
                return
            }
        }

        val time = now()


        if (pressed) {
            this.pressed += code
        } else {
            this.pressed -= code
        }

        bindings.onKey(code, pressed, handler, time)

        if (pressed) {
            times[code] = time
        }

        this.handler.checkSkip(code, pressed, handler)
        FabricInputEvents.dispatch(
            FabricInputEventType.KEY,
            FabricKeyInput(context, code, change, handler != null),
        )
    }

    private fun onChar(char: Int) {
        val consumed = handler.handler != null
        handler.onChar(char)
        FabricInputEvents.dispatch(FabricInputEventType.CHAR, FabricCharInput(context, char, consumed))
    }

    private fun scroll(scrollOffset: Vec2f) {
        val consumed = handler.onScroll(scrollOffset)
        FabricInputEvents.dispatch(
            FabricInputEventType.MOUSE_SCROLL,
            FabricMouseScrollInput(context, scrollOffset, consumed),
        )
    }

    fun areKeysDown(vararg keys: KeyCodes): Boolean {
        for (key in keys) {
            if (key !in pressed) {
                return false
            }
        }
        return true
    }

    fun areKeysDown(keys: Collection<KeyCodes>): Boolean {
        for (key in keys) {
            if (key !in pressed) {
                return false
            }
        }
        return true
    }

    fun isKeyDown(vararg keys: KeyCodes): Boolean {
        for (key in keys) {
            if (key in pressed) {
                return true
            }
        }
        return false
    }

    fun isKeyDown(modifier: ModifierKeys): Boolean {
        return isKeyDown(*modifier.codes)
    }

    fun draw() {
        val now = now()
        val delta = now - this.time
        this.time = now
        cameraInput.updateInput(delta)
        interaction.draw()
    }

    fun getLastPressed(key: KeyCodes) = times[key]
}
