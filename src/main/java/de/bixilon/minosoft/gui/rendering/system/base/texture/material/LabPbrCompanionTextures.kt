/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.material

import de.bixilon.kmath.vec.vec2.i.Vec2i
import de.bixilon.kutil.time.DurationUtil.rem
import de.bixilon.kutil.time.DurationUtil.sumOf
import de.bixilon.minosoft.assets.AssetsManager
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.RenderContext
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.AnimationFrame
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.SpriteUtil.mapNext
import de.bixilon.minosoft.gui.rendering.system.base.texture.animator.TextureAnimation
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.MipmapTextureData
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.TextureData
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.RGBA8Buffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.data.buffer.TextureBuffer
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.FileTextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.FileTextureLoader.Companion.readImageProperties
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.readTexture
import de.bixilon.minosoft.gui.rendering.textures.properties.AnimationProperties
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import kotlin.math.floor
import kotlin.time.Duration

/**
 * Resolves the LabPBR `_n` and `_s` files associated with an ordinary resource
 * pack texture. The OpenGL static array stores these in fixed vertical pages
 * inside the diffuse layer, retaining one sampler and one array layer for all
 * three material channels.
 */
internal object LabPbrCompanionTextures {
    const val MATERIAL_PAGES = 3

    enum class Kind(
        val suffix: String,
        val pageOffset: Int,
        val neutral: IntArray,
    ) {
        NORMAL("_n", 1, intArrayOf(128, 128, 255, 255)),
        SPECULAR("_s", 2, intArrayOf(0, 0, 0, 255)),
    }

    data class Loaded(
        val data: TextureData,
        val resource: ResourceLocation?,
        val animation: Animated? = null,
    )

    /**
     * A companion sprite owns its authored frame sequence but follows the
     * diffuse sprite's timeline when the diffuse texture is animated. This
     * mirrors Iris's PBR atlas synchronization without coupling material data
     * to the diffuse [TextureAnimation] frame list.
     */
    class Animated internal constructor(
        val data: TextureData,
        private val frames: Array<AnimationFrame>,
        private val interpolate: Boolean,
    ) {
        private val total = frames.sumOf { it.time }
        private val frameEnds = Array(frames.size) { Duration.ZERO }
        private var timelinePosition = Duration.ZERO
        @Volatile
        var frameIndex: Int = 0
            private set
        @Volatile
        var revision: Long = 0L
            private set

        init {
            require(frames.isNotEmpty() && total > Duration.ZERO) {
                "LabPBR companion animation must contain positive-duration frames."
            }
            var end = Duration.ZERO
            for ((index, frame) in frames.withIndex()) {
                end += frame.time
                frameEnds[index] = end
            }
        }

        fun advance(delta: Duration, synchronizedPosition: Duration? = null) {
            val nextPosition = if (synchronizedPosition == null) {
                (timelinePosition + delta) % total
            } else {
                synchronizedPosition % total
            }
            if (interpolate && nextPosition == timelinePosition) return
            timelinePosition = nextPosition

            var low = 0
            var high = frameEnds.lastIndex
            while (low < high) {
                val middle = (low + high) ushr 1
                if (timelinePosition < frameEnds[middle]) {
                    high = middle
                } else {
                    low = middle + 1
                }
            }
            if (!interpolate && low == frameIndex) return
            frameIndex = low
            val start = if (frameIndex == 0) Duration.ZERO else frameEnds[frameIndex - 1]
            val frame = frames[frameIndex]
            val progress = if (timelinePosition == start) {
                0.0f
            } else {
                ((timelinePosition - start) / frame.time).toFloat()
            }
            val destination = data.collect()
            val current = frame.data.collect()
            val next = frame.next.data.collect()
            require(destination.size == current.size && destination.size == next.size) {
                "LabPBR companion animation mip levels are inconsistent."
            }
            for (index in destination.indices) {
                destination[index].interpolate(
                    current[index],
                    next[index],
                    if (interpolate) progress else 0.0f,
                )
            }
            revision = Math.addExact(revision, 1L)
        }
    }

    fun resource(loader: TextureLoader, kind: Kind): ResourceLocation? {
        val file = (loader as? FileTextureLoader)?.file ?: return null
        if (!file.path.endsWith(".png")) return null
        val stem = file.path.removeSuffix(".png")
        if (stem.endsWith(Kind.NORMAL.suffix) || stem.endsWith(Kind.SPECULAR.suffix)) return null
        return ResourceLocation(file.namespace, "$stem${kind.suffix}.png")
    }

    fun load(context: RenderContext, texture: Texture, kind: Kind): Loaded {
        return load(
            assets = context.session.assets,
            loader = texture.loader,
            expectedSize = texture.size,
            mipmaps = texture.mipmaps,
            kind = kind,
        )
    }

