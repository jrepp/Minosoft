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

# Small Survival banding — rejected shadow-coordinate candidate

## Rejection

The user reported that forward/backward terrain banding remained after the
camera-relative fullscreen shadow-matrix candidate and that emissive-looking
and other rendering artifacts were now visible. The candidate is rejected,
and its changes to `IrisFrameState`, `IrisLegacyShaderTransformer`, and the two
focused tests were reverted. Do not treat its coordinate-equivalence tests or
static screenshots as evidence that the reported visual defect was fixed.

The mathematical test established an equivalence between two constructed
coordinate systems. It did not establish the correct end-to-end shader
convention across every scene and fullscreen pass. That missing validation
made it insufficient justification for accepting the change.

## Retained observations

At the original creative-mode pose
`(13.906898308909321, 77, -15.844933657189346)`, yaw `76.00004`, pitch
`12.400003`, the centered hillside across the water retained the artifact
with Distant Horizons disabled and with entities/particles suppressed.
The camera was in loaded air and no content fixtures were active. Disabling
Bliss changed the affected lighting. These findings support further shader
stage isolation; they do not identify shadow projection as the root cause.

The earlier survival-mode comparison was invalidated by phantom damage and
player displacement. Creative mode and flight are intentional user-requested
conditions for subsequent reproduction.

## Diagnostic artifacts

Local, untracked comparison records are retained under
`.run/diagnostics/small-survival-banding-2026-09-07/{baseline,candidate}/`.
They use quarter-block offsets through one block forward and back, separate
pose/frame metadata, and stationary/DH-disabled/native-renderer comparisons.
The candidate used the same content and Bliss input fingerprints as the
baseline; pack fingerprints do not identify Minosoft transformer changes.
Both sequences restored their pose and temporary time/throttle state.

The rejected candidate's root unit suite passed 2,245 tests with 3 skips,
and the exact Bliss DH planner check passed. These are compiler/contract
results, not successful visual acceptance. The independently unchanged
render-contract suite had 203 passing tests.

Continue with a matched pose and shader-stage bisection, checking the
intermediate buffers before changing renderer transforms again. The native
black-sky symptom and previously diagnosed Bliss scattering/material profile
must remain separately identified.
