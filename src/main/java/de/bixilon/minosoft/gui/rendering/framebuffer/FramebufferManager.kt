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

package de.bixilon.minosoft.gui.rendering.framebuffer

import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.framebuffer.gui.GUIFramebuffer
import de.bixilon.minosoft.gui.rendering.framebuffer.world.MainWorldTarget
import de.bixilon.minosoft.gui.rendering.renderer.drawable.Drawable
import de.bixilon.minosoft.gui.rendering.system.base.PolygonModes

class FramebufferManager(
    private val context: RenderContext,
) : Drawable {
    val main = MainWorldTarget(context)
    val gui = GUIFramebuffer(context)
    private var mainInitialized = false
    private var guiInitialized = false


    fun init() {
        mainInitialized = true
        main.init()
        guiInitialized = true
        gui.init()

        context.window::size.observe(this, true) {
            main.size = it
            gui.size = it
        }
    }

    fun postInit() {
        main.postInit()
        gui.postInit()
    }


    fun clear() {
        main.clear()
        gui.clear()
    }

    fun update() {
        main.update()
        gui.update()
    }

    fun unload() {
        var failure: Throwable? = null
        if (guiInitialized) {
            try {
                gui.unload()
            } catch (error: Throwable) {
                failure = error
            }
            guiInitialized = false
        }
        if (mainInitialized) {
            try {
                main.unload()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
            mainInitialized = false
        }
        failure?.let { throw it }
    }


    override fun draw() {
        context.system.framebuffer = null
        context.system.polygonMode = PolygonModes.FILL

        main.draw()
        gui.draw()
    }
}
