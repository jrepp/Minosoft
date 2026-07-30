<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.
-->

# Complementary Unbound integration

This document describes the exact Complementary Unbound shader pack selected by
the managed Fabric stack, the relevant upstream rendering design, and every
Complementary-specific adjustment Minosoft currently makes. It is an
implementation reference, not a redistribution or fork of the shader pack.

## Identity and ownership

| Field | Managed value |
| --- | --- |
| Pack | Complementary Shaders - Unbound |
| Revision | `r5.8.1` |
| Archive | `ComplementaryUnbound_r5.8.1.zip` |
| Modrinth project | `R6NEzAwj` |
| Modrinth version | `VMHXIk50` |
| SHA-512 | `9098dd9e0c18b80f7aba2839cea33ce9a614d97665bbfcac87ccce6e4771667c41602d99088852cb1642ccab20b2ceff9b98af8f2e795bd0d3b90b7c9cbab914` |
| Manifest | [`modpacks/fabric-stack/shaderpacks/complementary-unbound.pw.toml`](../../modpacks/fabric-stack/shaderpacks/complementary-unbound.pw.toml) |

The downloaded archive is third-party content and remains byte-for-byte
unchanged. `play.sh` verifies it, stores it in the content-addressed
out-of-source modpack store, and stages it as a shader pack rather than a
resource pack or executable mod. No expanded Complementary source belongs in
this repository.

The effective source compiled by Minosoft is not byte-for-byte upstream. The
planner resolves includes and options in memory, applies the narrow
transformations documented below, adds Minosoft's producer ABI bridge, and then
compiles the resulting candidate. These generated sources are generation-owned
runtime state and are not written back into the ZIP.

Complementary retains ownership and licensing of its original shader sources.
Minosoft's Kotlin transformers and injected GLSL are Minosoft code under the
project license.

## Upstream pack structure used by Minosoft

The archive has a shared `shaders/lib/` implementation plus three legacy
dimension program directories:

- `world0` for the Overworld;
- `world-1` for the Nether;
- `world1` for the End.

Each directory contains 36 vertex/fragment program pairs and one
`shadowcomp.csh` compute program. The important program families are:

| Family | Representative roots | Role |
| --- | --- | --- |
| Scene G-buffer | `gbuffers_terrain`, `gbuffers_water`, `gbuffers_entities`, `gbuffers_hand`, `gbuffers_clouds` | Writes material, lighting, depth, normal, and color inputs for later passes. |
| Specialized scene | `gbuffers_armor_glint`, `gbuffers_beaconbeam`, `gbuffers_lightning`, `gbuffers_line`, sky and weather roots | Handles producer-specific vertex/state contracts. |
| Shadow | `shadow`, `shadowcomp` | Renders authored caster classes and filters or augments shadow data. |
| Deferred/composite | `deferred1`, `composite`, `composite1`, `composite3` through `composite7` | Applies lighting, fog, water, clouds, temporal filtering, and post-processing. |
| Final | `final` | Presents the completed world image. |
| Optional advanced lighting | `clrwl_*` | Implements the option-gated colored-lighting and world-space-reflection path. |

`shaders.properties` supplies profiles, option screens, program conditions,
blend and shadow directives, particle ordering, custom resources, and
`underwaterOverlay=false`. `lib/common.glsl` supplies the selected option
defines. The pack uses G-buffer color/depth attachments as persistent inputs to
later fullscreen passes and uses temporal accumulation to converge stochastic
cloud, fog, lighting, and foliage samples.

Minosoft does not emulate these effects with its built-in shaders. It executes
the pack's scene, shadow, deferred, composite, compute where supported, and
final programs through the Iris-compatible render graph.

## Source preparation order

For every paired program, `IrisShaderPackPlanner` performs the following
candidate-only sequence:

1. Select the active dimension directory and option/profile conditions.
2. Resolve `#include` files and preprocess the chosen option definitions.
3. Reject unsupported, unpaired, or explicitly disabled programs.
4. Apply `IrisTemporalStabilityTransformer` to matching resolved sources.
5. Apply `IrisComplementaryWaterTransformer` to matching resolved sources.
6. Add the retained Minosoft producer ABI through
   `IrisLegacyShaderTransformer`.
7. Translate the optional source-native block-atlas alias and texture-size
   queries.
8. Inspect outputs, samplers, images, buffers, blend state, and program
   contracts; then build a complete immutable `ShaderPipelinePlan`.
9. Compile and link the complete candidate on the render thread before
   replacing the last-known-good generation.

The pack-specific corrections deliberately run before the producer ABI bridge,
where their anchors still match authored Complementary source. Generic
texture-array translation runs after the bridge because it must see the final
host sampling ABI.

