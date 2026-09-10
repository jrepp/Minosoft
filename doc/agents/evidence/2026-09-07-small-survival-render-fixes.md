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

# Small Survival shader and entity follow-up

## Confirmed causes and changes

The initial visual review was followed by controlled shader isolation and source
inspection of the exact Bliss 2.1.0 archive. Camera cells were loaded air, no
acceptance fixtures were active, and entity/particle and DH suppression had
already ruled out those producers for the near hill artifact.

- Terrain's compatibility `gl_ModelViewMatrix` incorrectly used the camera
  matrix during the shadow pass. It now uses the light's host matrix. Authored
  shadow receivers have a separate camera-relative matrix, preserving the same
  light-space projection as render-origin-relative casters.
- Bliss temporal reprojection adds current-minus-previous camera position before
  applying `gbufferPreviousModelView`. That previous matrix still included host
  camera translation. The standard previous matrix now contains only rotation,
  matching the authored current model-view pair. This removes double translation
  from history lookup in TAA and reflections.
- DH's authored `gl_Vertex` now receives camera-relative positions. Its matching
  player-view/shadow matrices preserve clip-space geometry. Bliss's fractional
  camera rounding and world-position noise therefore remain anchored in world
  coordinates, rather than following the camera fraction.
- Generated entity/hand fragments and fullscreen vertices now use the same
  authored matrix convention. Custom uniform expressions use the player current,
  previous and shadow matrices together. The capture boundary keeps the active
  camera view in absolute world coordinates and render origin separate, including
  third-person displacement.
- Generic fallback living models used the player-only texture-zero sentinel.
  Iris's compact sampler cannot resolve it. They now use the real fallback debug
  texture. Unsupported species still use a humanoid fallback; this change does
  not supply llama geometry or a llama texture.
- Switching Iris off did not copy retained uniform values back to the native
  program. The native sky could consequently keep its default black color.
  Target transitions now synchronize retained uniforms once, with a guard for
  `AnyShaderUniform` callbacks that re-enter shader binding. The first version
  lacked this guard and crashed during startup; the guard has a regression test.
- A later live run exposed an independent entity shadow-sort crash. Entity
  removal clears its ID/UUID while retained drawables may still be sorted.
  Their previously dynamic final sort key is now frozen on first use, so removal
  cannot change the comparator ordering midway through a sort.

- Modern join and runtime view-distance announcements now own the native view
  distance. Incomplete chunk arrivals, movement and unloads no longer replace
  that announced value with a changing loaded-bounds estimate. Servers without
  an announcement retain the existing estimate fallback.

The earlier fullscreen-only shadow candidate remains rejected as recorded in
[its evidence](2026-09-07-small-survival-shadow-coordinates.md). This follow-up
corrects both producer and receiver and separately fixes temporal reprojection;
passing the earlier mathematical test alone did not establish those contracts.

## Visual boundary and observations

Creative player `(1.2384954965,77,-26.3799366729)`, inspection yaw `67.00009`,
pitch `7.7000103`, overworld; `small-survival` trajectory, `standalone` content,
Bliss authored options including `SSS_TYPE=3`. Client generation 1 after restart,
endpoint `client-94770-1-7a8a67af`, server `server-94435-1-232e6998`.
The startup camera pointed down; the inspection temporarily restored the earlier
angle and returned to the recorded current angle afterward.

Client-only time 4090, clear weather, background throttle disabled during the
probe; framebuffer 1800×1000, world viewport 1350×750. The full integration suite
opened a window and caused pause-menu contamination in part of the first sweep.
Those frames were discarded for visual acceptance and the sweep was repeated
with transient GUI state cleared at each capture after tests completed.

- Matched clean baseline/forward/backward and small-yaw stills no longer show
  the conspicuous pale tree contours or broken thin strips across the central
  hill. Strong green/dark shading remained for subsequent biome isolation.
- Native forward/left captures now have a blue sky after the Iris-to-native
  transition. Returning to Bliss succeeds without a uniform re-entry crash.
- The fallback entity has visible colored texture in native and Bliss captures.
- The isolated spruce still floats because both server and client contain air
  in three trunk positions. No world blocks were changed; this may be gameplay
  history and is not a culling repair.
- A short injected forward/backward movement sequence captured five consecutive
  frames in each direction. The severe contours/stripes remained absent, but
  larger inter-frame movement still produced some edge history/ghosting. Capture
  readback itself stalls frames; this is not representative-rate temporal or
  performance certification. Key release and exact pose/presentation restoration
  completed with no errors.
