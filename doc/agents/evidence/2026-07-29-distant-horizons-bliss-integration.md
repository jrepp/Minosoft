<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.
-->

# Distant Horizons and Bliss integration checkpoint

Date: 2026-07-29

## Scope

This checkpoint records the completed source-native DH render ABI and its
optional producer/storage/control layers:

1. adapt the exact Distant Horizons 2.4.4-b artifact to an owned Minosoft LOD
   data boundary;
2. pin the exact Bliss 2.1.0 Modrinth archive and execute its Distant Horizons
   programs through Minosoft's retained render graph;
3. persist detached LOD tiles across client sessions;
4. generate unexplored LOD terrain only through the current local or managed
   server authority;
5. transfer bounded LOD tiles over an advertised source-native channel;
6. expose the behavior through Minosoft's native persisted settings UI.

Neither upstream artifact is copied into the repository or linked as Minecraft
client bytecode. Packwiz manifests retain exact download identities and hashes;
the play utility stages them into the out-of-source artifact store.

## Pinned artifacts

| Artifact | Identity | Exact file | SHA-512 |
| --- | --- | --- | --- |
| Distant Horizons | Modrinth `uCdwusMi`, version `8ClbUcsw`; upstream version `2.4.4-b` for Minecraft 1.20.4 | `DistantHorizons-2.4.4-b-1.20.4-fabric-forge.jar` | `6d88ddd2b321851b91fdc8024c5e779b0f7459aa64e9f4b7a4832c7ffda7feb255fcd8f41628e3da72a695376c746190cc7dc9e6308d8e9e405348547c987ee5` |
| Bliss | Modrinth `ZvMtQlho`, version `FQU0kGPt`; upstream version `2.1.0` | `Bliss_v2.1.0_(Chocapic13_Shaders_edit).zip` | `45e6c8325695ea0f5ded77c3042ecd193608f9e687558b19a79fb4b53be050384076796d1ebc1638be94ba08510a5be9b41c3a2706a84d6a60124ddbeda5979a` |

The focused pack is `modpacks/distant-horizons-bliss/`. It also pins the
already adapted Fabric API 0.97.3, Sodium 0.5.8, and Iris 1.7.2 artifacts, plus
server-only Terralith 2.4.11 and Tectonic 2.3.5b artifacts for the managed
large-world trajectory.
Complementary Unbound remains the default of `fabric-stack`; adding Bliss does
not replace or modify that archive.

## Distant Horizons source-native boundary

`DistantHorizonsCompatibilityAdapter` matches only the exact inspected artifact
surface:

- mod id `distanthorizons`, version `2.4.4-b`, environment `*`;
- client, Mod Menu, and server entrypoint keys;
- `lod` provided identity;
- one mixin declaration, the exact access widener, and ten nested JARs.

Activation installs owner-scoped chunk, block-mutation, payload, world
lifecycle, settings, and renderer hooks. It does not execute upstream
entrypoints, mixins, access widening, database/network/generator code,
renderer code, or screen classes. Persistence, generation, transfer, settings,
and rendering below are Minosoft source-native implementations behind the
exact artifact identity.

### Current tile contract

One `DistantLodTile` is an immutable 16 by 16 column snapshot keyed by native
`ChunkPosition`. Each column currently retains:

- the exact block Y and resource identity found beneath the native light
  heightmap;
- for water, the first non-water block Y and resource identity below the
  surface, so the opaque pass supplies the depth that `dh_water` consumes.

Chunk create/update captures all 256 columns under the chunk lock. A block
mutation derives the distinct affected X/Z columns and copies only those
values into a new immutable tile. Native unload/clear deliberately retains the
detached tile. World exit and adapter closure clear session state.

`DistantLodTileStore` is access-ordered and capped at a configured
4,096–65,536 chunks per play session, with a 16,384 default. The oldest unused
tile is evicted when the cap is exceeded. The store never retains a live
`Chunk`.