## Managed option profile

The manifest supplies the following baseline. An explicit
`MINOSOFT_SHADER_OPTIONS` value can replace it for diagnosis or acceptance.

| Option | Upstream default | Managed value | Intent |
| --- | ---: | ---: | --- |
| `RP_MODE` | `1` | `3` | Select the pack's labPBR resource-pack material path. |
| `SHADOW_QUALITY` | `2` | `1` | Keep real-time shadows at the upstream “Low” tier. |
| `CLOUD_QUALITY` | `2` | `3` | Increase Unbound cloud lighting/detail sample quality. |
| `ANISOTROPIC_FILTER` | `0` | `8` | Use the pack's 8x mipmapped terrain filter. |
| `TAA_MODE` | `1` | `1` | Retain temporal filtering required by sparse effects. |
| `TAA_SMOOTHING` | `3` | `4` | Select high history smoothing. |
| `TAA_JITTER` | `1` | `0` | Remove projection-jitter flicker at the cost of TAA's spatial antialiasing benefit. |
| `FXAA_STRENGTH` | `75` | `85` | Provide stronger current-frame edge smoothing. |
| `FXAA_TAA_INTERACTION` | `10` | `0` | Do not reduce FXAA during camera movement. |
| `WAVING_FOLIAGE` | enabled | `false` | Disable authored grass/foliage wind in the normal profile. |
| `WAVING_LEAVES` | enabled | `false` | Disable authored leaf wind in the normal profile. |
| `WATER_ALPHA_MULT` | `100` | `180` | Strengthen the visible surface boundary while remaining translucent. |
| `WATER_FOG_MULT` | `100` | `50` | Move underwater fog farther away so nearby geometry remains readable. |
| `WATER_BUMPINESS` | `1.25` | `1.50` | Strengthen surface-direction normal variation. |
| `UNDERWATERCOLOR_R` | `100` | `110` | Tune underwater red attenuation. |
| `UNDERWATERCOLOR_G` | `100` | `120` | Tune underwater green attenuation. |
| `UNDERWATERCOLOR_B` | `100` | `130` | Tune underwater blue attenuation. |

These are authored pack options, not source rewrites. Their accepted values are
a coupled presentation profile: changing one can invalidate the visual balance
or the temporal measurements used to select the others.

## Complementary-specific source transformations

### Block-lit water normals

Program: `gbuffers_water` fragment stage.

Complementary r5.8.1 scales the water normal's horizontal amplitude only with
the sky-light coordinate:

```glsl
normalMap.xy *= 0.03 * lmCoordM.y + 0.01;
```

In enclosed, block-lit water, `lmCoordM.y` is low even when local block light is
strong. The normal perturbation therefore collapses and removes the highlights,
refraction, and edge variation that identify the water plane.

Minosoft substitutes:

```glsl
float minosoftWaterSurfaceLight = max(lmCoordM.x, lmCoordM.y);
normalMap.xy *= 0.03 * minosoftWaterSurfaceLight + 0.01;
```

`lmCoordM.x` is block light and `lmCoordM.y` is sky light. Using the stronger
channel preserves the upstream response outdoors while allowing local light to
reveal the surface in caves.

### Luminance-aware underwater attenuation

Program: `composite1` fragment stage.

Upstream applies one color multiplier to every already-composited surface:

```glsl
vec3 underwaterMult = vec3(0.80, 0.87, 0.97);
color.rgb *= underwaterMult * 0.85;
volumetricEffect.rgb *= pow2(underwaterMult * 0.55);
```

That uniform surface attenuation collapses lit stone and ore toward the same
blue-gray result as dark water. Minosoft derives a bounded surface-light weight:

```text
surfaceLight =
    smoothstep(0.06, 0.30, clamp(luminance(max(color, 0)), 0, 1))

surfaceAttenuation =
    mix(underwaterMult * 0.85, vec3(0.96), surfaceLight * 0.65)
```

The final surface color is multiplied by `surfaceAttenuation`. Dark surfaces
therefore retain nearly all authored blue attenuation, while an already-lit
surface moves only partway toward a near-neutral `0.96` multiplier. The
volumetric term remains exactly
`pow2(underwaterMult * 0.55)`, preserving blue distance attenuation and depth
cues.

### Optional waving-terrain motion history

Programs: every matching `gbuffers_terrain*` terrain program.

The normal managed profile disables foliage and leaf waving. If either option
is deliberately re-enabled, the transformer:

1. evaluates Complementary's wind deformation at `frameTimeCounter`;
2. evaluates it again at `max(frameTimeCounter - frameTime, 0)`;
3. translates the prior position by
   `cameraPosition - previousCameraPosition`;
