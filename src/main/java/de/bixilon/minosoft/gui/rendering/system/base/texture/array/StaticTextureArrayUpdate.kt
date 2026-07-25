/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.texture.array

import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.TextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.loader.file.PNGTextureLoader
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture

/**
 * A render-thread transaction for refreshing existing static textures and
 * appending new names without changing any already-published shader
 * coordinates.
 *
 * [upload] prepares replacement GPU storage while the old storage remains
 * active. [publish] switches lookup and GPU state to the candidate. [complete]
 * makes that switch final. Closing before completion rolls the publication
 * back and releases candidate resources.
 */
interface StaticTextureArrayUpdate : AutoCloseable {
    fun resolve(
        name: ResourceLocation,
        mipmaps: Boolean = true,
        loader: TextureLoader = PNGTextureLoader(name),
    ): Texture

    /**
     * Retains an already-loaded texture used by this generation without
     * refreshing its pixels.
     */
    fun retain(texture: Texture): Texture

    fun upload()
    fun publish()

    /**
     * Finalizes publication and returns the generation lifetime for every
     * managed shader slot referenced by this transaction.
     */
    fun complete(): AutoCloseable
}