The store feeds `DistantHorizonsRendererHook`. Its producer uses one stable
4 by 4 base lattice across the visible LOD radius and adaptively refines
high-relief and water/land boundaries to 2 by 2 or exact columns. It rejects cells inside the
native near-terrain seam, excludes only chunks with an uploaded native mesh,
and closes unequal coarse/fine edges with unit-sampled, coalesced vertical
skirts capped at 32 blocks. Water coverage is planned separately; only water
columns emit retained opaque beds, and those beds never emit skirts. Explored
chunks, restored persistence, locally generated terrain, and managed-server
responses all publish this same tile contract and retain source provenance.
Cross-chunk hierarchical octrees remain outside the renderer.

### Cross-session persistence

`DistantLodPersistence` stores one database per connection/world identity under
the active trajectory profile's `distant-horizons/` directory. The key is a
SHA-256 digest of the protocol version, connection identifier, and world name;
the filename does not expose server details.

The `MDHL` version-1 format is GZIP compressed and palette-encodes resource
locations rather than serializing live registry IDs. Every decode bounds the
tile count, material-palette count, UTF-8 string size, and compressed file
size. `DistantLodPersistenceWriter` coalesces dirty snapshots on the I/O pool
and atomically replaces the destination through a same-directory temporary
file, with a non-atomic move fallback where the filesystem requires it.
World exit and adapter closure perform a final flush before clearing session
state.

This is intentionally not an implementation or migration of upstream DH's
database schema. Corrupt, incompatible, or oversized source-native data fails
closed without entering the renderer.

### Unexplored generation and authority

`DistantChunkSpiral` lazily enumerates square rings outside native view
distance without allocating a radius-sized queue.

For `LocalConnection`, `DistantUnexploredGenerator` invokes the already
selected authoritative local `ChunkGenerator` into a detached `ChunkBuilder`.
It captures only the resulting surface/material/water-bed tile and never
publishes that builder as a native loaded chunk. A configurable per-client-tick
budget and radius bound the work.

Remote clients never run a speculative copy of server world generation. The
managed Fabric bridge instead validates an explicit requested position,
deliberately calls the authoritative `ServerWorld.getChunk`, and captures the
result. Its per-player queue is capped at 128 tiles and it generates at most
one tile per player per 20 server ticks.

### Source-native network transfer

The managed server advertises `minosoft:distant_horizons_lod` with `MDHN`
protocol version 1. The client sends nothing before that HELLO. Request and
response messages are bounded to 32 tiles and one MiB, identifiers are bounded
to 512 UTF-8 bytes, server requests are radius checked to 256 chunks, and all
decoders reject trailing data.

The client requests only missing positions outside native view distance,
expires stale entries, and holds at most 32 pending tiles so the producer's
one-tile-per-second budget supplies backpressure. Modern outbound Fabric payloads
use the custom-payload packet's raw remaining bytes; adding a nested
byte-array length was found live to prevent the Fabric receiver from seeing
the message and is now avoided by the explicit `sendRaw` boundary.

This protocol is a Minosoft managed-server feature. It does not claim wire
compatibility with upstream Distant Horizons servers.

### Native settings

`DistantHorizonsOptions` registers one persisted `SettingsSchema` with
rendering, world-generation, storage, and multiplayer categories. It exposes
enablement, render distance, local-generation radius/budget, persistent
database capacity, managed-server radius, and request size. Capacity changes
are restart-required because they size the session store at world entry.

The screen uses Minosoft's shared settings system. It does not execute or claim
pixel parity with upstream Mod Menu/Cloth configuration screens.

### Compact render ABI

The distant mesh owns a provider-neutral 24-byte vertex:

- three-float render-origin-relative position;
- packed RGBA color;
- packed block/sky light nibbles;
- one packed word containing the six-axis normal index and exact Iris/DH
  material id.

The shader transformer decodes the upstream material ids 0 through 15 and the
DH normal ordering at the shader boundary. The graph uses distinct
`DISTANT_TERRAIN` and `DISTANT_WATER` semantics; neither replaces or falls
through to the selected native near-terrain backend.

### Exact dependency exception

The pinned DH metadata declares that it breaks Iris versions through 1.7.4,
including the pack's 1.7.2 artifact. That binary incompatibility is not the
runtime relationship used here: Minosoft links neither mod and activates both
through exact source-native adapters.

