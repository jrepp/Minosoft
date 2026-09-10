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

# Small survival content remediation — 2026-09-07

## Verified changes

The [initial visual probe](2026-09-07-small-survival-visual-probe.md) is followed
by source fixes in Minosoft, content-forge, and Blockbench. Generated PNG/JSON
files and immutable modpack archives were not hand-edited.

- `TextureUtil` resolves ImageIO palette indices through the color model and
  normalizes component depth. Previously a valid four-bit indexed spruce-leaf
  texture became near-black palette-index intensities. Gray PNGs retain their
  encoded intensity instead of undergoing ImageIO's `getRGB` sRGB conversion.
  Regression coverage forces fallback with interlaced indexed/transparent,
  eight-bit gray, and sixteen-bit gray PNGs.
- `content-forge/producers/model/semantics.mjs` removes zero-area exported edge
  faces and adds culling to unrotated boundary faces. Both general model
  emitters use it. The explicit survival producer supplies log axes, snowy
  ground, fern halves, crossed plants, and small mushroom geometry. Its
  authored precolored leaves omit a second biome tint.
- `blockbench/js/automation/texture_gen.js` supplies fern fronds/half selection
  and removes baked bevel borders from natural stone, including calcite and
  smooth basalt. Existing local improvements to bark, needles, soil, snow,
  and water feed the producer too; their presence is a cross-repository
  dependency, not a claim that all sibling working-tree art is committed.
- `content-forge/scripts/survival-pack.mjs` atomically publishes an explicit
  overlay: 24 blockstates, 26 models, 31 diffuse textures and their 62 PBR
  companions, plus pack metadata and hashed provenance. The full 966-block
  approximation catalog stays below the authored resource packs.
- `content-stacks/standalone.json` mounts optional `survival-authored` after
  bundled packs and before user archives. `MINOSOFT_SURVIVAL_CONTENT` overrides
  the default `../content-forge/out-survival`. This prevents a higher-priority
  diffuse texture from silently replacing the newly authored material.

## Scattering diagnosis and shader profile

Matched live captures at the original treetop view showed yellow mushroom caps
with Bliss's default `SSS_TYPE=1`, including with `EMISSIVE_TYPE=0`. Setting
`SSS_TYPE=0` removed the yellow caps and bright green foliage patches while
ordinary shadows remained. Source inspection agrees: Bliss maps mushrooms to
block ID 81, weak scattering with amount 0.75; leaves receive amount 1.
`SSS_TYPE=1` ignores authored transmission maps. This isolates the observed
apparent emission as block-ID subsurface scattering.

The corrected profile uses `SSS_TYPE=3` to read authored specular blue values:
0 for opaque materials and 96 for spruce foliage. Specular alpha 255 is the
non-emission sentinel. `EMISSIVE_TYPE=1` retains ordinary luminous block IDs.
The checked `acceptance/scenarios/small-survival-authored-materials.json`
applies these settings to the active client, reloads shaders and resources, drains
terrain work, and verifies main/shadow publication without missing pages,
retired residency, build failures, or failed submissions. It does not certify
visual quality or record a user-approved screenshot baseline.

Shadow maps remain runtime shader output. The content fixes address invalid
geometry and material semantics; there is no authored replacement shadow-map
image or speculative renderer-depth patch.

## Reproduction and checks

Use Java 25 for repository commands. Generate from the sibling checkout, then
relaunch the client to consume a newly composed immutable view:

```sh
# In ../content-forge:
node scripts/survival-pack.mjs
node --test tests/survival.test.mjs

# In Minosoft (record/restore live invariants before stopping an existing session):
MINOSOFT_SERVER_MODPACK=distant-horizons-bliss ./play.sh start both \
  --trajectory small-survival --content-stack standalone
./play.sh scenario run acceptance/scenarios/small-survival-authored-materials.json \
  --trajectory small-survival --artifacts <new-artifact-directory> --json
```

Passed checks:

