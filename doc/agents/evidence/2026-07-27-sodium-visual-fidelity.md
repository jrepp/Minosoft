<!-- Copyright (C) 2026 Jacob Repp -->

# Sodium terrain visual fidelity

Date: 2026-07-27

## Outcome

The adapted Sodium terrain generation now carries four independent packed
light/color values per quad and uses a Sodium-style smooth-lighting path for
solid and fluid terrain. Cached per-vertex biome sampling supplies bilinear
grass, foliage, and water tinting. The same packed attribute is decoded by the
built-in chunk shader and exposed to transformed Iris terrain programs.

This closes the missing UI-independent visual primitives investigated here:
smooth terrain light and AO, smooth shallow-water light, and biome-color
blending. It does not claim that Minosoft has adopted Sodium's independent
scheduler, mesher, upload, visibility, or batching implementations.

## Implemented slice

| Item | Result | Evidence |
| --- | --- | --- |
| 1. Four-vertex terrain contract | `BlockVertexConsumer`, `ChunkMeshBuilder`, and `ChunkMeshesBuilder` accept four packed light/color values. Quad indices can select either diagonal. Raw float bits are preserved because valid packed values may encode IEEE NaNs. | `ChunkMeshBuilder`, `IndexUtil`, `QuadMeshBuilder`, `BakedFaceTest`, and `IndexUtilTest`. |
| 2. Smooth solid lighting | Each canonical face corner averages outward, two edge, and diagonal block/sky samples independently. Partial and inset faces bilinearly interpolate the corner result and fallback light. Continuous AO uses edge/diagonal opacity, and diagonal choice includes both brightness and light. | `SmoothTerrainLighting`, `BakedFace`, `BakedModel`, `WorldRenderProps`, `SolidSectionMesher`, and `SmoothTerrainLightingTest`. |
| 3. Water lighting | Fluid top, bottom, side, and back faces use the same per-vertex light/AO contract. | `FluidSectionMesher` and the live shallow-water canary. |
| 4. Biome blending | A section-local cache memoizes block/fluid samples and evaluates every vertex at Sodium's half-block offset with bilinear X/Z interpolation. Existing simple/Gaussian radius samplers remain the underlying biome blur. | `TerrainTintCache`, `TerrainTintCacheTest`, solid/fluid meshers, and the live swamp/plains seam. |
| 5. Settings invalidation | Changing blending enabled state, radius, or algorithm invalidates terrain so cached vertices cannot survive a profile change. The zero-radius simple sampler still performs its central sample. | `ChunkRenderer` and `SimpleTintSampler`. |
| 6. Iris bridge | Modern transformed terrain inputs derive `COLOR`, `PACKED_LIGHT`, and `a_LightCoord` from Minosoft's combined location-three terrain attribute. The transform is restricted to the terrain ABI. | `IrisLegacyShaderTransformer` and `IrisLegacyShaderTransformerTest`. |
| 7. Visual canary | Debug chunks `(6,6)` and `(7,6)` contain a swamp/plains seam crossed by grass and shallow water plus a torch, stair, and farmland group. Biomes are resolved at generation time, with deterministic fallbacks when the registry is not populated yet, and are represented by horizontally sampleable sources. Explicit reference preparation can suppress the transient debug-world border without changing its normal default. | `DebugGenerator`, `DebugGeneratorTest`, `ClientDebugChannel`, `WorldBorderRenderer`, and the supervised live run below. |

## Invariants

- Terrain still uses the single canonical `minosoft:terrain/iris-material`
  layout. The change extends values carried by its existing
  `packed_light_color` semantic; it does not introduce a competing Sodium
  vertex format.
- Block and sky light nibbles are averaged independently and clamped before
  repacking.
- A non-tinted face keeps its existing material tint. Cached biome lookup is
  attempted only for a nonnegative face tint index.
- Fluid and solid tints share the same cache lifetime: one meshing operation.
- Biome settings changes rebuild chunks instead of mutating uploaded meshes.
- Reference-only world-border suppression is opt-in. Ordinary gameplay and the
  default `visual.prepare-reference` request retain the existing border state.

## Live acceptance

A supervised Java 17 `fabric-stack` local debug world ran under trajectory
`sodium-visual-2026-07-27`. The checked frame showed:

- a continuous dark-swamp to bright-plains grass transition across `x=112`;
- continuous shallow-water tint and luminance from swamp teal to plains blue,
  with no visible triangle diagonal;
- smooth torch illumination and partial-block AO on the stair/farmland group;
- no transient world-border stripes after explicit reference suppression.

The same-frame diagnostics reported:

- Sodium `0.5.8+mc1.20.4` and Iris `1.7.2+mc1.20.4` adapters active;
- terrain owner `minosoft:sodium-compatible-terrain`;
- implementation `sodium-0.5.8-adapter-minosoft-core`;
- layout `minosoft:terrain/iris-material`, stride 84 bytes;
- `packed_light_color`, block ID, mid-texture, tangent, normal, and mid-block
  semantics present;
- opaque, cutout, and translucent main-view submissions in the accepted frame;
- no active shader pack for this canary, so the built-in shader isolated the
  terrain values while the Iris adapter remained active.

The first reference frame exposed a real fixture defect: `DebugGenerator`
captured null biome entries before registry population, producing white grass
and water tints. Resolving registry entries at generation time and supplying
deterministic fallback biomes corrected the frame. No raw `.run/` logs or
screenshots are committed.

## Verification

Focused Java 17 checks cover corner lighting/AO/inset interpolation, bilinear
tint sampling and cache reuse, diagonal indices, independent packed
light/color values, Iris terrain attribute transforms, settings catalog
claims, and the generated canary:

- 13 focused unit tests passed;
- 7 focused integration tests passed;
- 2,134 integration tests passed with 115 declared skips;
- 9 `debug-core` tests passed;
- a nonincremental, no-build-cache `compileKotlin` passed;
- `assemble`, `play-util:installDist`, and
  `debug-server-fabric:remapJar` passed;
- `git diff --check` passed.

The final root unit pass executed 1,949 tests with one declared skip and three
failures, all isolated to the concurrently changing
`IrisShaderPackPlannerTest`: the project reference pack currently reports a
duplicate `gbuffers_armor_glint` scene bridge, the runtime-binding rejection
test no longer observes its expected exception, and the pinned-program-family
test cannot find one expected element. The same three failures reproduce when
that class runs alone. They do not exercise the terrain lighting, tint cache,
quad contract, or Iris terrain-attribute transform implemented here. An
earlier root pass before those unrelated Iris planner changes completed 1,947
tests without failures and with one declared skip.

## Remaining boundary

The live canary deliberately selected no shader pack. It proves that the
Sodium-owned terrain generation supplies correct data to the shared
Iris-compatible ABI, not independent shader-pack visual parity. Shader-pack
program breadth and the still-delegated Sodium scheduler/mesher/upload path
remain governed by the Iris pipeline and render-substrate evidence.
