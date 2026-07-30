/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

import java.awt.image.BufferedImage;

final class MotionNoiseAnalyzer {
    private MotionNoiseAnalyzer() {
    }

    static Metrics compare(
        BufferedImage reference,
        BufferedImage observed,
        Region region,
        int pixelThreshold,
        int flatGradientThreshold
    ) {
        if (reference == null || observed == null) throw new IllegalArgumentException("Images are required.");
        if (reference.getWidth() != observed.getWidth() || reference.getHeight() != observed.getHeight()) {
            throw new IllegalArgumentException("Image dimensions must match.");
        }
        if (pixelThreshold < 0 || pixelThreshold > 255) {
            throw new IllegalArgumentException("Pixel threshold must be between 0 and 255.");
        }
        if (flatGradientThreshold < 0 || flatGradientThreshold > 255) {
            throw new IllegalArgumentException("Flat-gradient threshold must be between 0 and 255.");
        }
        Region measured = region == null
            ? new Region(0, 0, reference.getWidth(), reference.getHeight())
            : region;
        if (
            measured.x < 0 || measured.y < 0 || measured.width <= 0 || measured.height <= 0 ||
                (long) measured.x + measured.width > reference.getWidth() ||
                (long) measured.y + measured.height > reference.getHeight()
        ) {
            throw new IllegalArgumentException("Region exceeds image dimensions.");
        }

        long pixels = (long) measured.width * measured.height;
        long changedPixels = 0L;
        long absoluteRgbError = 0L;
        long absoluteLumaError = 0L;
        long squaredLumaError = 0L;
        long flatPixels = 0L;
        long flatChangedPixels = 0L;
        long flatAbsoluteLumaError = 0L;
        long[] lumaHistogram = new long[256];

        int maxX = measured.x + measured.width;
        int maxY = measured.y + measured.height;
        for (int y = measured.y; y < maxY; y++) {
            for (int x = measured.x; x < maxX; x++) {
                int expected = reference.getRGB(x, y);
                int actual = observed.getRGB(x, y);
                int redError = channelError(expected, actual, 16);
                int greenError = channelError(expected, actual, 8);
                int blueError = channelError(expected, actual, 0);
                int maxChannelError = Math.max(redError, Math.max(greenError, blueError));
                boolean changed = maxChannelError > pixelThreshold;
                if (changed) changedPixels++;
                absoluteRgbError += redError + greenError + blueError;

                int expectedLuma = luma(expected);
                int actualLuma = luma(actual);
                int lumaError = Math.abs(expectedLuma - actualLuma);
                absoluteLumaError += lumaError;
                squaredLumaError += (long) lumaError * lumaError;
                lumaHistogram[lumaError]++;

                if (isFlat(reference, x, y, measured, expectedLuma, flatGradientThreshold)) {
                    flatPixels++;
                    flatAbsoluteLumaError += lumaError;
                    if (changed) flatChangedPixels++;
                }
            }
        }

        return new Metrics(
            pixels,
            changedPixels,
            ratio(changedPixels, pixels),
            mean(absoluteRgbError, pixels * 3L),
            mean(absoluteLumaError, pixels),
            pixels == 0L ? 0.0 : Math.sqrt((double) squaredLumaError / pixels),
            percentile(lumaHistogram, pixels, 0.95),
            flatPixels,
            flatChangedPixels,
            ratio(flatChangedPixels, flatPixels),
            mean(flatAbsoluteLumaError, flatPixels)
        );
    }

    private static int channelError(int expected, int actual, int shift) {
        return Math.abs(((expected >>> shift) & 0xFF) - ((actual >>> shift) & 0xFF));
    }

    private static int luma(int argb) {
        int red = (argb >>> 16) & 0xFF;
        int green = (argb >>> 8) & 0xFF;
        int blue = argb & 0xFF;
        return (54 * red + 183 * green + 19 * blue + 128) >>> 8;
    }

    private static boolean isFlat(
        BufferedImage image,
        int x,
        int y,
        Region region,
        int center,
        int threshold
    ) {
        if (
            x <= region.x || y <= region.y ||
                x + 1 >= region.x + region.width ||
                y + 1 >= region.y + region.height
        ) {
            return false;
        }
        return Math.abs(center - luma(image.getRGB(x - 1, y))) <= threshold &&
            Math.abs(center - luma(image.getRGB(x + 1, y))) <= threshold &&
            Math.abs(center - luma(image.getRGB(x, y - 1))) <= threshold &&
            Math.abs(center - luma(image.getRGB(x, y + 1))) <= threshold;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0L ? 0.0 : (double) numerator / denominator;
    }

    private static double mean(long sum, long count) {
        return count == 0L ? 0.0 : (double) sum / count;
    }

    private static int percentile(long[] histogram, long count, double percentile) {
        if (count == 0L) return 0;
        long target = Math.max(1L, (long) Math.ceil(count * percentile));
        long cumulative = 0L;
        for (int value = 0; value < histogram.length; value++) {
            cumulative += histogram[value];
            if (cumulative >= target) return value;
        }
        return histogram.length - 1;
    }

    record Region(int x, int y, int width, int height) {
    }

    record Metrics(
        long pixels,
        long changedPixels,
        double changedRatio,
        double meanAbsoluteRgbError,
        double meanAbsoluteLumaError,
        double rootMeanSquareLumaError,
        int p95LumaError,
        long flatPixels,
        long flatChangedPixels,
        double flatChangedRatio,
        double flatMeanAbsoluteLumaError
    ) {
    }
}
