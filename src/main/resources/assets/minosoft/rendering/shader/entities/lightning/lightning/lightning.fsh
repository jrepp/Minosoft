/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#version 330 core

in vec4 finTintColor;
out vec4 foutColor;

void main() {
    foutColor = finTintColor;
}