Preflight therefore has a typed `FabricDependencyIssue` boundary. Only the
combination below is accepted:

- issue owner `distanthorizons` version `2.4.4-b`;
- relation `breaks`;
- dependency `iris`;
- sole matched provider `iris` version `1.7.2+mc1.20.4`.

Every other owner, relation, DH version, Iris version, missing dependency, or
provider set continues to fail closed.

## Bliss ingestion compatibility

The exact Bliss archive first exposed six pack-reader assumptions. The planner
now matches Iris/OptiFine behavior for these source forms:

1. empty `dimension.<directory>=` placeholders are ignored rather than rejected;
2. option discovery excludes inactive dimension program directories while
   retaining shared includes and the selected directory;
3. conditionally inconsistent internal defines are removed from the option
   surface, while a referenced ambiguous option still fails later validation;
4. option comments may contain prose around the bracketed choices, and a
   repeated comment prefix is accepted;
5. program-enabled expressions can reference active platform defines such as
   `IS_IRIS`;
6. a missing bracketed notice whose id ends in `_IS_NOT_SUPPORTED` is treated
   as a non-navigable notice, while ordinary missing subscreens still fail.

With those changes, the archive crosses bounded ZIP ingestion, Overworld
directory selection, include/options/profile/property processing, conditional
program selection, and settings model construction.

## Bliss executable contract

The exact external-pack plan now passes
`IrisWorldShaderPipeline.validateProgramContract` with `dh_terrain`,
`dh_water`, and `dh_shadow` enabled only when the exact Distant Horizons
renderer registration is present.

The retained contract includes:

- source-native routes for Bliss's enabled scene families and water program;
- retained legacy `shadow` specializations for player, entity-flame, block,
  flashing-block, and both skeletal states;
- a retained legacy `gbuffers_textured` world-border specialization and an
  explicit discard program for Bliss's `clouds=off` policy;
- legacy `texture` and `tex` diffuse aliases plus neutral default
  `normals`/`specular` companions where a retained layout has no material
  texture;
- float `dhNearPlane`/`dhFarPlane`, integer `dhRenderDistance`, and matrix
  `dhProjection`, `dhProjectionInverse`, and `dhPreviousProjection` frame
  inputs, matching Iris 1.7.2's exact uniform types;
- a zero default for Bliss's undeclared `Moon_Weather_properties` input;
- independent full-viewport `DEPTH32F` `dhDepthTex`/`dhDepthTex0` and
  pre-water `dhDepthTex1` resources.

The DH projection retains the host field of view and aspect ratio while using
an independent 4,096-block render-distance contract and the Iris/DH 512-block
far margin. Distant opaque rendering writes its own depth attachment; the graph
copies that depth before distant water without contaminating native
`depthtex0..2`.

## Verification

Passed:

```text
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.DistantHorizonsCompatibilityAdapterTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisProgramFallbacksTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisOpenGlRenderTargetsTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisFrameStateTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisLegacyShaderTransformerTest \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisShaderPackPlannerTest \
  --tests de.bixilon.minosoft.gui.rendering.system.base.shader.code.glsl.GLSLCommentStripperTest

./play.sh modpack prepare distant-horizons-bliss \
  --trajectory distant-horizons-bliss-integration

./play.sh modpack inspect distant-horizons-bliss \
  --trajectory distant-horizons-bliss-integration

./play.sh start client --local-world --modpack distant-horizons-bliss \
  --trajectory distant-horizons-bliss-integration

./play.sh wait client.render-ready --timeout 120s \
  --trajectory distant-horizons-bliss-integration --json

./gradlew :test
./gradlew assemble
```

The focused client pack inspected as four adapted mods. Distant Horizons had empty
blocker and dependency-issue ledgers, and Bliss staged as the single managed
shader archive. The selected managed server-support view adds Fabric API,
Terralith, Tectonic, and the owned debug/LOD bridge without placing
server-only world-generation artifacts on the client classpath.

The exact Bliss external planner test advances through planning and settings,
and now passes the executable contract with the three DH program roots present.
The final Java 17 unit gate passed 2,127 tests with two configured optional
tests skipped, and the complete assemble gate passed.

