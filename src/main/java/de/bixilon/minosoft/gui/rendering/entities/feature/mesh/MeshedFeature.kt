/*
 * Minosoft
 * Copyright (C) 2020-2026 Moritz Zwerger
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

package de.bixilon.minosoft.gui.rendering.entities.feature.mesh

import de.bixilon.minosoft.gui.rendering.entities.feature.DrawableEntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.renderer.EntityRenderer
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.util.mesh.Mesh
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates

abstract class MeshedFeature<M : Mesh>(
    renderer: EntityRenderer<*>,
) : DrawableEntityRenderFeature(renderer) {
    protected var unload = false
    private var suppressMeshUnload = false
    protected open var mesh: M? = null
        set(value) {
            val old = field
            if (old != null && !suppressMeshUnload) {
                enqueueUnload(old)
            }
            field = value
            this.unload = false
        }


    override fun updateVisibility(level: EntityVisibilityLevels) {
        super.updateVisibility(level)
        if (level <= EntityVisibilityLevels.OUT_OF_VIEW_DISTANCE) {
            unload = true
        }
    }

    private fun enqueueUnload(mesh: M) {
        when (mesh.state) {
            MeshStates.PREPARING -> mesh.drop()
            MeshStates.LOADED -> renderer.renderer.queue += {
                if (mesh.state == MeshStates.LOADED) {
                    mesh.unload()
                }
            }
            MeshStates.UNLOADED -> Unit
        }
    }

    private fun clearMeshWithoutEnqueue() {
        suppressMeshUnload = true
        try {
            this.mesh = null
        } finally {
            suppressMeshUnload = false
        }
    }

    override fun enqueueUnload() {
        super.enqueueUnload()
        if (unload) {
            this.mesh = null
        }
    }

    override fun invalidate() {
        super.invalidate()
        unload = true
    }

    override fun prepare() {
        super.prepare()
        val mesh = this.mesh
        if (mesh != null && mesh.state == MeshStates.PREPARING) {
            mesh.load()
        }
    }

    override fun draw() {
        val mesh = this.mesh ?: return
        draw(mesh)
    }

    protected open fun draw(mesh: M) {
        mesh.draw()
    }

    override fun unload() {
        super.unload()

        val mesh = this.mesh ?: return
        when (mesh.state) {
            MeshStates.PREPARING -> mesh.drop()
            MeshStates.LOADED -> mesh.unload()
            MeshStates.UNLOADED -> Unit
        }
        clearMeshWithoutEnqueue()
    }
}
