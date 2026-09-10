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

# Small survival visual probe — 2026-09-07

## Scope and result

Observed on macOS with Java 25, Minosoft `3de024096`, Minecraft 1.20.4,
trajectory `small-survival`, world `small-survival-2026-08-10`, pack
`distant-horizons-bliss`, and the composed `standalone` content stack.
The probe verifies framebuffer-plus-scene capture and reproduces visual defects;
it does not accept the content or establish a renderer fix.

The server endpoint was `server-71517-1-550a8421`, the client endpoint was
`client-71956-1-6529ac64`, and the client session was
`ef5969c2-d1d0-f300-c907-71fc9a9fe173`. Both endpoint generations were 1.
The composed-content fingerprint was
`fc51d027ca862c709e0651b01c2e1586c7ea4d0f6b521c8ee049c55dd8c0b6ea`.
Bliss 2.1.0 reported shader fingerprint
`22451634ceb90a31546ec4e1591be3274f072de50bdffdd287213a31803cc2d7`.

## Matched capture protocol

The saved player was dead. Normal respawn at night exposed the player to zombies;
initial ground captures were invalid for comparison because attacks moved the
camera. After respawning, `world.teleport-player` placed the player on an existing
spruce treetop at `(12.5, 92, 3.5)`, yaw 180, pitch 35, in the overworld.
A loaded client block sample from `(11,91,2)` through `(13,94,4)` contained one
supporting spruce-leaf block and otherwise air, with no unloaded cells and sky
light 15 around the camera. The player was neither embedded nor submerged.

All six compared screenshots were 1800×1000, with the same recorded camera
`(12.5, 93.52999997138977, 3.5)`, yaw 180, pitch 35, presentation time 6000,
and clear presentation weather. HUD was hidden; clouds and arm remained visible.
The first five retained entity and particle presentation; the last suppressed
both. Each diagnostic bundle completed with zero warnings. Iris and DH changes
used their debug presentation operations. This is visual diagnostic evidence,
not a performance measurement or a temporally frozen simulation.

Local one-off bundles remain under
`.run/diagnostics/small-survival-probe-2026-09-07/` and are intentionally untracked.
Each includes `frame.png`, same-frame `scene.json`, capture metadata, shader and
terrain diagnostics, client/server state, fixtures, and a hashed manifest.
The following hashes identify the original, unedited framebuffers:

| Capture | Frame | PNG SHA-256 |
| --- | ---: | --- |
| `day-bliss-dh` | 2483 | `a8f4af1bedb7edbcb629d3fa5b1b90590720027c4887d646c20527c321935d7d` |
| `day-native-dh` | 2709 | `db6234a64a71f867283a31ae0bb4e54954635353e301568a2a30a6bb9c09fa48` |
| `day-bliss-no-dh` | 2738 | `ddabdf6ca9764b350f89466e13e11efb502bc093bf0e7dc0e34479342cf1b1d7` |
| `day-bliss-no-dh-repeat` | 2743 | `c1cfdfcfbd4ab692a61f1bafdf37908427b5b27e80609169078dcdcad9938c1e` |
| `day-bliss-dh-restored` | 3518 | `e8a77e7af32c8270e7a8b1f3b452c361dca9a6a06c398306f02b7beec4bea2d0` |
| `day-bliss-terrain-only` | 3676 | `f3c93285f2e9322be8eda899da179431c9f69cece671f68d058116153e7a8284` |

Reproduce a bundle against the currently resolved endpoints with:

```sh
MINOSOFT_MODPACK=distant-horizons-bliss ./play.sh diagnose capture \
  --trajectory small-survival --visual --output <new-probe-directory> --json
```

Use the environment variable for this command's pack discovery; `diagnose
capture` does not accept `--modpack`. Re-resolve endpoints and record current
state before preparing another reference or changing presentation.

## Findings and remaining uncertainty

- Spruce foliage renders nearly solid black with Bliss enabled and disabled,
  with DH enabled and disabled, and with entities/particles suppressed. The
  composed diffuse PNG contains visible grayscale leaf structure. These checks
  rule out a Bliss-only explanation for the black foliage; they do not isolate
  texture upload, tint/light, model, or sampling behavior.
- Mushrooms appear conspicuously yellow/bright after the Iris disable/enable
  cycle, including after DH is restored and entities/particles are suppressed.
  They were brown in the original Bliss capture and native capture. This is a
  useful generation-transition symptom; actual emissive metadata or the exact
  lighting cause has not been proven.
- Captured fixture lists are empty. Material-animation diagnostics report zero
  textures, channels, advances, uploads, resources, and states. Checked Bliss
  diagnostics have empty rejected/fallback scene-bind maps. These observations
  do not prove that every selected shader route renders correctly.
- The bounded ground-level scene inventory includes calcite, stone, gravel,
  coarse dirt, smooth basalt, blackstone, terracotta, and vegetation, with
  expected named texture candidates. It reports no plank state. This does not
  rule out a GPU texture-binding error or a defect outside the bounded sample.
  The reported wood-plank substitution and shadow z-fighting remain unresolved;
  the overview captures are not sufficient to label a depth or shadow-bias bug.

## Producer and composition boundary

The composed `provenance.json` orders sources from lowest to highest priority.
Hash comparison against the exact source files/archives establishes:

| Resource | Winning source | Producer consequence |
| --- | --- | --- |
| spruce_leaves, calcite, yellow_terracotta, blackstone textures | Faithful 32x | Current content-forge textures are overridden |
| coarse_dirt, brown_mushroom_model, large_brown_mushroom_model, grass_model textures | Vanilla Evolved | These visible inputs are not current content-forge textures |
| water_overlay texture | content-forge loose-content | Current producer output reaches the composed view |

Spruce leaves, calcite, yellow terracotta, and blackstone retain content-forge
cube models while using Faithful textures. That mixture is observed, not proof
of incompatibility. The detailed hash trace is retained locally as
`asset-trace.json`. No `_n`/`_s` companion was present beside the inspected
spruce-leaf, calcite, coarse-dirt, yellow-terracotta, or water-overlay texture.

Before authoring replacements in content-forge/Blockbench, select the exact
state/model/material from a close capture and prove the intended source wins
composition. Keep producer edits in recipes/model sources, regenerate, then
recapture the consumer with an updated fingerprint. Treat blockstate selection,
model UVs/coplanar faces, diffuse/normal/specular/emissive inputs, and renderer
shadow depth/bias as separate candidates. The shader's world shadow maps are
render targets generated at runtime, not replacement diffuse texture assets.

## Restoration and validation

Iris was restored enabled with its original fingerprint. DH was restored to
its configured enabled state with a null transient override. HUD was restored;
entity, particle, cloud, world-border, and arm suppression were false. Prepared
time and weather were cleared, and client presentation again followed the
server's advancing day and rain. No reference/canary override remained.

Intentional new gameplay state: the player was left alive in survival with
health 20 at the treetop pose above, rather than returned to the hostile respawn
point. Server and client remained running; final `status --json` reported server
ready, client joined, and client render ready. No renderer, producer, generated
asset, pack configuration, or world block was edited. Validation consisted of
live captures, state/loaded-block checks, reversible presentation checks, and
source hash comparisons; no JVM tests were needed for this documentation-only
result.
