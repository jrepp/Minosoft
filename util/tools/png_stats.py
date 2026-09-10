#!/usr/bin/env uv run
# Minosoft
# Copyright (C) 2026 Jacob Repp
#
# This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
#
# This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
#
# This software is not affiliated with Mojang AB, the original developer of Minecraft.

"""Summarize a captured PNG without needing a display or a full client run.

The debug control plane writes final-framebuffer captures via ``visual.capture``;
this tool turns one of those PNGs into numeric evidence (luminance, tint, grid)
for agent acceptance notes. Deps are pinned by the project ``uv.lock``.

Usage:
    uv run png_stats.py IMAGE [--grid COLSxROWS] [--region X0,Y0,X1,Y1]
        [--samples XxY] [--quantize STEP]
"""

from __future__ import annotations

import argparse
import sys

from PIL import Image


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be a positive integer")
    return parsed


def dimensions(value: str) -> tuple[int, int]:
    left, separator, right = value.lower().partition("x")
    if not separator:
        raise argparse.ArgumentTypeError("value must use COLSxROWS")
    try:
        return positive_int(left), positive_int(right)
    except (ValueError, argparse.ArgumentTypeError) as error:
        raise argparse.ArgumentTypeError("value must use positive COLSxROWS") from error


def region(value: str) -> tuple[int, int, int, int]:
    try:
        coordinates = tuple(int(part) for part in value.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError("value must use X0,Y0,X1,Y1") from error
    if len(coordinates) != 4:
        raise argparse.ArgumentTypeError("value must use X0,Y0,X1,Y1")
    return coordinates


def luminance(pixel: tuple[int, int, int]) -> float:
    r, g, b = pixel[:3]
    return (r + g + b) / 3.0


def load(image_path: str) -> Image.Image:
    try:
        with Image.open(image_path) as source:
            return source.convert("RGB")
    except Exception as error:  # pragma: no cover - CLI boundary
        raise SystemExit(f"failed to open {image_path}: {error}") from error


def report_stats(im: Image.Image, quantize: int) -> None:
    width, height = im.size
    px = im.load()
    total = 0.0
    count = 0
    minimum = 255.0
    maximum = 0.0
    buckets = [0] * 10
    for y in range(0, height, quantize):
        for x in range(0, width, quantize):
            value = luminance(px[x, y])
            total += value
            count += 1
            minimum = min(minimum, value)
            maximum = max(maximum, value)
            buckets[min(int(value / 255.0 * 10), 9)] += 1
    print(f"size       {width}x{height}")
    print(f"samples    {count} (every {quantize} px)")
    print(f"luminance  avg={total / count:.1f} min={minimum:.1f} max={maximum:.1f}")
    print("buckets    " + " ".join(f"{index * 10}-{index * 10 + 9}:{n}" for index, n in enumerate(buckets)))


def report_grid(im: Image.Image, columns: int, rows: int, quantize: int) -> None:
    width, height = im.size
    if columns > width or rows > height:
        raise SystemExit("grid exceeds image dimensions")
    px = im.load()
    cell_w = width / columns
    cell_h = height / rows
    for row in range(rows):
        line = []
        for column in range(columns):
            x0, y0 = int(column * cell_w), int(row * cell_h)
            x1, y1 = int((column + 1) * cell_w), int((row + 1) * cell_h)
            total = count = 0
            for y in range(y0, y1, max(quantize, 1)):
                for x in range(x0, x1, max(quantize, 1)):
                    total += luminance(px[x, y])
                    count += 1
            line.append(f"{total / count:3.0f}")
        print(" ".join(line))


def report_region(im: Image.Image, x0: int, y0: int, x1: int, y1: int, quantize: int) -> None:
    width, height = im.size
    px = im.load()
    x0, x1 = sorted((min(max(x0, 0), width), min(max(x1, 0), width)))
    y0, y1 = sorted((min(max(y0, 0), height), min(max(y1, 0), height)))
    if x0 >= x1 or y0 >= y1:
        raise SystemExit("empty region")
    total = 0.0
    count = 0
    rsum = gsum = bsum = 0
    for y in range(y0, y1, quantize):
        for x in range(x0, x1, quantize):
            r, g, b = px[x, y][:3]
            total += (r + g + b) / 3.0
            rsum += r
            gsum += g
            bsum += b
            count += 1
    print(f"region     {x0},{y0}-{x1},{y1} ({count} samples)")
    print(f"luminance  avg={total / count:.1f}")
    print(f"tint RGB   {rsum / count:.0f} {gsum / count:.0f} {bsum / count:.0f}")


def report_center(im: Image.Image, columns: int, rows: int, quantize: int) -> None:
    width, height = im.size
    px = im.load()
    cx, cy = width // 2, height // 2
    cell_w = width / (columns * 4)
    cell_h = height / (rows * 4)
    print(f"center     {columns}x{rows} cells around {cx},{cy}")
    for row in range(rows):
        line = []
        for column in range(columns):
            x0 = int(cx - (columns / 2 - column) * cell_w)
            y0 = int(cy - (rows / 2 - row) * cell_h)
            x1 = int(x0 + cell_w)
            y1 = int(y0 + cell_h)
            total = count = 0
            for y in range(y0, y1, max(quantize, 1)):
                for x in range(x0, x1, max(quantize, 1)):
                    if 0 <= x < width and 0 <= y < height:
                        total += luminance(px[x, y])
                        count += 1
            line.append(f"{total / max(count, 1):3.0f}")
        print(" ".join(line))


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Report numeric statistics for a captured PNG.")
    parser.add_argument("image", help="path to the PNG capture")
    parser.add_argument("--grid", type=dimensions, default=None, help="COLSxROWS ASCII luminance grid")
    parser.add_argument("--region", type=region, default=None, help="X0,Y0,X1,Y1 region average + RGB tint")
    parser.add_argument("--center", type=dimensions, default=None, help="COLSxROWS samples around the image center")
    parser.add_argument("--quantize", type=positive_int, default=1, help="sample every N pixels")
    args = parser.parse_args(argv)

    image = load(args.image)
    report_stats(image, args.quantize)
    if args.grid:
        columns, rows = args.grid
        report_grid(image, columns, rows, args.quantize)
    if args.region:
        x0, y0, x1, y1 = args.region
        report_region(image, x0, y0, x1, y1, args.quantize)
    if args.center:
        columns, rows = args.center
        report_center(image, columns, rows, args.quantize)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