The isolated local-world run published Bliss shader generation 1 on the Apple
M4 Max OpenGL 4.1 driver. At frame 2,348, `render.substrate` reported:

- compiled `dh_terrain` and `dh_water` main-view routes plus the dedicated
  `dh_shadow` auxiliary-view route;
- one exact `dh_terrain` submission and one exact `dh_shadow` submission over
  the same 4,992-vertex detached mesh;
- no distant-terrain fallback bind;
- advancing `before_hand`, `before_translucent`,
  `distant_before_translucent`, and `shadow_before_translucent` depth
  snapshots;
- the owned distant-terrain, distant-depth-copy, and distant-water graph
  passes in order.

An initial checked capture exposed one ABI mismatch:
`dhRenderDistance` had been uploaded through `glUniform1f`, while Iris 1.7.2
and Bliss declare it as `uniform int`. After changing the immutable distant
frame state and upload boundary to an integer, a diagnostic build with
per-call OpenGL assertions enabled ran beyond frame 2,300 without an error.
The subsequent 3456 by 1910 capture
`9560f0b013f9b4e03f631302f8a374c5cd5e0c956503e7d2a395eff546088b08`
showed the Bliss sky/fog and distant explored surface without the former
driver-error overlay.

The completion pass then exercised the exact archive again on the same Apple
M4 Max OpenGL 4.1 driver:

- 72 main-view scene specializations and seven shadow specializations linked;
- the shadow view selected exact `shadow` routes for the player and
  entity-flame producers instead of falling back to their host shaders;
- the main view selected the authored `gbuffers_textured` world-border route;
- `dh_terrain`, `dh_water`, and `dh_shadow` all received physical draws in the
  debug local world, with empty rejected- and fallback-bind ledgers;
- the debug run accumulated 60,576 `dh_water` vertices while solid terrain and
  its shadow accumulated 5,359,758 vertices each at frame 1,286;
- all four depth snapshots advanced through frame 1,287;
- an explicit shader reload advanced shader generation 1 to 2, rebuilt the
  exact 69-main/7-shadow pre-world-border set, and resumed DH submissions with
  empty rejected- and fallback-bind ledgers.

The final flat-world capture is
`3d741e0a103ebb40084119d037e1e8a36d3e5f3a58ff14ae0c1140e400ca1540`.
The debug-world distant-water/relief capture after fluid-bed support is
`2b11e4b2359fe117c0960359d1192b2884702b49345e3a9d0aa430493fb64f94`.
These are diagnostic captures, not platform-qualified zero-tolerance
references.

### Large diverse world and optional-function live gate

The optional producer layers were exercised against a new isolated managed
server directory:

```text
trajectory: distant-horizons-large-diverse
world: world-diverse-large-2026-07-29
seed: 7219035841762202609
server root: ~/Library/Caches/Minosoft/world-servers/
             distant-horizons-large-diverse-2026-07-29
server support pack: distant-horizons-bliss
```

The fresh Fabric 1.20.4 server loaded Terralith and Tectonic/Terratonic before
world creation. `server.properties` retains the normal 29,999,984-block maximum
world size, creative/flight access, view distance 16, and simulation distance
12. This trajectory is the requested large diverse biome map; it is isolated
from the repository's shared `server/` runtime state.

The first launch exposed that the play utility's hard-coded `fabric-stack`
support view also installed Naturalist, whose registry was intentionally not
part of this focused client. That attempt was moved intact to
`backups/world-diverse-large-2026-07-29.naturalist-rejected-attempt`.
`MINOSOFT_SERVER_MODPACK` now selects the exact support pack while preserving
`fabric-stack` as the default.

Live acceptance then established:

- the client joined `minecraft:overworld` in creative at
  `(-8.5, 163.0, -2.5)`, with 453 native chunks loaded;
- `render.substrate` retained Bliss shader generation 2, 72 main and seven
  shadow routes, all three DH route selections, advancing distant/native/shadow
  depth snapshots, and empty fallback/rejection ledgers;
- the source-native database grew to 117 KiB from explored terrain, and the
  next client process logged `DISTANT_HORIZONS_DATABASE_LOADED tiles=453`
  before rendering resumed;