- SSS-off and screen-space-contact-shadow-off A/B checks did not remove the
  residual green shading. Disabling `PER_BIOME_ENVIRONMENT` removed it. The
  F3 overlay identifies the actual biome as `minecraft:lush_caves` (shader ID
  52), which Bliss deliberately groups with swamps in `isSwamps`. The olive
  direct-light color is authored cave/swamp presentation, not a texture-channel
  or biome-ID error. A narrower `SWAMP_ENV=false` A/B also removes the cast. This option is now
  persisted in the out-of-source Small Survival Iris profile; all other authored
  options are preserved. This is an intentional presentation adjustment, not a
  texture or biome-ID repair.

[Clean forward view](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/coordinate-fixes-clean/baseline.png),
[one block forward](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/coordinate-fixes-clean/forward-one.png),
[clean native sky](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/coordinate-fixes-clean/native.png),
[left view / world tree](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/coordinate-fixes-clean/left.png).
State, substrate, presentation and restoration metadata accompany those images.
Raw lifecycle logs are not tracked.

## DH coverage limit

The prior review's 21-versus-128-chunk distance was not merely slow hydration.
`DistantHorizonsCompatibilityAdapter.MAX_RESIDENT_PAGES` is 2,048 and the network
client bounds its contiguous request radius to that capacity: at most radius 22
(`45² = 2,025` base pages). The dedicated server currently serves detail level 0.
The ordinary server budget is one page per player per second. Distant drawing
requires effective coverage beyond the current native distance plus its seam
and two-chunk transition span, so fresh remote cache warm-up takes minutes.
The client profile is 10 but this server advertises 6. An additional source audit
found the view-distance packet handler was a no-op and the client instead
inferred its distance from the changing loaded-chunk bounding box. Do not use
the profile value as the live native/seam distance.
A configured 128 chunks therefore cannot become a complete 128-chunk footprint
through this path. Raising the page cap without a memory-bounded multiresolution
source plan is not an accepted repair.

Remote persistence is intentionally disabled because the source handshake has
connection/world epochs but no stable world fingerprint. Local persistence can
be enabled with a fingerprint; the profile's persistence option itself was not
false. Reconnects rebuild the remote cache. These are coverage/lifecycle limits,
not proof of an angle-dependent native culling error.

## Verification

Focused matrix/transformer tests pass, including fractional movement, negative
origins, shadow projection equivalence, and camera-motion reprojection across a
render-origin change. The exact Bliss archive planner gate passes. Shader target
handback/re-entry and fallback texture integration tests pass.
The broad root and render-contract suites passed. Two integration expectations
encoded the old inward terrain basis. Independent old/new byte comparison found
exactly 48 changed float words per 2,016-byte fluid stream, solely tangent
handedness and normal fields; the other bytes were identical. Updated BakedFace
normal and three fluid digests pass alongside the semantic parity suite. The
entity-removal ordering regression also passes through the real drawer.

The final full unit gate passed 2,260 tests with zero failures/errors (4 skipped),
and render-contracts passed 203 tests. These gates include the active-camera,
native visibility, built-in DH clipping and current hand-depth corrections.
Focused initialization/view-distance and frustum integration gates pass; the
full integration suite was not repeated after these final focused corrections.

## Warm distant review

On client `client-50921-1-87c913a3`, server `server-50746-1-d89507fa`, DH
reached an effective 13 chunks and began submitting distant geometry (1,068
commands at the first warm boundary; 846 at the reference forward angle).
These are boundary work counts, not performance comparisons. The latter snapshot
had 511 main desired/active pages, zero missing selected pages, and 123 masked
native pages. DH-off removed the far landscape. Small settled movement/yaw
captures did not show a new angle-specific hole, but the longer continuous
sequence did reveal geometry popping near the native boundary. Source arrival
was still ongoing, so this is not a static full-cache culling acceptance.
The ignored authoritative view-distance packet and moving inferred seam were
subsequently corrected. The focused integration gate passes three world-view
regressions and six multi-version initialization fixtures, including later
announcement replacement and the client profile clamp.

[Warm forward view](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/final-distant-review/baseline.png),
[DH disabled](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/final-distant-review/dh-off.png),
[movement with distant draws](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/motion-distant/KEY_S-3.png),
[biome-lighting isolation](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/biome-lighting/no-biome.png),
[actual biome overlay](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/biome-f3.png).


## Final candidate restart

The final candidate includes the active absolute camera capture and authoritative
view-distance fixes. Parent 84707, client `client-84936-1-0e8a31bd`, server
`server-84754-1-7c1b5251`; both joined/render-ready after restart. The same creative
position and starting angle were retained. Bliss remains enabled with
`SSS_TYPE=3` and intentional `SWAMP_ENV=false`; fingerprint
`470f848b4aef17276579e132370d27989171d3b4524fe3f91794e901c019da2e`.

