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

package de.bixilon.minosoft.gui.rendering.entities.renderer

import de.bixilon.kmath.mat.mat4.f.MMat4f
import de.bixilon.kutil.math.interpolation.Interpolator
import de.bixilon.kutil.time.TimeUtil
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.text.formatting.color.ChatColors
import de.bixilon.minosoft.data.text.formatting.color.ColorInterpolation
import de.bixilon.minosoft.data.world.chunk.light.types.LightLevel
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawer
import de.bixilon.minosoft.gui.rendering.entities.easteregg.EntityEasterEggs.FLIP_ROTATION
import de.bixilon.minosoft.gui.rendering.entities.easteregg.EntityEasterEggs.isFlipped
import de.bixilon.minosoft.gui.rendering.entities.effect.EntityRenderEffects
import de.bixilon.minosoft.gui.rendering.entities.feature.EntityRenderFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.FeatureManager
import de.bixilon.minosoft.gui.rendering.entities.feature.hitbox.HitboxFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.flame.EntityFlameFeature
import de.bixilon.minosoft.gui.rendering.entities.feature.text.name.EntityNameFeature
import de.bixilon.minosoft.gui.rendering.entities.visibility.EntityVisibilityLevels
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic.ValueTimeMark

abstract class EntityRenderer<E : Entity>(
    val renderer: EntitiesRenderer,
    val entity: E,
) {
    private var update = TimeUtil.NULL
    val features = FeatureManager(this)
    val renderEffects = EntityRenderEffects(entity.dimensions.x * 0.5f)
    val info = entity.renderInfo
    var distance2 = 0.0

    val hitbox = HitboxFeature(this).register()
    val name = EntityNameFeature(this).register()
    val flame = EntityFlameFeature(this).register()
    val light = Interpolator(ChatColors.WHITE.rgb(), ColorInterpolation::interpolateRGB)
    val matrix = MMat4f()
    var visibility = EntityVisibilityLevels.OUT_OF_VIEW_DISTANCE
        protected set
    /**
     * Acceptance-only visibility override used by supervised render canaries.
     * Normal rendering leaves this null, so native frustum and occlusion
     * decisions remain authoritative.
     */
    @Volatile
    var referenceVisibilityOverride: EntityVisibilityLevels? = null

    fun <T : EntityRenderFeature> T.register(): T {
        features += this
        return this
    }

    protected open fun updateMatrix(delta: Duration) {
        val offset = renderer.context.camera.offset.offset
        val position = entity.renderInfo.position

        matrix.apply {
            clearAssign()
            translateAssign((position.x - offset.x).toFloat(), (position.y - offset.y).toFloat(), (position.z - offset.z).toFloat())

            if (entity.isFlipped()) {
                translateYAssign(entity.dimensions.y + 0.2f)
                rotateRadAssign(FLIP_ROTATION)
            }
        }
    }

    fun update(time: ValueTimeMark, auxiliaryVisible: Boolean = false) {
        val delta = if (this.update == TimeUtil.NULL) Duration.ZERO else (time - update)
        update(time, delta, auxiliaryVisible)
        this.update = time
    }

    open fun update(time: ValueTimeMark, delta: Duration, auxiliaryVisible: Boolean = false) {
        updateLight(delta)
        updateRenderInfo(time)
        updateMatrix(delta)
        features.update(delta, auxiliaryVisible)
    }

    open fun updateRenderInfo(time: ValueTimeMark) {
        entity.draw(time)
        this.distance2 = Vec3dUtil.distance2(entity.renderInfo.eyePosition, renderer.session.camera.entity.renderInfo.eyePosition)
    }

    private fun getCurrentLight(): LightLevel {
        val positionInfo = entity.physics.positionInfo
        if (positionInfo.chunk == null) return LightLevel.MAX
        var light = positionInfo.chunk.light[positionInfo.position.inChunkPosition]

        if (entity.isOnFire) {
            light = light.with(block = LightLevel.MAX_LEVEL)
        }

        return light
    }

    protected open fun updateLight(delta: Duration) {
        if (this.light.delta >= 1.0f) {
            val rgb = renderer.context.light.map.buffer[getCurrentLight().index]
            this.light.push(rgb)
        }
        light.add((delta / 1.seconds).toFloat(), 0.1f) // TODO: 1 second?
    }

    open fun enqueueUnload() {
        features.enqueueUnload()
    }

    open fun unload() {
        features.unload()
    }

    @Deprecated("What, why and how?")
    open fun invalidate() {
        features.invalidate()
    }

    open fun collect(drawer: EntityDrawer) {
        features.collect(drawer)
    }

    open fun collectShadow(drawer: EntityDrawer) {
        features.collectShadow(drawer)
    }

    open fun isVisibleTo(camera: Entity) = entity.isVisibleTo(camera)
    open fun isVisible() = visibility >= EntityVisibilityLevels.OCCLUDED && (
        isVisibleTo(renderer.context.session.camera.entity) || entity.hasGlowingEffect
    )

    open fun updateVisibility(level: EntityVisibilityLevels) {
        val effective = referenceVisibilityOverride ?: level
        this.visibility = effective
        features.updateVisibility(effective)
    }
}