- the server HELLO survived the registry/world-entry transition, the scheduler
  selected native seam radius 11 and outer radius 128, and the first request
  contained 16 explicit positions;
- after correcting the modern raw custom-payload boundary, the initial transfer
  probe reached 92 accepted requests and 208 generated tiles while the client
  logged its first and 128th received tile;
- the coalesced database subsequently grew to 156 KiB, proving that
  network-produced unexplored tiles entered the same persistent store.

That initial probe also exposed an unsafe throughput mismatch in the
pre-backpressure build: the old client filled a 512-tile queue continuously,
the bridge attempted two new Terralith chunks every server tick, and the
dedicated-server watchdog eventually stopped the process after 4,138 loaded
chunks while resolving another far chunk. The retained world and crash report
made the cause explicit at `DistantHorizonsLodServer.capture`; this was not
treated as a renderer or terrain-corruption failure. The accepted policy now
caps the client at 32 pending positions, the server at 128 queued positions,
and authoritative generation at one chunk per player per second.

The final paced run loaded the existing 1,303-tile database exactly once. At
182.6 seconds of server uptime it had retained 20 ticks per second, accepted
170 requests, produced 168 authoritative tiles, and held the active queue at
the 32-position client ceiling. The coalesced database had reached 317,874
bytes.
At client frame 1,472, Bliss shader generation 2 retained exact
`dh_terrain`, `dh_water`, and `dh_shadow` routes; all four relevant depth
snapshots advanced through frame 1,473, with empty rejected- and fallback-bind
ledgers. The final intentional player state is `minecraft:overworld`, creative,
at `(-8.5, 163.0, -2.5)` with yaw `114.402534` and pitch `20.17342`.

The client and server were intentionally left running on this trajectory at
handoff. No reference/canary presentation override was activated.

### Built-in Minosoft fallback

A 2026-07-30 no-pack replay found that the graph already retained the exact
`minosoft:distant-horizons/terrain`/`distant_terrain` and
`minosoft:distant-horizons/water`/`distant_water` passes, but the native
distant shader still used the near-terrain projection and the detached quad
builder selected the inward index winding. The first defect clipped far quads
into large polygons across the sky; the second culled their top surfaces and
left only fragmented inward-facing relief walls.

The built-in route now:

- preserves the active host field of view and aspect ratio while deriving an
  independent far plane from the configured LOD radius;
- declares distinct `DISTANT_TERRAIN` and `DISTANT_WATER` program families;
- indexes top, relief, and water-bed quads with their outward winding; and
- decodes the retained normal ID for inexpensive native face shading.

A subsequent large-world replay exposed two producer defects that the earlier
flat/debug captures did not exercise. Detached tiles were still emitted when
their exact chunks were currently owned by native terrain, so the independent
DH projection could draw coarse water and terrain over a loaded shoreline.
Coarse 4/8/16-column cells also selected the highest sampled surface, allowing
one tree or rock column to become a full-cell pillar. The renderer now includes
the uploaded native-mesh ownership revision in its cache key, snapshots
render-ready native chunk keys under the mesh lock, and excludes only those
tiles from the detached plan. Packet-loaded chunks whose native mesh is not
ready remain covered by LOD. Each remaining cell selects the deterministic
median retained column instead of the maximum.

With the Bliss presentation disabled on the Apple M4 Max OpenGL 4.1 driver,
graph generation 19 retained both exact passes with empty rejection/fallback
ledgers. The checked 3456 by 1910 capture
`de84f700849bfcbf7e279eb6576f59ddc492526b7fe91e2039ac1c6b98fe1da2`
shows native near terrain transitioning to a continuous coarse green/sand
shoreline and translucent blue distant water. It supersedes the clipped
baseline
`4eb3c51b56f4c2e1793647120e1c27c0295bfe8b732d516f04722e534046d668`.