4. projects it with
   `gbufferPreviousProjection * gbufferPreviousModelView`;
5. writes prior normalized screen UV and a validity bit to `colortex14`.

The conditional buffer contract is:

| Property | Value |
| --- | --- |
| Buffer | `colortex14` |
| Format | `RGBA16F` |
| Clear | transparent zero |
| Payload | previous UV in `xy`, validity in `w` |
| Consumers | `composite6` temporal reprojection |

Planning fails if a matching pack already owns `colortex14`; Minosoft never
aliases an authored attachment.

Waving amplitude is also multiplied by:

```text
1 - smoothstep(
    max(far * 0.35, 32),
    max(far * 0.75, fadeStart + 16),
    length(playerPosition)
)
```

This retains nearby movement and tapers it before distant geometry becomes too
small for stable temporal reconstruction.

### Mip-aware foliage coverage

Programs: matching `gbuffers_terrain*` fragment stages. This correction remains
active even when waving is disabled.

For Complementary's known foliage material IDs, Minosoft:

- derives the pixel footprint from texture-coordinate derivatives;
- converts it to an approximate mip level;
- raises the alpha coverage threshold from `0.10` toward `0.45` with distance;
- bounds the derivative-based transition width to `0.01..0.08`;
- compares coverage against a stable hash of a texture-space 4x cell;
- discards failed samples and forces surviving alpha to `1.0`.

Texture-anchored coverage prevents the stipple pattern from crawling with the
camera. Making survivors opaque avoids blending the RGB stored in nearly
transparent cutout texels into the terrain pass.

### Cloud and camera-medium history

Program: `composite6` fragment stage.

Complementary's Unbound clouds use sparse samples that depend on temporal
convergence. Ordinary screen-space history is not sufficient after camera
movement, and history from above water is invalid immediately below the water
plane.

Minosoft adds load-preserved, double-buffered `R16F colortex15`. Each pixel
stores Complementary's cloud linear depth from `colortex5.a`; underwater frames
add `2.0` to the stored value as a medium marker. On the next frame:

- the marker is decoded before depth comparison;
- history is rejected on the first frame or after an above/below-water
  transition;
- cloud view position is reconstructed from linear depth and reprojected with
  Complementary's previous-camera function;
- out-of-bounds, empty, or invalid depth rejects history;
- a depth difference greater than
  `max(8 blocks, currentDistance * 0.05)` rejects history;
- a valid `colortex14` foliage motion sample takes precedence.

Rejection sets the temporal blend factor to zero for that sample. Stable
underwater frames continue to accumulate the pack's frame-varying colored-light
fog, volumetric lighting, and banding dither; only incompatible entry/exit
history is discarded.

### Material-254 projection jitter

Programs: matching entity and hand phases.

Complementary marks material `254` as a “No TAA” route. If a matching fragment
contract bypasses TAA while its vertex source still applies `TAAJitter`,
Minosoft removes that projection-jitter assignment. Otherwise the silhouette
moves every frame but never receives the history filter intended to converge
that movement.

The r5.8.1 entity and hand sources selected by the managed options already omit
the problematic call. The transform is a defensive contract for matching
variants and does not rewrite mixed-material block or item programs.

## Required compatibility corrections outside pack-specific GLSL

Several fixes are generic Minosoft/Iris compatibility behavior rather than
changes to Complementary source:

| Boundary | Minosoft behavior | Why Complementary needs it |
| --- | --- | --- |
| Terrain texture atlas | Replaces the flat block-atlas sampler with the selected Minosoft texture array and physical layer. | The managed `RP_MODE=3` path consumes diffuse, normal, and specular LabPBR pages. |
| Anisotropic helper | Removes Complementary's `sampler2D` helper argument and redirects explicit LOD/gradient taps to array-aware sampling functions. | `ANISOTROPIC_FILTER=8` otherwise references the removed flat sampler. |
| First-person alpha test | Defaults arm and held-item state ABIs to Iris's `GREATER 0.1` test unless the pack authors an override. | Transparent sleeve/item texels must not write depth through `gbuffers_hand`. |
| Underwater overlay | Parses `underwaterOverlay` with Iris's compatible `true` default and suppresses the vanilla overlay only when the plan says `false`. | Complementary authors its own water fog and declares `underwaterOverlay=false`. |
| Physical material UVs | Transforms fluid-top and simple-overlay UVs into their physical diffuse pages and keeps texture-array layer varyings flat. | Logical `0..1` UVs otherwise cross LabPBR companion pages or interpolate layers. |
| Hand depth | Applies the pinned Iris hand projection depth scale without clearing existing world depth. | The hand remains in front without destroying depth needed by later pack passes. |
| Brightness input | Uses the Minecraft 1.20.4 default gamma value `0.5` for new/reset rendering profiles. | Complementary consumes `screenBrightness` when deriving underwater fog brightness. |

