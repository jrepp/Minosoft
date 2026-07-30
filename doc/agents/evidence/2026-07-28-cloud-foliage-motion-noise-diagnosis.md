<!-- Copyright (C) 2026 Jacob Repp -->

# Cloud and foliage motion-noise diagnosis

## Outcome

The same-pose motion-noise probe separates three authored contributors without
showing a broken Minosoft scene route or a duplicate physical submission:

- Complementary Unbound's volumetric clouds are fullscreen shader content.
  `visual.prepare-reference` suppresses Minosoft's ordinary cloud producer but
  does not disable those pack-authored clouds. Use the pack's
  `CLOUD_QUALITY=0` or `CLOUD_STYLE_DEFINE=0` option for a real cloud-off A/B.
- Complementary's grass and leaf vertex waving is a major contributor in the
  foliage crop. At the immediate returned frame, disabling
  `WAVING_FOLIAGE` and `WAVING_LEAVES` reduced mean luma error from `15.91` to
  `9.50` and flat-region changed pixels from `51.74%` to `24.89%`.
  These two runs were below the probe's accepted cadence and are directional
  diagnosis, not an acceptance baseline.
- At one unchanged pose with clouds disabled and representative cadence, the
  pack's default low TAA jitter increased immediate flat-region change from
  `0.124%` to `0.335%` (`2.69x`) while mean luma error remained nearly
  unchanged (`1.456` versus `1.474`). Jitter amplifies visible speckle, but was
  not the main source of image-error energy in that crop.

The accepted renderer diagnostics continued to show advancing fullscreen
programs and cloud, terrain, and entity scene binds with empty rejection and
fallback maps. Material animation diagnostics remained empty. This supports a
pack-quality/options diagnosis rather than a missing history flip, duplicate
scene submission, or animated acceptance fixture.

## Terrain publication and distance boundary

The active main-view terrain path does perform distance and visibility culling,
but it does not use a separate foliage distance. The trajectory's effective
block view distance is `10` chunks. Chunk retention and meshing admission use
the square horizontal view-distance test, while section visibility also uses
the world's `+/-12`-section vertical limit. Loaded candidates are then filtered
by the camera frustum and the section-connectivity traversal before the common
visible list is submitted as opaque, cutout, translucent, or text terrain.
Cutout foliage therefore shares the same selected-section set as ordinary
terrain.

Distance-detail types exist, and the active block profile has LOD enabled, but
the current mesher explicitly requests `ChunkMeshDetails.ALL`;
`ChunkMeshDetails.update` also retains the previous detail immediately. There
is consequently no active distance-driven foliage simplification or
regeneration boundary in this run. Shadow terrain has a separate
pack-authored distance/culling path.

At one stable pose, repeated stationary substrate samples reported identical
visible totals:

- opaque: `260` meshes / `582714` vertices
- cutout: `277` meshes / `834882` vertices
- translucent: `291` meshes / `828192` vertices
- text: `0` meshes / `0` vertices

The same four mesh, vertex, load-state, and occlusion maps were byte-for-byte
equal before and after a reversible yaw-away/return probe. That probe was below
the image metric's cadence gate, but the exact substrate equality is still
valid culling-state evidence. It rules out persistent section remeshing,
publication churn, or draw-distance eviction as the cause of the immediate
same-pose residual at that camera. A later small yaw change changed the
frustum-selected totals as expected; that is not generation churn.

### Draw-distance follow-up

A user-requested follow-up raised both the trajectory block view distance and
the dedicated-server `view-distance` from `10` to `16`, leaving client/server
simulation distances unchanged. After a graceful managed restart the client
loaded `1021` chunks instead of the earlier `453`, and one settled view exposed
`522` opaque, `516` cutout, and `642` translucent meshes. The exact shader
fingerprint and player state survived the restart, but continuous
shader-enabled rendering remained below `20 FPS`.

The accepted compromise is `12` chunks on both the trajectory and server, a
20-percent linear increase over the original distance. A second graceful
restart loaded `609` chunks. With the background limiter disabled, the
12-chunk Complementary scene varied around `12` to `19 FPS`, with roughly
`40` to `59 ms` median draw time. Terrain submission itself was only about
`8 ms` at p95 in the inspected sample.

A reversible presentation A/B isolated the remaining cost: the same 12-chunk
scene reached `60.03 FPS`, `5.18 ms` median draw, and `7.38 ms` p95 draw with
the Iris presentation disabled. Restoring Complementary returned the original
cost and fingerprint. The distance increase is therefore viable in the base
renderer; the full-resolution Complementary pipeline at `3456x1910` is the
current frame-rate boundary.

### Terrain publication crash and motion flicker

The later client exit was a terrain lifetime race, not a Complementary compile
failure. At `2026-07-29 01:25:13` the main terrain submission attempted to
draw an OpenGL vertex buffer already in `UNLOADED` state. A visibility rebuild
could snapshot a loaded section, then publish that stale candidate after a
concurrent replacement or unload had removed it and queued its GPU buffer for
retirement.