The diverse-world defect capture
`c6b1cfe9304b2a98e358cb4f7048b0130d03fbb09b903a9d4de1fe33ecdc8811`
shows both the loaded-shoreline overlap and amplified pillars. After the
loaded-chunk exclusion and median aggregation changes, capture
`ae908c77016a3f2cf1b5be412bf0e51d470e03b68e9b6a4be59c5023c5fe19d6`
shows the native cliff winning the seam with the pillar field removed. The
remaining black first-person arm was initially mistaken for an authored dark
skin. A later generated cyan/magenta reference texture reached the exact hand
draw yet remained black, proving the defect was in the hand shader rather than
DH or account skin state.

### Large-store render-thread and Bliss occlusion regression

The retained database later reached 16,384 tiles and exposed a scale-dependent
freeze in the built-in producer. `DistantCellKey` had inherited the data-class
`31 * x + z` hash, which collapses the regular four-block coverage lattice into
long `HashMap.TreeNode` chains. A live render-thread dump repeatedly stopped in
that lookup for more than ten minutes. The coverage key now mixes both axes,
and retained planning plus CPU mesh construction runs on one coalescing daemon
worker. Snapshot and native-chunk revision checks remain cheap on the render
thread; only completed mesh upload and replacement return to the OpenGL owner.
Cancelled or superseded CPU meshes are dropped exactly once during reload and
unload.

The same replay found an independent Bliss compositing defect. DH solid and
water programs write the shared pack gbuffer colors while using dedicated DH
depth textures. Scheduling them after native opaque terrain therefore allowed
far color to overwrite nearer native color even though the depth domains were
not comparable. The retained order is now distant solid, the pre-water
`dhDepthTex1` copy, distant water, then native opaque terrain. Native terrain
therefore becomes the final color/depth owner at the seam.

The two first-/third-person defect captures
`d8fe94a711a66f4a2576546f99c37bf717e6fe6a51c91e2ae308537f385a791b`
and
`cb26409c0fc77a891a5f6229ac48f64159c6851558c681c555c09428c84354ce`
show the former full-frame red/green distant noise. On the corrected generation,
`render.substrate` reported the exact distant-solid, depth-copy, distant-water,
and native-opaque order with empty route fallback, while frames advanced
normally with the 16,384-tile store. The 3456 by 1910 Bliss capture
`7e443510fdb120b8f865382d2b4e84e469e6b05a9985d197bfb5b3097e91a089`
shows native water and terrain remaining in front of the distant seam. This is
functional regression evidence, not a platform-qualified checked pixel
reference.

### Adaptive geometry, diagnostics, and hand acceptance follow-up

The 2026-07-30 diverse-world follow-up added non-persistent
`mods.distanthorizons.presentation` A/B control and a bounded
`mods.distanthorizons.render-diagnostics` snapshot. The latter reports tile and
cell counts, uploaded-native exclusions, 1/2/4 cell-size counts, material
surface/source provenance, skirt counts, and maximum skirt drop without
retaining meshes or emitting per-frame strings. One 16,384-tile build reported
134 render-ready native exclusions, 341,769 planned cells, and a maximum skirt
drop of exactly 32.

Focused planner tests now cover adaptive four-block relief, exact extreme
columns, isolated-spike rejection, coalesced skirts, explicit 2-to-4 adaptive
transition stitching, coastline-to-water targets, skirt-free water beds,
source provenance, and render-distance/native-ownership exclusion. Valid
retained heights with a temporarily absent palette material use the neutral
fallback instead of opening a transparent hole.

The same live frame then exposed a circular terrain band at the former 4-to-8
distance transition. Median aggregation made the two sides geometrically
discontinuous on high-variance Tectonic terrain, and closing that discontinuity
with skirts merely turned it into a visible wall. The production planner now
keeps the 4-by-4 base lattice for the full visible radius; adaptive 2/exact
refinement remains local. This deliberately favors stable geometry over the
unqualified 8/16 coarsening rung until a morphing transition mesh is available.

