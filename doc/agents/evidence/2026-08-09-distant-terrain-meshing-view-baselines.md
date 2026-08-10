<!--
 Minosoft
 Copyright (C) 2026 Jacob Repp

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Distant-terrain meshing and cardinal-view baselines

Date: 2026-08-09 local time (live artifacts dated 2026-08-10 UTC).

## Headless accuracy baselines

`DistantPageMesherBaselineTest` adds differential oracles that do not share the
production interval-subtraction or greedy-merge implementation. They expand
the generated quads into unit faces and compare them exactly with 2,048 seeded
heightfields over detail levels 0 and 1 and widths 1, 2, 4, and 8, eight
explicit ascending, descending, diagonal, and terraced heightfields, and 384
seeded multi-run pages over widths 2, 4, and 8. The multi-run inputs contain
detached shelves and stacked overhangs and independently enumerate their top,
underside, and split side faces. The gate also freezes primitive counts,
rejects overlapping greedy faces, requires stable quad order and digest on
repeat, varies material and light merge boundaries, exercises negative page
coordinates, and verifies page-relative geometry at page coordinates
`+/-1,875,000` and translated vertical origins.

`DistantTerrainRegionArtifactBaselineTest` freezes the application encoder
boundary for all six face directions: vertex positions, winding/index order,
packed color, light, normal, and material fields. A separate large-coordinate
case verifies opaque/water partitioning and tinted-water alpha/light/material
packing.

Both tests are part of `./gradlew localTerrainTest`. The Java 25.0.4 focused
gate passed after these additions.

## View-dependent real-OpenGL acceptance

`terrain-distant-cardinal-views.json` uses an isolated regenerated debug world
and one fixed camera position with four yaws. At every view it settles and
flushes terrain work, requires nonzero distant main and shadow draw pages with
zero missing pages, clears transient focus-loss GUI state, and compares a
platform-qualified framebuffer crop. It finishes by checking failed build,
upload, submission, retirement, and OpenGL backend accounting and restores the
camera, time, weather, HUD, Iris, and distant-terrain presentation.

The accepted identity was Java 25.0.4, Minecraft 1.20.4, Apple M5 Max OpenGL
4.1 Metal 90.5, `1800x1000`, debug seed `6072333650475958863`, trajectory
`terrain-distant-view-baseline-2026-08-09`, and standalone stack fingerprint
`a532d878f7c8` with zero acceptance fixtures. Iris was disabled only through
the reversible presentation control; distant terrain remained enabled.

The checked crop starts below the asynchronously hydrated far-horizon
silhouette. Early full-horizon runs showed that silhouette can gain source
geometry across fresh processes even after the current terrain runtime reaches
its idle boundary, so it is not a deterministic image oracle. The scenario
instead verifies distant view selection structurally in each direction and
uses pixels to freeze stable ground rendering and the view transform.

Accepted runs:

| Purpose | Run | Result |
| --- | --- | --- |
| Intentional baseline update | `terrain-distant-cardinal-views-2026-08-10T03-15-50-520370Z-17438` | passed in 65,444 ms |
| Same-process compare only | `terrain-distant-cardinal-views-2026-08-10T03-17-06-886785Z-18194` | passed in 65,622 ms |
| Fresh-process compare only | `terrain-distant-cardinal-views-2026-08-10T03-19-10-901703Z-19404` | passed in 63,440 ms |

The fresh-process run published main/shadow draw counts of `374/267` north,
`378/265` east, `376/264` south, and `376/264` west, with zero missing pages in
all eight view publications. Changed-pixel ratios were 0.0093, 0.0102, 0.0059,
and 0.0003 respectively, below the 0.02 platform-qualified limit; mean absolute
error remained below 0.10 in every view.

At handoff the scenario had restored the player and camera to
`(0.5,20.0,0.5)`, yaw/pitch zero, Iris/Bliss and configured distant terrain were
enabled, presentation time and weather overrides were cleared, the isolated
client was stopped, its lease was released, and no dedicated server had been
started or changed.
