/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

out lowp vec4 foutColor;

#include "minosoft:tint"
#include "minosoft:texture"
#include "minosoft:alpha"
#include "minosoft:animation"

void main() {
    applyDefaults();
    applyTint();
    applyTexel();
}
