<!-- Copyright (C) 2026 Jacob Repp -->

# Complementary temporal-stability implementation

## Outcome

The managed Complementary Unbound r5.8.1 path now implements all six
noise-reduction actions identified by the 2026-07-28 diagnosis:

1. The Fabric-stack shader profile pins `TAA_JITTER=0`.
2. The same profile pins `WAVING_FOLIAGE=false` and
   `WAVING_LEAVES=false`.
3. If either waving option is deliberately re-enabled, terrain evaluates wind
   once at the current frame time and once at the prior frame time, projects
   the prior deformed position with the previous camera matrices, and writes
   the prior UV plus a validity bit to `colortex14`.
4. The final zero-jitter profile keeps material-mask-254 entity and hand
   silhouettes unjittered while the pack bypasses TAA for those pixels. As a
   defensive compatibility boundary, entity/hand programs with that fragment
   contract also have any authored `TAAJitter` projection call removed. The
   exact r5.8.1 entity/hand sources already omit that call; mixed-material
   block/item passes are intentionally not rewritten.
5. Waving amplitude tapers from 35 to 75 percent of the active far distance.
   Foliage alpha coverage remains active even when waving is disabled. It uses
   the texture footprint and derivatives to raise the distant cutoff, then
   applies a texture-anchored coverage dither and makes surviving cutout texels
   opaque. This avoids both camera-relative crawling and the dark fringe caused
   by promoting nearly transparent RGB texels into the unblended terrain pass.
6. `composite6` reconstructs a cloud sample position from the cloud linear
   depth, reprojects it through the pack's world/previous-camera reprojection,
   stores cloud depth plus the camera-medium marker in double-buffered `R16F
   colortex15`, and rejects history when the camera crosses the water plane or
   the cloud sample is out of bounds, uninitialized, or differs by more than
   the larger of eight blocks and five percent of the current sample distance.

These are narrowly detected Complementary transforms, not a new generic Iris
ABI. The transformer requires the exact authored function/output anchors and
leaves unrelated programs unchanged. A detected-but-incompatible revision
fails during planning rather than publishing a partial motion/history
contract.

## Runtime boundary

The final managed configuration keeps waving disabled, so `colortex14` remains
an inexpensive cleared `RGBA8` fallback sampled by `composite6`; no terrain
program writes it. `colortex15` is active `R16F`, load-preserved,
double-buffered history, and `composite6` writes
`colortex3,colortex2,colortex15`.

Mip-aware foliage coverage is independent of that waving conditional and
therefore remains active in this final disabled-waving generation.

The optional waving path was also exercised rather than left source-only. A
transactional live option reload enabled both waving options on Apple M4 Max
OpenGL 4.1. The accepted candidate reported:

- shader fingerprint
  `c5817411a87e71e03b072c8d9ad1778101c65f7e8e17d1c1a6eb20b7ba48c8f3`;
- `colortex14` as cleared, double-buffered `RGBA16F`;
- `gbuffers_terrain` outputs
  `colortex0,colortex6,colortex4,colortex14`;
- `colortex15` as load-preserved, double-buffered `R16F`;
- advancing `composite6`, with empty scene rejection and fallback maps.

The reload was then reversed through the same API. The final disabled-waving
generation restored fingerprint
`4a263e457d684a35fdfbfd5bab90464fc02096d4181f861dcd8f2af0b6d57d83`,
ordinary terrain outputs `colortex0,colortex6,colortex4`, the active
`colortex15` cloud history, and empty rejection/fallback maps.

## Quantitative sample

A bounded full-frame same-pose probe ran with the final low-noise options. The
immediate returned frame changed 0.1494 percent of flat pixels versus 0.0587
percent in the equal-frame stationary control. By the 32-frame checkpoint,
mean luma error was 1.020 times control and flat changed pixels were 0.1245
percent versus 0.0892 percent control.

This run is directional, not an accepted camera-motion baseline: its initial
timing window still contained the unfocused background limiter and therefore
failed the representative-cadence gate. The probe conditionally restored the
background throttle to `default` and reported `poseConflict=false`.

## Artifact triage and follow-up fixes

A later screenshot pass first removed two false renderer diagnoses:

- the dominant black/red wall was the real world-border producer while the
  player was outside the configured 1,024-block test world; the border center
  remained the documented `(-794.5, 614.5)`, and an in-border capture removed
  the wall without suppressing the pass;
- the procedural diamond points in the night sky remained when host entities,
  particles, clouds, and the world border were independently suppressed, so
  they are pack-authored stars rather than duplicate host submissions.

