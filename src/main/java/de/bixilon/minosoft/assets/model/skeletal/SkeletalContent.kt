/*
 * Minosoft
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

package de.bixilon.minosoft.assets.model.skeletal

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.minosoft.data.Axes
import de.bixilon.minosoft.data.direction.Directions
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.assets.model.skeletal.expression.SkeletalExpression

/**
 * Renderer-independent skeletal content. Parsers may create this in headless
 * mode; renderers consume it through an explicit bake/binding adapter.
 */
data class SkeletalContent(
    val source: ResourceLocation,
    val format: SkeletalContentFormat,
    val formatVersion: String?,
    val identifier: String,
    val textureSize: Vec2i,
    val texture: String? = null,
    val roots: List<SkeletalBone>,
    val animations: Map<String, SkeletalAnimationClip> = emptyMap(),
    val expressions: List<SkeletalExpressionBinding> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(identifier.isNotBlank()) { "Skeletal content identifier must not be blank." }
        require(textureSize.x > 0 && textureSize.y > 0) { "Skeletal texture size must be positive: $textureSize" }

        val names = HashSet<String>()
        fun visit(bone: SkeletalBone) {
            require(bone.name.isNotBlank()) { "Skeletal bone name must not be blank." }
            require(names.add(bone.name)) { "Duplicate skeletal bone: ${bone.name}" }
            bone.children.forEach(::visit)
        }
        roots.forEach(::visit)
    }

    val bones: Map<String, SkeletalBone> by lazy {
        val result = LinkedHashMap<String, SkeletalBone>()
        fun collect(bone: SkeletalBone) {
            result[bone.name] = bone
            bone.children.forEach(::collect)
        }
        roots.forEach(::collect)
        result
    }
}

data class SkeletalContentDocument(
    val source: ResourceLocation,
    val format: SkeletalContentFormat,
    val formatVersion: String?,
    val models: List<SkeletalContent>,
)

data class SkeletalContentIdentity(
    val source: ResourceLocation,
    val format: SkeletalContentFormat,
    val identifier: String,
)

enum class SkeletalContentFormat {
    MINOSOFT,
    OPTIFINE_CEM,
    GECKOLIB,
}

data class SkeletalBone(
    val name: String,
    val target: String? = null,
    val attach: Boolean = false,
    val pivot: Vec3f = Vec3f.EMPTY,
    val rotation: Vec3f = Vec3f.EMPTY,
    val scale: Vec3f = Vec3f(1.0f),
    val invertAxes: Set<Axes> = emptySet(),
    val mirrorTextureU: Boolean = false,
    val mirrorTextureV: Boolean = false,
    val cubes: List<SkeletalCube> = emptyList(),
    val children: List<SkeletalBone> = emptyList(),
) {
    init {
        require(pivot.finite() && rotation.finite() && scale.finite()) {
            "Skeletal bone transforms must be finite: $name"
        }
    }
}

data class SkeletalCube(
    val origin: Vec3f,
    val size: Vec3f,
    val pivot: Vec3f? = null,
    val rotation: Vec3f = Vec3f.EMPTY,
    val inflate: Vec3f = Vec3f.EMPTY,
    val mirror: Boolean = false,
    val material: String? = null,
    val uv: SkeletalUv,
) {
    init {
        require(origin.finite() && size.finite() && pivot?.finite() != false && rotation.finite() && inflate.finite()) {
            "Skeletal cube transforms must be finite."
        }
        require(size.x >= 0.0f && size.y >= 0.0f && size.z >= 0.0f) {
            "Skeletal cube size must not be negative: $size"
        }
    }
}

sealed interface SkeletalUv {
    data class Box(val offset: Vec2f) : SkeletalUv
    data class Faces(val faces: Map<Directions, SkeletalFaceUv>) : SkeletalUv
}

data class SkeletalFaceUv(
    val offset: Vec2f,
    val size: Vec2f,
    val rotation: Int = 0,
    val material: String? = null,
) {
    init {
        require(offset.x.isFinite() && offset.y.isFinite() && size.x.isFinite() && size.y.isFinite()) {
            "Skeletal face UV coordinates must be finite."
        }
    }
}

