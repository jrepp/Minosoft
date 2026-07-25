/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.system.base.shader.code.glsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GLSLCommentStripperTest {
    @Test
    fun `operator-led continuation is not mistaken for a block comment`() {
        val comments = GLSLCommentStripper()

        assertEquals(
            comments.strip("    * getLight(floatBitsToUint(vinLight) & 0xFFu);"),
            "    * getLight(floatBitsToUint(vinLight) & 0xFFu);",
        )
    }

    @Test
    fun `block and line comments are removed without discarding surrounding code`() {
        val comments = GLSLCommentStripper()

        assertEquals(comments.strip("/* shader header"), "")
        assertEquals(comments.strip(" * continued */ uniform float value; // trailing"), " uniform float value; ")
        assertEquals(comments.strip("value = first() /* combine */ * second();"), "value = first()  * second();")
        assertEquals(comments.strip("left/**/right"), "left right")
    }

    @Test
    fun `comment markers inside quoted include paths are preserved`() {
        val comments = GLSLCommentStripper()

        assertEquals(comments.strip("#include \"demo/*literal*/path\""), "#include \"demo/*literal*/path\"")
        assertEquals(comments.strip("#include 'demo//literal/path'"), "#include 'demo//literal/path'")
    }
}