The shader-disabled A/B then exposed a separate base-terrain defect:
top fluid faces supplied logical `0..1` UVs directly to static texture arrays
whose LabPBR companions occupy fixed vertical pages. The ocean consequently
sampled across diffuse, normal, and specular pages and rendered dense radial
blue/black stripes. `FluidSectionMesher` now applies the texture's physical-page
UV transform to top faces as it already did for sides and bottoms. A checked
native capture removed the stripes. Texture-array layer varyings are also flat
across rasterized primitives, preventing interpolation between array layers.

The first-person hand now follows the pinned Iris depth contract: its projection
pre-scales clip-space Z by `0.125`, while the hand pass preserves the existing
world depth instead of clearing it. This keeps the hand in front without
destroying depth needed by later overlays and post-processing.

A final same-pose motion probe over 4,147,200 pixels showed an immediate
motion-only transient, but it converged by the first eight-actual-frame capture:
flat changed pixels were `0.02690%` after motion versus `0.01616%` in the
equal-frame control, and later 16/32-frame samples were below their controls.
The initial status still included the unfocused limiter, so the report remains
diagnostic rather than an accepted representative-cadence baseline.

## Underwater overlay compatibility

A later underwater capture exposed two independent full-screen overlay defects.
First, `SimpleOverlay` supplied logical `0..1` UVs directly to the static
material texture array, allowing the quad to sample outside its diffuse page.
The shared mesh path now transforms both corners through the texture's physical
page mapping before emitting vertices.

After that correction removed the broad page bands, the remaining contours and
speckling traced to an ignored Iris pack directive. Complementary declares
`underwaterOverlay=false` in `shaders.properties`, and pinned Iris 1.7.2 cancels
the vanilla underwater overlay when the active world pipeline returns false
from `shouldRenderUnderwaterOverlay()`. Minosoft instead submitted the vanilla
quad through `gbuffers_textured` on top of Complementary's own water
fog/post-processing, contaminating the pack's multiple render targets.

`ShaderPipelinePlan` now retains the typed directive with Iris's compatible
`true` default. `WaterOverlay` reads the immutable plan pinned for the current
frame and suppresses only that vanilla producer when the pack explicitly opts
out. Native rendering and packs without the property retain the overlay.
Focused UV, overlay-gate, and full planner tests pass.

The remaining daytime underwater fog noise was a separate Complementary TAA
failure. At `(-764.145,49.0,651.659)`, a clean TAA-on frame showed broad gray
history regions, black contours, and isolated speckles even though a
1,848-cell loaded-only client/server sample matched exactly and contained only
stone, water, coal ore, sand, and seagrass. Disabling Iris removed the
post-process; keeping Iris active while changing only `TAA_MODE=0` retained
smooth pack fog and removed the artifacts.

The first accepted workaround made `composite6` exit `DoTAA` before every
history read whenever `isEyeInWater == 1`. That removed corrupt history in the
original dark pose, but a brighter cave with a waterlogged glow lichen exposed
the limitation: Complementary varies its colored-light fog, volumetric light,
and banding dither by `frameCounter` specifically for TAA to accumulate. The
unconditionally current-frame underwater path therefore produced dense
full-frame speckling and obscured the light contribution.

`colortex15` now encodes the global water-medium marker alongside its cloud
depth. `composite6` rejects history for the first frame and whenever the
current and previous water markers differ, then keeps ordinary TAA accumulation
within the same medium. Cloud validation decodes the marker before comparing
depth. This rejects incompatible history at water entry and exit without
discarding the temporal accumulation needed to resolve underwater fog and
colored light. Ordinary above-water TAA, previous-wind motion, and cloud world
reprojection remain active.

Generation 2 hot-reloaded this refinement on the Apple driver. A matched-pose
`y=62`/`y=59` A/B aimed at the waterlogged glow lichen at `(-775,60,678)`
retained its wall-light contribution below water and removed the dense
speckling. The representative 5-degree movement probe ran at 71.5--71.8 FPS
with a 14.115 ms maximum median frame time. Immediate motion/control mean-luma
ratio fell from `3.331` with `6.022` excess to `1.086` with `0.021` excess;
the 4-, 16-, and 32-frame motion mean-luma results were all below their matched
stationary controls. The run reported `poseConflict=false`.

The final generation restored the untouched managed fingerprint
`4a263e457d684a35fdfbfd5bab90464fc02096d4181f861dcd8f2af0b6d57d83`,
reported no active or retired leases, and retained 44 textures, seven
framebuffers, 111 programs, and zero shader objects. The structural before/after
is not a same-lighting comparison because shared server time advanced from day
into sunset while the client hot-reloaded; world time was deliberately left
untouched. The final frame nevertheless removes the page bands, black contours,
and full-screen speckling at the same underwater player position.