    fun load(
        assets: AssetsManager,
        loader: TextureLoader,
        expectedSize: Vec2i,
        mipmaps: Int,
        kind: Kind,
    ): Loaded {
        val resource = resource(loader, kind)
        if (resource != null) {
            val stream = assets.getOrNull(resource)
            if (stream != null) {
                val buffer = stream.use { it.readTexture() }
                val properties = assets.readImageProperties(resource)?.animation
                if (properties != null) {
                    try {
                        val animation = animation(buffer, properties, expectedSize, mipmaps)
                        return Loaded(animation.data, resource, animation)
                    } catch (error: Throwable) {
                        Log.log(LogMessageType.LOADING, LogLevels.WARN) {
                            "Ignoring animated LabPBR companion $resource: ${error.message}"
                        }
                    }
                } else {
                    return Loaded(createData(scale(buffer, expectedSize), mipmaps), resource)
                }
            }
        }

        val neutral = neutral(kind, expectedSize)
        return Loaded(createData(neutral, mipmaps), null)
    }

    fun neutral(kind: Kind, size: Vec2i): RGBA8Buffer {
        val buffer = RGBA8Buffer(size)
        buffer.fill(kind.neutral[0], kind.neutral[1], kind.neutral[2], kind.neutral[3])
        return buffer
    }

    private fun animation(
        source: TextureBuffer,
        properties: AnimationProperties,
        expectedSize: Vec2i,
        mipmaps: Int,
    ): Animated {
        val descriptor = properties.create(source.size)
        val rows = source.size.y / descriptor.size.y
        val scaled = scale(
            source,
            Vec2i(
                descriptor.columns * expectedSize.x,
                rows * expectedSize.y,
            ),
        )
        val sprites = Array(descriptor.textures) { index ->
            val buffer = scaled.create(expectedSize)
            val origin = Vec2i(
                (index % descriptor.columns) * expectedSize.x,
                (index / descriptor.columns) * expectedSize.y,
            )
            buffer.put(scaled, origin, Vec2i.EMPTY, expectedSize)
            createData(buffer, mipmaps)
        }
        val frames = Array(descriptor.frames.size) { index ->
            val frame = descriptor.frames[index]
            AnimationFrame(frame.time, sprites[frame.texture])
        }
        frames.mapNext()
        return Animated(frames.first().data.copy(), frames, properties.interpolate)
    }

    /**
     * Iris scales a companion's complete frame grid to the diffuse sprite's
     * frame size before slicing it. Preserve pixel-art edges for integral
     * scale factors and use bilinear sampling for non-integral pack assets.
     */
    private fun scale(source: TextureBuffer, targetSize: Vec2i): TextureBuffer {
        if (source.size == targetSize) return source
        require(source.size.x > 0 && source.size.y > 0) {
            "LabPBR companion dimensions must be positive: ${source.size}"
        }
        require(targetSize.x > 0 && targetSize.y > 0) {
            "LabPBR target dimensions must be positive: $targetSize"
        }
        val target = source.create(targetSize)
        val integral = targetSize.x % source.size.x == 0 &&
            targetSize.y % source.size.y == 0
        for (y in 0 until targetSize.y) {
            for (x in 0 until targetSize.x) {
                if (integral) {
                    val sourceX = x * source.size.x / targetSize.x
                    val sourceY = y * source.size.y / targetSize.y
                    target.setRGBA(x, y, source.getRGBA(sourceX, sourceY))
                    continue
                }
                val sourceX = ((x + 0.5) * source.size.x / targetSize.x - 0.5)
                    .coerceIn(0.0, (source.size.x - 1).toDouble())
                val sourceY = ((y + 0.5) * source.size.y / targetSize.y - 0.5)
                    .coerceIn(0.0, (source.size.y - 1).toDouble())
                val x0 = floor(sourceX).toInt()
                val y0 = floor(sourceY).toInt()
                val x1 = (x0 + 1).coerceAtMost(source.size.x - 1)
                val y1 = (y0 + 1).coerceAtMost(source.size.y - 1)
                val xWeight = sourceX - x0
                val yWeight = sourceY - y0
                val top = source.getRGBA(x0, y0).mix(source.getRGBA(x1, y0), xWeight.toFloat())
                val bottom = source.getRGBA(x0, y1).mix(source.getRGBA(x1, y1), xWeight.toFloat())
                target.setRGBA(x, y, top.mix(bottom, yWeight.toFloat()))
            }
        }
        return target
    }

    private fun createData(buffer: TextureBuffer, mipmaps: Int): TextureData =
        if (mipmaps <= 0) TextureData(buffer) else MipmapTextureData(buffer, mipmaps)
}