Loaded-terrain mutations now advance a revision. Visibility traversal builds
from one revision and publishes under the loaded-mesh read lock only if that
revision is still current; otherwise it retries. Mesh upload/replacement keeps
the loaded-map write lock through removal of the old visible entry and
insertion of the new one. That second boundary is necessary for visual
stability: deferring the new entry until the following visibility rebuild
prevented the crash but exposed a one-frame terrain hole during motion.

The focused `ChunkRendererTest` deterministically proves both sides: an
uploaded mesh is visible immediately, and a snapshot taken before its unload
cannot republish it afterward. The final Java 17 focused suite passed. Managed
Complementary generations retained fingerprint
`4a263e457d684a35fdfbfd5bab90464fc02096d4181f861dcd8f2af0b6d57d83`;
live substrate samples during real player movement and repeated client
generations reported only `loaded` visible terrain buffers, advancing terrain
submissions, and no repeated unloaded-buffer render fatal.

## Entity boundary

Entity route diagnostics remained clean and one ordinary skeletal draw pass was
retained per inspected mob. A checked medium-distance entity pixel A/B was not
completed: another live session changed the camera from
`yaw=-102.37587, pitch=20.005383` to
`yaw=-113.77587, pitch=87.00538`, and the resulting view occluded the
frustum-visible mobs behind nearby terrain. The probe preserved the new pose
instead of overwriting it. Do not claim an entity-specific visual cause from
this run.

The pack source nevertheless identifies a narrower mechanism to test.
`gbuffers_entities.glsl` writes ordinary entities with material mask `254`,
documented by the pack as `No SSAO, No TAA, Reduce Reflection`, and
`taa.glsl` returns before accumulating pixels with that mask. Global projection
jitter still moves the entity silhouette between frames. Translucent entity
fog additionally advances its Bayer dither with `frameCounter` while TAA is
enabled, and `ENTITY_GN_AND_CT` permits generated-normal and coated-texture
detail on entity materials. This is a source-backed explanation for possible
medium-distance shimmer, not checked pixel evidence that it dominated the
reported entity.

The host also has explicit entity distance boundaries. The active entity
profile uses `render_distance=-1`, which resolves to
`(serverViewDistance - 1) * 16`, or `144` blocks for the current server view
distance. Main-view entities are rejected by squared distance first, then by
frustum and terrain-occlusion tests. Dropped-item geometry has additional
detail tiers at `10`, `20`, `30`, and `48` blocks and retains no item mesh past
`48` blocks. Complementary's live shadow plan independently limits entity
shadow bounds to `24` blocks while terrain shadows extend to `192` blocks.
Those boundaries can cause disappearance or shadow popping, but they do not
explain a transient at an unchanged returned pose.

Two attempts to establish a visual entity target exposed fixture limitations.
The client-only dropped item follows production gravity and fell from
`y=92.25` to `y=54`, while naturally retained medium-distance mobs were behind
nearby terrain even when their frustum state was visible. A same-pose global
entities-present/entities-suppressed pair was completed, but both runs failed
the cadence gate and suppression did not consistently reduce the residual.
No entity-specific pixel or `ENTITY_GN_AND_CT` option conclusion is accepted
from that pair.

For the next entity check, first acquire or record ownership of a stable pose,
place one reversible retained entity against a terrain-free background, and
compare the same crop with `hideEntities=false` and `hideEntities=true`.
Retain the pack-authored TAA profile for the first pair, then repeat only the
`TAA_JITTER` option if the entity silhouette still shows movement-only
speckle. If the silhouette is stable but surface detail shimmers, repeat with
only `ENTITY_GN_AND_CT=false`.

## Measurement limitations

The first rerun began with an unfocused-client timing window containing about
`8 FPS` history. Although the probe disabled the non-persistent background
throttle, its initial 600-frame status window had not yet converged, so those
reports correctly set `measurementValidity.representativeFrameRate=false`.
Pre-acquire the compare-and-set throttle override and warm the timing window
before a sequence of option A/Bs, or accept only reports whose initial and
final cadence both pass the existing `20 FPS` and `50 ms` bounds.

A later foliage-static control crossed a large world-lighting transition at
the 32-frame checkpoint. Only the immediate same-pose comparison is retained
from that pair.

## Artifacts

Raw runtime artifacts remain trajectory-local and untracked:

- `.run/motion-noise/2026-07-28-debug-foliage-waving-jitter/report.json`
- `.run/motion-noise/2026-07-28-debug-foliage-static-jitter/report.json`
- `.run/motion-noise/2026-07-28-debug-foliage-waving-no-jitter/report.json`
- `.run/motion-noise/2026-07-28-debug-current-pose-jitter-on/report.json`
- `.run/motion-noise/2026-07-28-debug-cloud-suppressed/report.json`
- `.run/motion-noise/2026-07-28-terrain-list-return/report.json`
- `.run/motion-noise/2026-07-28-entities-present/report.json`
- `.run/motion-noise/2026-07-28-entities-suppressed/report.json`

The accepted shader fingerprint
`72427f870b8559430d9a05e18f7f5ffed0861cde00a194c40c894d243eeb930f`
and ordinary HUD/cloud/entity/particle presentation were restored. The last
temporary entity target, name override, and visibility override were removed,
no reserved client-only entity remained, the temporary low-jitter/waving/cloud
option experiments were returned to the original shader options, and the
background-throttle override was conditionally restored from `disabled` to
`default`.
