/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MotionNoiseAnalyzerTest {
    @Test
    void identicalFramesHaveNoNoise() {
        BufferedImage frame = solid(8, 8, 0xFF808080);

        MotionNoiseAnalyzer.Metrics metrics = MotionNoiseAnalyzer.compare(frame, frame, null, 8, 0);

        assertEquals(0L, metrics.changedPixels());
        assertEquals(0.0, metrics.changedRatio());
        assertEquals(0.0, metrics.meanAbsoluteLumaError());
        assertEquals(36L, metrics.flatPixels());
        assertEquals(0.0, metrics.flatChangedRatio());
    }

    @Test
    void saltAndPepperNoiseIsMeasuredInsideFlatRegions() {
        BufferedImage reference = solid(10, 10, 0xFF808080);
        BufferedImage observed = solid(10, 10, 0xFF808080);
        observed.setRGB(4, 4, 0xFF000000);
        observed.setRGB(5, 5, 0xFFFFFFFF);

        MotionNoiseAnalyzer.Metrics metrics = MotionNoiseAnalyzer.compare(reference, observed, null, 8, 0);

        assertEquals(2L, metrics.changedPixels());
        assertEquals(0.02, metrics.changedRatio(), 1.0e-9);
        assertEquals(2.55, metrics.meanAbsoluteLumaError(), 1.0e-9);
        assertEquals(64L, metrics.flatPixels());
        assertEquals(2L, metrics.flatChangedPixels());
        assertEquals(2.0 / 64.0, metrics.flatChangedRatio(), 1.0e-9);
    }

    @Test
    void boundedRegionExcludesNoiseOutsideIt() {
        BufferedImage reference = solid(8, 8, 0xFF808080);
        BufferedImage observed = solid(8, 8, 0xFF808080);
        observed.setRGB(1, 1, 0xFFFFFFFF);

        MotionNoiseAnalyzer.Metrics metrics = MotionNoiseAnalyzer.compare(
            reference,
            observed,
            new MotionNoiseAnalyzer.Region(3, 3, 4, 4),
            8,
            0
        );

        assertEquals(16L, metrics.pixels());
        assertEquals(0L, metrics.changedPixels());
        assertEquals(4L, metrics.flatPixels());
    }

    @Test
    void rejectsMismatchedImagesAndInvalidRegions() {
        BufferedImage frame = solid(8, 8, 0xFF808080);

        assertThrows(
            IllegalArgumentException.class,
            () -> MotionNoiseAnalyzer.compare(frame, solid(4, 4, 0xFF808080), null, 8, 0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> MotionNoiseAnalyzer.compare(
                frame,
                frame,
                new MotionNoiseAnalyzer.Region(7, 7, 2, 2),
                8,
                0
            )
        );
    }

    private static BufferedImage solid(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, argb);
        }
        return image;
    }
}