## Underwater brightness parity

The clean current-frame result also exposed a separate, non-temporal brightness
mismatch. At `(-759.145,49.0,653.769)`, the world remained nearly black with
Iris disabled, while a loaded-only 729-cell client/server sample matched
exactly and the camera occupied a tall-seagrass cell above sand. Complementary
and native rendering therefore agreed on the dark input; TAA history, pack
fullscreen routing, fixtures, terrain ownership, and client world divergence
did not explain it.

The selected rendering profile stored `gamma=0.0`. Pinned Minecraft 1.20.4
`GameOptions` bytecode constructs `options.gamma` with a `0.5` default, and
Complementary consumes that value as `screenBrightness` when deriving its
underwater fog brightness. Minosoft's profile initializer and Lighting Reset
had both used `0.0`, unintentionally selecting the minimum brightness for new
profiles. `LightC.DEFAULT_GAMMA` now provides the shared `0.5` default for
profile initialization and Reset, with focused regression coverage. The
existing `default/fabric-stack` trajectory was intentionally moved to `0.5`
for its next managed launch. The subsequent generation-1/2 managed run loaded
that trajectory value and produced the matched water-plane light A/B above.

## Water-surface legibility and underwater obscuration

A later user-directed capture pair retained the same horizontal position near
`(-773.792,676.300)` while first looking down from `y=62.235` and then looking
toward the lit surface from underwater at `y=61.091`. From above, the water
plane was nearly indistinguishable from the exposed cave: it had no strong
reflection, refraction, tint boundary, or edge contrast. From below, the lit
surface collapsed into a mostly uniform dark blue-gray field and nearby
geometry became difficult to distinguish.

This pair is qualitative rather than photometrically matched: the camera
direction changed as intended and shared world time advanced from rainy night
to rainy day between captures. A loaded-only client/server sample around the
camera nevertheless agreed on water, stone, dirt, air, and the waterlogged glow
lichen. The exact managed shader fingerprint remained active, all fullscreen
passes advanced, rejection/fallback ledgers were empty, and the shader resource
ledger remained 44 textures, seven framebuffers, 111 programs, and zero shader
objects. The pair therefore records insufficient water-surface cues and
excessive underwater attenuation, not missing world data or a failed water
render route.

## Water-lighting correction

The remaining artifact came from two Complementary r5.8.1 source assumptions.
Its water-normal amplitude used only `lmCoordM.y`, so water in a block-lit cave
lost most of the normal response that makes the surface readable. Its
underwater composite then multiplied every surface by the same blue
attenuation, collapsing locally lit stone and ore together with genuinely dark
water. `IrisComplementaryWaterTransformer` now applies revision-anchored
corrections at resolved shader source: water normals use the stronger block- or
sky-light channel, and underwater attenuation tapers toward a near-neutral
multiplier only for already lit surfaces while retaining Complementary's
authored volumetric blue attenuation.

The managed pack also selects `WATER_ALPHA_MULT=180`,
`WATER_FOG_MULT=50`, `WATER_BUMPINESS=1.50`, and underwater color
`110/120/130`. This makes the surface distinct without turning it opaque and
keeps useful underwater depth cues without suppressing nearby light sources.
The exact Complementary archive planner gate and focused water, temporal, and
planner tests pass, as does `assemble`.

A clean managed generation loaded fingerprint
`33fe86c284760c322dd32926b4bce06cf3d87a666be9e976e4753a5a577ed41a`.
The final above-water capture shows a structured blue surface over the lit
cave, and the underwater cave capture retains warm local-light contrast on
stone, gravel, and ore. Client/server samples around
`(-779,57,677)..(-775,62,681)` agreed on water and surrounding blocks with no
unloaded cells. Every terrain, shadow, and fullscreen route advanced,
rejection/fallback ledgers remained empty, and resources stayed at 44 textures,
seven framebuffers, 111 programs, and zero shader objects.

## Apparent cave light bleed

A subsequent frame at `(-778.7, 64.0, 647.984)`, yaw `-2.548`, pitch `5.948`,
appeared to show daylight bleeding through a dark cave wall. A loaded-only
client/server sample over `(-783,60,643)..(-774,69,652)` instead located a
continuous west-facing vine plane at `x=-777`, with air and ocean behind its
transparent texels. Client/server differences were limited to equivalent
normalized vine property spelling.

