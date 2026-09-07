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

# Small Survival shader inputs and distant coverage

## Scope and rejected candidate

The user reported far-terrain bands tied to forward/backward movement,
emissive-looking surfaces, and visibility gaps. The earlier fullscreen shadow
matrix candidate was rejected and reverted; see the separate
[rejection record](2026-09-07-small-survival-shadow-coordinates.md). This change
corrects two independently demonstrated defects. It does not establish that
every reported temporal artifact is resolved.

## Inward terrain normals

`CuboidUtil.positions` emits clockwise face corners. `IrisTerrainQuad` had
computed `(p1-p0) cross (p2-p0)`, which points inward for that production order.
For example, an UP face supplied `(0,-1,0)` to the shader instead of `(0,1,0)`.
Both the packed world-normal buffer and the flat view-normal buffer confirmed
this at the live scene. These incorrect inputs affect direct lighting,
normal-map orientation, scattering, and normal-dependent shadow bias.

Reverse the cross-product operands at the quad boundary. The existing tangent
handedness calculation follows the corrected normal, preserving the authored
UV bitangent. Terrain and held block geometry share this boundary. Vertex
positions, face culling, and shader shadow transforms are unchanged.

The new unit tests use actual `CuboidUtil` faces, all six directions, mirrored
UVs, a sloped top, and translated coordinates. Both tests failed against the
old calculation and pass after the correction. The existing integration test's
synthetic north-facing quad now expects its outward negative-Z normal and
matching tangent handedness.

## Missing network LOD ring

`DistantLodNetworkClient` started its square spiral beyond
`viewDistance + 1`, assuming native terrain covered the entire excluded square.
The live native chunk set was rounded. Tiles such as `(-8,-1)`, `(0,-9)`, and
`(-6,-7)` had neither native nor network data, while adjacent native and
network pages were resident. The missing ring held the effective DH distance
at nine chunks. The existing 32-block frontier rule then suppressed all
DH draws despite hundreds of resident and selected pages.

Start the request spiral at the player's immediate neighbours and retain the
existing actual-tile, pending-request, and received-v1 checks. Server capability,
radius/count limits, request expiry, cancellation, and protocol validation
remain authoritative. A regression test leaves exactly one tile missing inside
the native-view square: before the fix no request was sent; afterward that
one tile is requested once, with resident and pending tiles excluded.

The native region allocation-failure counter is not by itself evidence of
missing meshes: `MeshLoadingQueue` falls back to a dedicated mesh upload.
Likewise, the late `upload_pending` coverage states require a separate lifecycle
investigation before attributing visible gaps to allocation or culling.

## Live diagnosis

Reproduction pose: `(3.9027807791136357,77,-13.403256477500246)`, yaw
`76.400085`, pitch `7.700012`, creative mode in `minecraft:overworld`.
The Small Survival trajectory uses the standalone stack and Bliss 2.1.0.
The camera sample was loaded air and no acceptance material fixtures were active.
Matched comparisons pinned client presentation time to 4090 and temporarily
disabled background throttling, then restored both settings and the pose.

The artifact remained with TAA and contact shadows disabled. Built-in debug
views were useful for narrowing the pipeline but still received subsequent
fog and postprocessing. A temporary diagnostic pack copied from the exact
archive exposed packed and flat normals at the final pass with `texelFetch`;
it was restored to the original pack and option values after each probe.
The first raw diagnostic used framebuffer pixel coordinates directly and
therefore displayed the 1350x750 source in only part of the 1800x1000 window;
its geometry region is valid, while its out-of-bounds region is not evidence.
The corrected probe maps normalized coordinates to the source dimensions.

Local diagnostic inputs and frames are retained under
`.run/diagnostics/small-survival-banding-2026-09-07/`. They are inspection
artifacts, not pixel-accepted baselines or performance measurements. User
motion acceptance remains distinct from static capture and unit-test evidence.

## Verification and limits

- The full root unit task completed with 2,245 tests, 4 skipped, and no failures;
  the 203 render-contract tests were up to date and passing.
- All 14 focused integration tests passed across `IrisTerrainMaterialTest` and
  `DistantLodNetworkLifecycleTest`, including the new missing-neighbour case.
- One integration attempt raced the dev watcher's jar replacement and failed
  during test setup with `NoClassDefFoundError`/jar-scan failure. The same tests
  passed once the watcher finished; this was not a test assertion failure.
- Corrected raw world-normal captures show `(approximately 0,1,0)` on upward
  faces, and the matched final capture lights ground tops rather than undersides.
- The live distant store reported persistence disabled, so each client reload
  must refill remote tiles at the managed server's configured rate. No server
  rate or persistence setting was changed as part of this correction.
- This patch addresses the remote request scheduler. The local-authority
  unexplored generator's similar inner-radius rule is outside this remote
  reproduction and remains a follow-up boundary to evaluate.

After the missing ring filled on client generation 4, effective DH distance
advanced from 9 to 10 chunks and the render diagnostics changed from zero
draws to 877 draw commands / 5,646,612 submitted vertices. This is evidence of
resumed submission, not a performance comparison. The configured distance is
128 chunks, but actual hydrated coverage remains the limiting frontier and
continues to grow. The global frontier guard itself was not weakened.

## Material profile and handoff

The previous material handoff claimed persistence, but
`mods.iris.configure-options` changes the active options without calling
`persist()`. After restart, the trajectory's `iris.properties` lacked
`SSS_TYPE=3` and Bliss had reverted to block-ID scattering. After the matched
normal/LOD probes, the intended authored-material settings were reapplied and
saved explicitly to this trajectory's existing `fabric-options/iris.properties`,
with the prior file backed up locally. The final intended material profile is
`SSS_TYPE=3;EMISSIVE_TYPE=1`, fingerprint
`d6d0fcb6944cf52eb9bb56d81b32a6ae74c40ef97106a853e059baaa682af857`.
This is separate from the normal and LOD code fixes. The acceptance scenario
itself still applies options only to its active client.

The creative server remains the same owned server process. Client generation
4 contains both code fixes. The diagnostic pack, pass cutoff, time pin, and
background-throttle override are cleared. The intentional new state is the
corrected code and saved authored-material profile; DH continues hydrating
its frontier from the server.

The last authored-profile movement sequence aborted before its first capture
when the user moved independently. Its cleanup restored time/throttle and
preserved the new user pose instead of teleporting back. The successful
combined-code matched captures precede that intentional material-option change.

The final read-only diagnostic bundle is `final-corrected/` under that local
artifact root. It reports no active fixtures and the intended material
fingerprint. Its framebuffer contains the user's subsequently opened pause
menu, which was preserved; it is a handoff-state record, not a visual baseline.
Final `status --json` confirmed both owned roles joined/render-ready.