A subsequent clean-frame A/B capture from the same trajectory exposed high
Tectonic peaks whose first 32 skirt blocks rendered while the remaining
high-to-low edge was left open, making the peaks appear suspended. Extending the
skirts to the full source delta closed the gap but visibly invented enormous
curtain walls because the detached surface contract does not retain DH's
vertical bands. The planner instead stabilizes the rendered surface graph from
low to high so every retained neighbor step fits the 32-block closure bound.
Sampled min/max heights remain unchanged for diagnostics. This forms a bounded
approach to extreme relief without an open interval, a full-height invented
wall, or a cell-size boundary. Live review of the first bounded version showed
that consecutive 32-block steps still collapsed into striped towers at distance.
Follow-up captures converged on a 4-block terrain-neighbor step while retaining
the 32-block hard skirt ceiling. Exposed snow, grass, and leaves close with the
neutral stone side material rather than stretching their top color down a cliff.
The daylight frame then exposed refined arch-shaped holes from explicit or
invalid air columns inside otherwise retained Overworld tiles. Surface sampling
now excludes air before aggregation and fills only bounded intra-tile gaps from
the nearest valid columns; a genuinely all-air tile still emits no geometry.
The next angle distinguished a remaining whole-tile opening. The configured
96/128-chunk generation and network schedulers could enumerate more positions
than the 16,384-tile store retained, letting later outer rings evict a contiguous
middle region. Both schedulers now cap their effective radius to the largest odd
square covered by the configured capacity. The default retains a continuous
63-chunk radius (127 by 127, 16,129 positions) instead of a sparse 96/128-chunk
annulus; the render far plane remains independently configured.

The first-person acceptance control now generates an opaque 64 by 64
cyan/magenta checker skin in the existing dynamic texture array and binds it
only for the arm draw. It reports draw count, draw frame, selected/reference
texture IDs, and whether the reference ID reached the draw; the account/player
skin is never changed. The hand vertex shader no longer transforms normals
through the projection matrix, and the hand fragment route no longer applies
world-distance fog to clip-space coordinates. Capture
`cdd147696a4680a861f2f5b8a1e0036e32d07a9a0824350569ea06321ad13b85`
shows the reference arm in cyan/magenta. The reusable
`acceptance/scenarios/distant-horizons-geometry-hand.json` scenario validates
the diagnostic bounds and exact reference-texture draw, then restores both
overrides.

### Built-in boundary and ordinary-hand follow-up

The 2026-07-30 built-in-shader replay found two independent presentation
defects. Player and first-person meshes had been baked against the physical
subrectangle of the static debug texture and later sampled from a dynamic
64-by-64 skin. `PlayerSkinUvTexture` now supplies identity UV transforms while
the actual player texture remains a draw-time binding. The ordinary account
skin therefore renders through the same arm mesh that the cyan/magenta canary
exercises.

The first inner-seam treatment mixed all detached output toward opaque fog,
which produced a dark ring. Replacing that with an alpha ramp on distant water
then exposed the coarse opaque water bed as a pale terrain belt. The built-in
route now applies fog only at the independent far boundary, makes distant
water opaque at LOD scale, and discards the water-bed depth-support quads.
Water beds carry a marker above the eight-bit DH material field; the Iris
bridge continues to mask the authored material ID to eight bits, so Bliss
still receives and renders the bed through its required terrain/depth route.

Generation 7 retained the built-in owner and the separate
`minosoft:distant-horizons/terrain` and
`minosoft:distant-horizons/water` passes. The same-pose disabled control is
`9ed0ac6f0a31ab7e1ee307a5fc802b17822f558690aeca1a3fde551bfcb79165`;
the settled enabled capture is
`3a93030139463d418b5039562315ca8cce5b980df555982a62b8073a3d95938d`.
The enabled frame has continuous ocean coverage without the pale terrain belt
and shows the ordinary textured first-person hand. The final
`distant-horizons-geometry-hand` scenario run passed with 16,384 retained
tiles, 162 uploaded native chunks excluded at the handoff, 358,582 planned
cells, and a four-block maximum skirt drop.

## Next rung

Add platform-qualified checked near/far seam, water, relief, first-person hand,
cross-dimension, and temporal-stability pixel references, then repeat them on
another driver.
Optional functional parity still excludes upstream database migration,
upstream network interoperability, upstream generator overrides and screen
classes, and cross-chunk hierarchical octrees; none is required for the
completed DH/Bliss render ABI or the source-native optional layers recorded
here.