- Root unit suite: 2,243 tests, 4 existing skips, no failures; render contracts:
  203 tests, no failures. Focused decoder and existing `TextureReadingTest`
  integration checks passed.
- `:play-util:test` passed after mounting the overlay; the final focused
  `ContentStackManifestTest` passed all 6 priority/manifest checks.
- `ContentForgeBlockLoadIT`, with `MINOSOFT_CONTENT_FORGE_ROOT` pointing at
  `../content-forge/out` and `MINOSOFT_SURVIVAL_CONTENT` pointing at
  `../content-forge/out-survival`, loaded/baked all 966 catalog blockstates and
  all 24 overlay blockstates, including their registered property variants.
- Two Blockbench tests cover authored fern-half cutouts and water transmission.
- Five content-forge Node tests cover culling, degenerate faces, state/material resolution,
  deterministic cutouts, and actual repeated pack publication with verified
  hashes and decoded non-emissive/transmission companions.
- Live material scenario passed all 7 steps, including shader/resource reload.
  All 143 generated asset hashes matched their composed winning files.

## Live evidence and limits

Final endpoints were `client-90585-1-6d105e3e` and
`server-90280-1-228aa854`, both generation 1; client session
`b1204d02-602f-d571-1232-f3e2f41d1d5d`. World/pack remain
`small-survival-2026-08-10` / `distant-horizons-bliss`, Minecraft 1.20.4.
The composed fingerprint is
`80bc74ac87fe99099f3da84dbc271d653d73c898b30d343f34064b38d45b8db3`;
the final Bliss fingerprint is
`d6d0fcb6944cf52eb9bb56d81b32a6ae74c40ef97106a853e059baaa682af857`.

Bundles under `.run/diagnostics/small-survival-fix-2026-09-07/` are local,
untracked diagnostic artifacts. All final captures completed with zero
warnings and no active fixtures. Their 1800×1000 framebuffer and scene pairs
share pose `(12.5,92,3.5)`, yaw -180, pitch 35, camera Y 93.53, presentation
time 6000 and clear weather. They show visible green spruce foliage, brown
mushrooms, and natural ground without the earlier baked tile borders. The
Bliss/native/restored-Bliss comparison preserves those content corrections.

| Capture | Frame | Original PNG SHA-256 |
| --- | ---: | --- |
| `bliss-no-emission` | 13665 | `e9c4654770ba6734454fd859964f27181f76957fa07329c794f39850d8e577c0` |
| `bliss-no-subsurface` | 13869 | `ad09a61faa442ce98d0136ceab86ce335077b7c84ffef673bb1f346330d2cc49` |
| `final-bliss` | 4969 | `13525643da8a65c6b82a62efd2731c6d7d9c4f84eea48589986c8cb2508c937f` |
| `final-native` | 8705 | `0b2ff1d09b5c7e3265a4e2ed97a611dffc559a90579d5d012291d47dbae74d0e` |
| `final-bliss-restored` | 24406 | `98645f595e9478d4329c28e6c4f20981589edaf09f494be3db90a0dbb729dc66` |

These are diagnostic captures, not pixel-equivalent temporal baselines or
performance evidence. Moving entities, wind, and simulation continue. The
reported shadow z-fighting is not independently proven eliminated by these
static views; the model defects and main/shadow reload contract are covered.
The native renderer also retains a black-sky symptom outside this content fix.
Further visual acceptance must distinguish those renderer symptoms from the
now-corrected material input. No user validation is implied.

At handoff both server and client are running and ready. The saved pose remains
unchanged and the player is alive. HUD, authoritative time/weather, and ordinary
entity/particle/cloud/arm presentation are restored. Iris and DH are enabled;
no canary/fixture or transient reference override remains. The intentional new
state is the authored content fingerprint and active Bliss material profile.
The subsequent [render-input/LOD investigation](2026-09-07-small-survival-render-inputs-and-lod-coverage.md) found that the debug configuration had not been saved across restart and explicitly saved the trajectory profile.