data class SkeletalAnimationClip(
    val name: String,
    val lengthSeconds: Float,
    val loop: SkeletalAnimationLoop,
    val channels: Map<String, List<SkeletalAnimationChannel>>,
    val events: List<SkeletalAnimationEvent> = emptyList(),
    val sourceLoopType: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Animation name must not be blank." }
        require(lengthSeconds.isFinite() && lengthSeconds >= 0.0f) {
            "Animation length must be finite and non-negative."
        }
        require(sourceLoopType == null || sourceLoopType.isNotBlank() && sourceLoopType.length <= MAX_LOOP_TYPE_LENGTH) {
            "Animation source loop type must contain 1..$MAX_LOOP_TYPE_LENGTH characters."
        }
        require(events.zipWithNext().all { (left, right) -> left.timeSeconds <= right.timeSeconds }) {
            "Animation events must be ordered by time."
        }
    }

    private companion object {
        const val MAX_LOOP_TYPE_LENGTH = 256
    }
}

data class SkeletalAnimationEvent(
    val timeSeconds: Float,
    val type: SkeletalAnimationEventType,
    val payload: String,
    val locator: String? = null,
    val preEffectScript: String? = null,
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0f) {
            "Animation event time must be finite and non-negative."
        }
        require(payload.length <= MAX_PAYLOAD_LENGTH) {
            "Animation event payload exceeds $MAX_PAYLOAD_LENGTH characters."
        }
        require(locator == null || locator.length <= MAX_PAYLOAD_LENGTH) {
            "Animation event locator exceeds $MAX_PAYLOAD_LENGTH characters."
        }
        require(preEffectScript == null || preEffectScript.length <= MAX_PAYLOAD_LENGTH) {
            "Animation event script exceeds $MAX_PAYLOAD_LENGTH characters."
        }
    }

    private companion object {
        const val MAX_PAYLOAD_LENGTH = 16_384
    }
}

enum class SkeletalAnimationEventType {
    SOUND,
    PARTICLE,
    CUSTOM_INSTRUCTION,
}

enum class SkeletalAnimationLoop {
    ONCE,
    LOOP,
    HOLD,
}

data class SkeletalAnimationChannel(
    val target: SkeletalAnimationTarget,
    val keyframes: List<SkeletalAnimationKeyframe>,
)

enum class SkeletalAnimationTarget {
    ROTATION,
    TRANSLATION,
    SCALE,
}

data class SkeletalAnimationKeyframe(
    val timeSeconds: Float,
    val value: SkeletalVectorValue,
    val interpolation: SkeletalInterpolation = SkeletalInterpolation.LINEAR,
    val easing: String? = null,
    val easingArguments: List<Float> = emptyList(),
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0f) {
            "Keyframe time must be finite and non-negative."
        }
        require(easingArguments.size <= MAX_EASING_ARGUMENTS && easingArguments.all(Float::isFinite)) {
            "A skeletal keyframe may contain at most $MAX_EASING_ARGUMENTS finite easing arguments."
        }
    }

    private companion object {
        const val MAX_EASING_ARGUMENTS = 16
    }
}

sealed interface SkeletalVectorValue {
    data class Constant(val value: Vec3f) : SkeletalVectorValue {
        init {
            require(value.finite()) { "A constant skeletal vector must be finite." }
        }
    }
    data class Expression(val components: List<String>) : SkeletalVectorValue {
        init {
            require(components.size == 3) { "A skeletal vector expression requires three components." }
        }
    }
}

private fun Vec3f.finite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

enum class SkeletalInterpolation {
    LINEAR,
    CATMULL_ROM,
    STEP,
}

data class SkeletalExpressionBinding(
    val owner: String,
    val target: String,
    val expression: String,
    val compiled: SkeletalExpression = SkeletalExpression.compile(expression),
)
