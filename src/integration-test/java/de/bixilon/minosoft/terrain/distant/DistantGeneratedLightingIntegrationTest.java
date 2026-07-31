/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the license, or (at your option) any later
 * version.
 */

package de.bixilon.minosoft.terrain.distant;

import org.testng.annotations.Test;

/** Runs the detached generated-lighting boundary without the legacy group graph. */
public final class DistantGeneratedLightingIntegrationTest {
    @Test
    public void generatedPageUsesDetachedHaloLighting() {
        new DistantGeneratedLightingTest().assertGeneratedPageUsesDetachedHaloLighting();
    }
}