The first cold continuous forward/backward sweep retained the same central hill
and trees across the chunk boundary. This was not sufficient acceptance: a later
warm DH-enabled sweep reproduced the third-tree/left-terrain pop, while the next
DH-disabled sweep retained three trees in both directions. Source-arrival and
DH ownership remain under isolation; the view-distance repair alone does not
close the issue. The exposed stone/snow is neutral after the narrow cave/swamp
lighting adjustment. This first sweep preceded DH's drawable frontier (effective
8 chunks), so it cannot establish the final native/distant handoff. It does not certify all temporal edges or distant coverage.

[Final forward motion](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/motion-authoritative/KEY_W-3.png),
[final backward motion](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/motion-authoritative/KEY_S-3.png).


## Native visibility and ownership isolation

The matched settled forward/backward DH-on/off check retained the three central
hill trees in both directions. Near upload/build queues were idle at each sampled
boundary, distant main publication had no missing selected pages, and masked
pages matched READY near coverage (back 51/51, forward 57/57, repeat 58/58).
This does not support an inverted DH ownership mask. It also does not erase the
transient omissions captured during movement.

Source review found two native visibility defects: traversal deduplicated by
section even though exit connectivity depends on entry face, and the default
section frustum bound used the minimum corner for both minimum and maximum.
The former can falsely omit already-built sections when traversal origin/order
changes; the latter delays meshing at frustum edges. Multi-entry traversal now uses entry/exit bit masks and a fixed-capacity ring;
each exit propagates at most once per cell and each section is emitted once.
The alternate-entry diamond and existing implicit-air traversal checks pass
(five unit tests). Full-section bounds pass five frustum integration tests.

The warm native-renderer A/B exposed a separate broad missing band under floating
treetops. Its built-in fragment shader's unconditional radial near clip can hide
base pages retained by exact CPU coverage masking. The bounded repair tags exact
detail-0 pages and allows their ownership decision to govern clipping while
retaining the radial overlap guard for coarse pages. Packed normal/material and
water-bed bits stay separate; compact physical layout generation becomes 2.
Five focused coverage/encoder tests and compilation pass. Bliss transforms its
own fragment and does not execute this built-in clip.

[Matched forward with DH](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/ownership-isolate/forward-dh.png),
[matched forward without DH](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/ownership-isolate/forward-off.png),
[built-in missing band before repair](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/final-authoritative-review/native.png).


## Hand imprint over DH

Matched `ownership-isolate/forward-dh.png` and `forward-off.png` expose a pale
scene imprint on the hand only with DH. Bliss's packed 0.75 hand marker was gated
by `depthtex1 < 1`; the host snapshots that depth before translucency and HAND.
Over DH-only background, native `depthtex1` remains 1, incorrectly rejecting the
hand classification and bypassing the existing hand-history rejection.
The exact Bliss transformer now validates the marker against current `depthtex0`
while retaining the stale-marker depth guard. Five focused transformer tests and
the exact external archive planner gate pass.


## Combined visibility candidate

The combined restart runs client `client-12672-1-891cfdc1`, server
`server-12444-1-d9e5199e`, parent 12399. Creative position remains
`(1.2384954965,77,-26.3799366729)`. The camera is intentionally left facing the
inspection hill at yaw `67.00009`, pitch `7.7000103`, instead of the former
downward startup view. This also warms meshing at the view being inspected.
The ordinary world time/weather remain authoritative between probes.


## Stopping checkpoint: visual acceptance remains incomplete

The combined candidate's final stills retain the corrected native blue sky and
neutral stone/snow presentation. The earlier conspicuous pale tree outlines and
thin broken hill strips are absent in matched stills. However, the final warm
movement sequence still adds/removes the third hill tree and left terrain, and
shows a pale hand imprint. The built-in native capture still has a broad missing
DH band beneath floating trees. The base-page clipping correction is therefore
partial, and neither the traversal correction nor hand-depth correction is
accepted as a complete repair of the observed pixels. Their focused contract
regressions pass; further source/visibility and buffer-boundary evidence is
required. Do not report all culling or motion artifacts as fixed.

[Final native DH gap](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/final-visibility-review/native.png),
[final forward motion and hand imprint](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/motion-final-visibility/KEY_W-3.png),
[final backward motion](../../../.run/diagnostics/small-survival-render-fixes-2026-09-07/motion-final-visibility/KEY_S-3.png).

All final probes restored without reported errors. Handoff status confirms
server/client ready and no external clients. Creative health 20, original position,
inspection yaw/pitch, Iris and DH enabled, no DH override, no time/weather or
entity/particle/arm/reference override. Ordinary authoritative time was night at
handoff. Intentional presentation state is the persisted `SWAMP_ENV=false` option.
The floating world tree was not edited. Full 128-chunk remote coverage and
unsupported species geometry remain outside the accepted result.

At the user's requested stopping point, code changes are saved and uncommitted;
the next debugging/query/probe work is assessed in the
[agent-native tooling plan](../backlog/agent-native-debugging-plan.md).
