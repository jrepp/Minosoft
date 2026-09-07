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

# Small Survival visual sweep after normal and LOD request fixes

## Result

Subsequent fixes and corrected interpretation of the DH capacity limit are in
[the shader/entity follow-up](2026-09-07-small-survival-render-fixes.md). The
observations below describe the earlier generation.

The scene is not visually clean. The review reproduces pale entity rendering,
bright tree-edge outlines, strong green/black terrain shading, and the native
renderer's black sky. It also separates a genuinely floating world tree from a
culling failure. The original motion-rate-dependent banding remains open: this
review used settled screenshots after small teleports, not a continuous walking
frame sequence, so it does not certify temporal stability or its root cause.
No renderer code was changed for this inspection.

## Reproduction boundary

- Trajectory `small-survival`, content stack `standalone`, no acceptance fixtures.
- Parent 34302; server `server-34407-1-a3961664`; client generation 4,
  `client-29161-4-5325b270` (PID 29161).
- Creative player at `(1.2384955, 77, -26.3799367)`, yaw `67.00009`,
  pitch `7.7000103`, overworld. Camera sample: all 36 cells in
  `(0,77,-28)..(2,80,-26)` loaded air, skylight 15.
- Bliss v2.1.0, authored options including `SSS_TYPE=3`, `EMISSIVE_TYPE=1`;
  fingerprint `d6d0fcb6944cf52eb9bb56d81b32a6ae74c40ef97106a853e059baaa682af857`.
- Cleared GUI, captured current weather, sampled camera blocks and substrate,
  then pinned client presentation to time 4090 and clear weather. Server time
  and weather continued normally. Background throttle disabled only for probes.
- Survey: stationary repeat, forward 0.25/1 block, backward 1 block, sideways
  1 block, yaw ±5/±30/±90/180 degrees, pitch 0/45 degrees, and returned view.
  Followed with reversible DH-off, Iris-off, and entity/particle suppression.
- 1800×1000 framebuffer; world viewport 1350×750. Frames are visual evidence,
  not a performance workload; displayed FPS is not a benchmark.

## Findings and limits

| Finding | Evidence and interpretation |
| --- | --- |
| Bright outlines around tree silhouettes | Visible against sky and distant terrain, including small-yaw views; persist with DH disabled. The native A/B lacks the same pale contour. This narrows the symptom to the Bliss presentation path, but does not identify a particular shader stage or distinguish spatial filtering from temporal history. |
| Uneven terrain shading and striping | The central stepped hill and rear slope retain strong green/black shading and thin bright ledges. Nearby effects survive DH-off and entity/particle suppression. Native rendering substantially changes the tint/contrast. Some horizontal lines are real block terraces; these stills alone cannot label every line a motion artifact or prove excess emission. |
| Pale, nearly textureless nearby mobs | Left view shows two pale humanoid figures with Bliss. With Iris disabled they have visible Alex-like fallback textures. Nearby entity inventory identifies trader llamas 3061/3062 using `FallbackLivingEntityRenderer`, 504 base vertices, zero reported content emissive passes. The fallback body and shader appearance both need follow-up; do not describe this as proven emissive materials. |
| Floating spruce is world content | Same isolated tree floats in Bliss, DH-off and native captures. Client and server samples both contain spruce logs at `(12,79..90,3)`, air at y76..78, and coarse dirt at y75. This specific gap is authoritative world state, not view culling. Samples were debug reads of loaded blocks, not live Anvil reads. |
| DH is visible but coverage is incomplete | DH-off removes the far landscape, proving that the enabled path contributes visible geometry. Reported effective distance was 21 chunks versus configured 128, with 1026–1036 draw commands at the survey boundaries. These are boundary observations, not matched performance deltas. Ongoing source/build/upload work prevents treating a changing far outline as proven frustum-culling failure. No new angle-dependent native section disappearance was established by this sweep. |
| Native sky remains black | Reproduced in both forward and left/rear Iris-off captures; far objects also show conspicuous blue/green fog coloration. This is a separate fallback-renderer issue. |

## Artifacts

All links below are local diagnostic artifacts; raw lifecycle logs remain outside
version control. Scripts and state/capture metadata accompany the PNGs.

- [Survey baseline](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review/baseline.png),
  [forward one block](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review/forward-one.png),
  [horizon pitch](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review/horizon.png).
- [DH disabled](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review/no-dh.png),
  [native forward](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review/native.png).
- [Left, Bliss / floating tree and entities](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/left-bliss.png),
  [left, native](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/left-native.png),
  [client blocks](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/left-blocks-client.json),
  [server blocks](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/left-blocks-server.json).
- [Rear, Bliss](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/rear-bliss.png),
  [rear, native](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/rear-native.png),
  [rear, entities/particles suppressed](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-targeted/rear-no-effects.png).
- [Handoff inventory](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-handoff/scene.json)
  and [handoff frame](../../../.run/diagnostics/small-survival-banding-2026-09-07/visual-review-handoff/frame.png).

## Restoration and next validation

Both scripts completed with empty restoration-error lists. Restored the exact
starting pose, Iris enabled with the original fingerprint, DH override null,
background throttle default, entities/particles visible, and removed client
presentation time/weather overrides. Creative mode is intentional. The ordinary
world clock/weather continued; the initially open pause menu was cleared for the
inspection. Handoff diagnostics completed without warnings, no fixtures were
active, and both client and server remained ready.

Prioritize a continuous, repeatable short forward/backward frame sequence with
matched presentation and hydrated LOD state for the original banding report.
Inspect the entity material/texture path separately. Do not change culling to
repair the verified floating tree or infer full 128-chunk residency from the
configured DH distance alone.