The clean Complementary capture and shader-disabled capture retained the same
cutout silhouette. Native rendering showed the ocean directly through those
holes; Complementary's tonemapping made the day-lit exterior much brighter.
Opaque stone around the opening remained dark in both paths. Main and shadow
opaque, cutout, and translucent terrain routes all advanced, both depth-copy
boundaries advanced once per frame, and rejection/fallback maps were empty.
This checkpoint therefore does not establish terrain or shadow light leakage,
and no renderer correction was applied. The original pack fingerprint and HUD
state were restored after the A/B.

## Validation

- `IrisTemporalStabilityTransformerTest` covers previous wind deformation,
  motion output, distance fade, always-active texture-anchored mip coverage,
  single-injection cloud history, disocclusion rejection, and material-254
  jitter removal.
- `FluidTextureCoordinateTest` covers physical diffuse-page UV mapping, and
  `TextureLayerInterpolationTest` keeps array-layer varyings flat.
- `SimpleOverlayTextureCoordinateTest` covers static diffuse-page UV mapping,
  `WaterOverlayTest` covers the pack gate, and `IrisShaderPackPlannerTest`
  covers the typed `underwaterOverlay` directive and compatibility default.
- `FirstPersonItemTransformTest` covers the hand-only depth projection.
- `LightingControlsTest` covers the pinned Minecraft 1.20.4 rendering-profile
  default; `LightingMenu` consumes the same constant for Reset.
- The exact Complementary archive crossed the complete planner/source
  translation gate with the final disabled-waving options.
- The same exact archive crossed that gate with both waving options enabled.
- Final disabled-waving and temporary enabled-waving candidates both compiled
  on the live Apple driver. The enabled candidate proved the otherwise dormant
  motion target format and terrain output routing.
- The managed pack inspection published immutable pack
  `a259400ab76a690784c4ebc763819c29c48cc939e0c83b0814735b5f9654b96a`.

Local, untracked runtime artifacts:

- `.run/motion-noise/2026-07-29-temporal-stability/report.json`
- `.run/acceptance/2026-07-29-temporal-waving-enable-v2/report.json`
- `.run/acceptance/2026-07-29-temporal-waving-restore/report.json`
- `.run/motion-noise/2026-07-29-artifact-fixes/report.json`
- `.run/motion-noise/2026-07-29-underwater-camera-movement-gamma05/report.json`
- `.run/motion-noise/2026-07-29-underwater-medium-history-camera-movement/report.json`
- `.run/agent-screenshots/2026-07-29-shader-artifacts-raw.png`
- `.run/agent-screenshots/2026-07-29-shader-artifacts-scenic-before-coverage.png`
- `.run/agent-screenshots/2026-07-29-fluid-uv-fixed-native.png`
- `.run/agent-screenshots/2026-07-29-underwater-complementary-before.png`
- `.run/agent-screenshots/2026-07-29-underwater-overlay-uv-fixed.png`
- `.run/agent-screenshots/2026-07-29-underwater-final.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-clean-before.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-native-ab.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-taa-off.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-final.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-final-day.png`
- `.run/agent-screenshots/2026-07-29-underwater-fog-noise-final-day-early-return.png`
- `.run/agent-screenshots/2026-07-29-underwater-light-below-water.png`
- `.run/agent-screenshots/2026-07-29-underwater-light-medium-history-final.png`
- `.run/agent-screenshots/2026-07-29-underwater-light-matched-above-final.png`
- `.run/agent-screenshots/2026-07-29-underwater-light-matched-below-final.png`
- `.run/agent-screenshots/2026-07-29-current-looking-down-water-above-clean.png`
- `.run/agent-screenshots/2026-07-29-current-looking-up-lit-surface-underwater.png`
- `.run/agent-screenshots/2026-07-29-water-lighting-final-above-close.png`
- `.run/agent-screenshots/2026-07-29-water-lighting-final-underwater-lit-cave.png`
- `.run/agent-screenshots/2026-07-29-current-too-dark-unmodified.png`
- `.run/agent-screenshots/2026-07-29-current-too-dark-world-only.png`
- `.run/agent-screenshots/2026-07-29-current-too-dark-native-ab.png`
- `.run/agent-screenshots/2026-07-29-terrain-light-bleed-current.png`
- `.run/agent-screenshots/2026-07-29-terrain-light-bleed-clean.png`
- `.run/agent-screenshots/2026-07-29-terrain-light-bleed-native-ab.png`

At handoff the player was restored to the recorded pre-correction pose
`(-778.7412588713222, 62.55402671082285, 679.6999999880791)`, yaw
`169.655`, pitch `21.838266`, in the overworld. HUD, hitbox, cloud,
world-border, entity, and particle presentation were restored, and the final
client/server generation remained ready after validation.
