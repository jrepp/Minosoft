/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.entities.outline

import de.bixilon.kmath.vec.vec2.f.Vec2f
import de.bixilon.minosoft.gui.rendering.framebuffer.FramebufferShader
import de.bixilon.minosoft.gui.rendering.system.base.shader.NativeShader

class EntityOutlineShader(native: NativeShader) : FramebufferShader(native) {
    var texelSize by uniform("uTexelSize", Vec2f.EMPTY)
}
