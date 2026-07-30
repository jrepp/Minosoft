/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */
package de.bixilon.minosoft.data.entities.entities.monster

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.minosoft.data.entities.EntityRotation
import de.bixilon.minosoft.data.entities.data.EntityData
import de.bixilon.minosoft.data.entities.data.EntityDataField
import de.bixilon.minosoft.data.entities.entities.SynchronizedEntityData
import de.bixilon.minosoft.data.registries.entities.EntityFactory
import de.bixilon.minosoft.data.registries.entities.EntityType
import de.bixilon.minosoft.data.registries.identified.Namespaces.minecraft
import de.bixilon.minosoft.protocol.network.session.play.PlaySession

class Creeper(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation) : Monster(session, entityType, data, position, rotation) {
    private val fuseAnimation = CreeperFuseAnimation()

    @Volatile
    private var debugWhiteOverlayFuseTicks: Int? = null

    @get:SynchronizedEntityData
    val fuseState: Int
        get() = data.get(FUSE_STATE_DATA, -1)

    @get:SynchronizedEntityData
    val isCharged: Boolean
        get() = data.getBoolean(IS_CHARGED_DATA, false)

    @get:SynchronizedEntityData
    val isIgnited: Boolean
        get() = data.getBoolean(IS_IGNITED_DATA, false)

    override fun tick() {
        fuseAnimation.tick(if (isIgnited) 1 else fuseState)
        super.tick()
    }

    override fun whiteOverlayProgress(partialTick: Float): Float {
        val debugTicks = debugWhiteOverlayFuseTicks
        if (debugTicks != null) {
            return CreeperFuseAnimation.whiteOverlayProgress(debugTicks, debugTicks, partialTick)
        }
        return fuseAnimation.whiteOverlayProgress(partialTick)
    }

    /**
     * Bounded client-debug override used only by the retained-render canary.
     * Production fuse counters continue ticking and become visible again when
     * the returned previous value is restored.
     */
    @Synchronized
    fun setWhiteOverlayFuseTicksForDebug(ticks: Int?): Int? {
        require(ticks == null || ticks in 0..CreeperFuseAnimation.FUSE_TIME)
        val previous = debugWhiteOverlayFuseTicks
        debugWhiteOverlayFuseTicks = ticks
        return previous
    }

    companion object : EntityFactory<Creeper> {
        override val identifier = minecraft("creeper")
        private val FUSE_STATE_DATA = EntityDataField("CREEPER_STATE")
        private val IS_CHARGED_DATA = EntityDataField("CREEPER_IS_CHARGED")
        private val IS_IGNITED_DATA = EntityDataField("CREEPER_IS_IGNITED")

        override fun build(session: PlaySession, entityType: EntityType, data: EntityData, position: Vec3d, rotation: EntityRotation): Creeper {
            return Creeper(session, entityType, data, position, rotation)
        }
    }
}

/**
 * Vanilla 1.20.4's client-side creeper fuse interpolation and renderer blink
 * function. The server supplies only the signed fuse speed; the client owns
 * these two tick counters.
 */
class CreeperFuseAnimation {
    private var previousFuseTime = 0
    private var currentFuseTime = 0

    fun tick(fuseSpeed: Int) {
        previousFuseTime = currentFuseTime
        currentFuseTime = (currentFuseTime + fuseSpeed).coerceIn(0, FUSE_TIME)
    }

    fun whiteOverlayProgress(partialTick: Float): Float =
        whiteOverlayProgress(previousFuseTime, currentFuseTime, partialTick)

    companion object {
        const val FUSE_TIME = 30
        private const val FUSE_PROGRESS_DENOMINATOR = FUSE_TIME - 2

        fun whiteOverlayProgress(previousFuseTime: Int, currentFuseTime: Int, partialTick: Float): Float {
            val delta = partialTick.coerceIn(0.0f, 1.0f)
            val fuse = (previousFuseTime + (currentFuseTime - previousFuseTime) * delta) /
                FUSE_PROGRESS_DENOMINATOR.toFloat()
            if ((fuse * 10.0f).toInt() % 2 == 0) return 0.0f
            return fuse.coerceIn(0.5f, 1.0f)
        }
    }
}