These behaviors apply at host boundaries and remain useful for other compatible
shader packs. They are listed here because they materially affect the managed
Complementary result.

## Revision and failure contract

The transformations are source-anchored rather than line-number-anchored:

- unrelated program names, phases, or packs are returned unchanged;
- matching Complementary detection signatures must contain the complete
  expected source/output anchors;
- a missing required anchor or occupied auxiliary buffer throws during
  planning;
- a failed candidate never replaces the current render-ready generation.

This avoids silently publishing half of a motion, water, or history contract.
It does not make transforms automatically compatible with a newer
Complementary release. Updating the archive requires reviewing upstream
sources, anchors, options, formats, and resource ownership, then advancing the
manifest only after all gates pass.

## Validation and accepted runtime boundary

Focused source checks:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisComplementaryWaterTransformerTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisTemporalStabilityTransformerTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisLegacyShaderTransformerTest
```

Exact archive planning:

```sh
MINOSOFT_IRIS_TEST_PACK=/absolute/path/ComplementaryUnbound_r5.8.1.zip \
MINOSOFT_IRIS_TEST_OPTIONS='RP_MODE=3;SHADOW_QUALITY=1;CLOUD_QUALITY=3;ANISOTROPIC_FILTER=8;TAA_MODE=1;TAA_SMOOTHING=4;TAA_JITTER=0;FXAA_STRENGTH=85;FXAA_TAA_INTERACTION=0;WAVING_FOLIAGE=false;WAVING_LEAVES=false;WATER_ALPHA_MULT=180;WATER_FOG_MULT=50;WATER_BUMPINESS=1.50;UNDERWATERCOLOR_R=110;UNDERWATERCOLOR_G=120;UNDERWATERCOLOR_B=130' \
./gradlew :test \
  --tests 'de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest.external shader pack crosses the complete planner contract when configured'
```

Pack integrity and broad build:

```sh
./play.sh modpack inspect fabric-stack --trajectory <trajectory>
./gradlew assemble
```

Visual or driver changes additionally require the repository's hot-reload
acceptance protocol. Check the exact selected archive/options, framebuffer
output, relevant producer routes, rejection/fallback maps, resource counts, and
pose/world state before drawing a visual conclusion.

The final Apple OpenGL water acceptance advanced water, all shadow terrain
classes, and all fullscreen stages with empty rejection/fallback ledgers. Its
steady ledger was 44 textures, seven framebuffers, 111 linked programs, and
zero retained shader objects. Those counts are a regression baseline for this
exact revision, options, dimension, and backend—not a permanent cross-pack ABI.

## Implementation index

- Pack selection and options:
  [`complementary-unbound.pw.toml`](../../modpacks/fabric-stack/shaderpacks/complementary-unbound.pw.toml)
- Planning and transform order:
  [`IrisShaderPackPlanner.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisShaderPackPlanner.kt)
- Temporal, foliage, and history transforms:
  [`IrisTemporalStabilityTransformer.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisTemporalStabilityTransformer.kt)
- Water transforms:
  [`IrisComplementaryWaterTransformer.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisComplementaryWaterTransformer.kt)
- Producer and texture-array ABI:
  [`IrisLegacyShaderTransformer.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisLegacyShaderTransformer.kt)
- Immutable plan and alpha defaults:
  [`ShaderPipelinePlan.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/ShaderPipelinePlan.kt)
- Water-overlay gate:
  [`WaterOverlay.kt`](../../src/main/java/de/bixilon/minosoft/gui/rendering/framebuffer/world/overlay/overlays/simple/WaterOverlay.kt)
- Focused tests:
  [`IrisComplementaryWaterTransformerTest.kt`](../../src/test/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisComplementaryWaterTransformerTest.kt),
  [`IrisTemporalStabilityTransformerTest.kt`](../../src/test/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisTemporalStabilityTransformerTest.kt), and
  [`IrisShaderPackPlannerTest.kt`](../../src/test/java/de/bixilon/minosoft/gui/rendering/shader/pipeline/IrisShaderPackPlannerTest.kt)
- Runtime evidence:
  [Complementary motion and hand](../agents/evidence/2026-07-28-complementary-motion-and-hand.md) and
  [temporal stability, overlays, and water lighting](../agents/evidence/2026-07-29-complementary-temporal-stability.md)
