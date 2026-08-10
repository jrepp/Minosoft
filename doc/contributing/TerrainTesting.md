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

# Terrain testing

Use a Java 25 JDK for every command. The checked-in `.envrc` selects Homebrew's
`openjdk@25` on macOS and sets `JAVA_HOME`, `MINOSOFT_JAVA_HOME`, and `PATH`.
On another platform, set `MINOSOFT_JDK25_ROOT` to the JDK root before allowing
the environment:

```sh
direnv allow
java -version
./gradlew localTerrainTest
```

The focused local gate is deterministic, headless, and does not require a
running client, server, GPU, or downloaded world.

The gate deliberately reuses focused tests from the ordinary unit,
integration, and `render-contracts` suites:

| Area | Contracts covered |
| --- | --- |
| Water | Fluid material-page UVs, Complementary water shader transformation, water partitioning in distant artifacts, and near-terrain semantic parity |
| Lighting | Day/night and player-light falloff, smooth terrain corner and inset light, detached generated-light snapshots, and far-generated-light integration |
| Far terrain | Page hierarchy, selection, meshing, near-coverage masking, draw selection, and region artifact generation |

Run `./gradlew test integrationTest :render-contracts:test` when a change crosses
the focused boundary or when preparing a broader handoff. The focused task is a
fast regression gate, not a replacement for the complete suites.

## Open-content local dojo

For a local render session without a Minecraft client asset cache, the content
composer builds one resource-pack-compatible tree from ordered local sources.
The checked-in [`standalone` manifest](../../content-stacks/standalone.json) is
the default. Its low-to-high priority order is VoxeLibre, managed mod assets,
optional local mod collections, managed resource packs, optional loose content,
general resource packs, and Faithful overlays.

The managed resource-pack layer includes exact, verified Faithful 32x June
2025, Vanilla Evolved 1.9.0, Open Assets Lib, and GUI Revision 2.1.0 archives.
Faithful supplies the 256x bitmap font and broad 32x asset coverage; Vanilla
Evolved supplies compatible vanilla-path refinements; Open Assets Lib remains
available in its own namespace for packs and mods that reference it; and GUI
Revision wins final priority for its container and GUI sprite textures,
including authored nine-slice metadata. The generated font provider uses the
vanilla 16-row atlas geometry so Faithful can replace only `ascii.png` as
intended. Valid PNGs that
the fast decoder cannot consume, including Faithful's grayscale textures, are
retried from bounded in-memory bytes through ImageIO. The launcher verifies
both SHA-512 digests and retains the
independently licensed archives only in the out-of-source artifact store. The
composer copies required root-level license and notice files unmodified into a
source-scoped `third-party-notices/` tree. Minosoft currently consumes compatible
static textures; OptiFine conditional-container rules and Mojang shader programs
remain outside this content-composer boundary.

Compose it independently before launch with:

```sh
./play.sh content compose --json
```

The result is immutable and hash-addressed below the configured out-of-source
modpack store. `provenance.json` records the expanded source order and paths.
Directory files are copied so later edits to an input cannot mutate a published
fingerprint; archive and mod assets are bounded and extracted with traversal
protection. No third-party media is copied into this repository.

VoxeLibre is one source adapter in this general stack. It reads the installed
game's maintained `tools/Conversion_Table.csv`, maps safe whole-file textures
and any non-blacklisted atlas slices into a standard `assets/minecraft/`
layout, supplies the generated fixed-width ASCII
font and modern HUD/button sprites needed without jar/index assets, supplies the
small model and animation support files needed by the bounded terrain dojo, and
copies the source `LEGAL.md` into the generated view while recording its original
path. Its fingerprint covers the emitted bytes, including platform-rendered
fallback support, rather than only the input paths.

The checked-in `.envrc` exports `MINOSOFT_VOXELIBRE_ROOT` when `../VoxeLibre`
exists. Launch the isolated debug-world dojo with:

```sh
./play.sh dev client \
  --local-world \
  --world-generator debug \
  --trajectory terrain-local-dojo \
  --content-stack standalone
```

The manifest selects `distant-horizons-bliss` as its managed modpack and marks
the stack standalone, so the unavailable Minecraft jar/index layers are
disabled only in that trajectory's generated resources profile. Removing
`--content-stack` on a later launch restores those layers.

The debug catalog has a continuous stone floor at Y=7 beneath its spaced block
states. Generation includes one negative-chunk spawn apron and stops after the
catalog margin instead of creating an unbounded flat world. When validating a
content-stack change, sample Y=7 around the player before diagnosing an apparent
terrain hole. For first-person skin failures, use the reversible
`render.prepare-reference-hand` control: an intact checker arm distinguishes
asset selection from the Iris hand/PBR route, and the control must be restored
off before handoff.

The managed stack pins one exact Faithful 32x release without storing its binary
in the repository. Add any locally installed, version-compatible Faithful ZIP
or resource-pack directory as a still-higher-priority override; repeat the
option for multiple packs:

```sh
./play.sh dev client \
  --local-world --world-generator debug \
  --trajectory terrain-local-dojo \
  --content-stack standalone \
  --faithful-pack /absolute/path/to/Faithful.zip
```

`MINOSOFT_FAITHFUL_PACKS` is the manifest's optional, platform-separated
highest-priority source. `MINOSOFT_CONTENT_DIRECTORIES`,
`MINOSOFT_RESOURCE_PACKS`, and `MINOSOFT_CONTENT_MODS` add loose trees, other
resource packs, and mod collections at their declared manifest positions.
Command-line `--content-source TYPE=PATH`, `--content-mods PATH`, and
`--faithful-pack PATH` inputs are appended as higher-priority launch-local
overlays. The launcher validates every source and automatically replaces its
previous managed profile entry when the stack changes.
Rows explicitly blacklisted by VoxeLibre are never adapted; this includes all
sliced rows in the currently inspected checkout. The adapter is a local
compatibility/testing layer, not a claim that Minecraft
and VoxeLibre have identical content semantics or that every texture conversion
is exact.

Create the deterministic content-adaptation backlog from a fully loaded local
client with:

```sh
./play.sh content audit --trajectory terrain-local-dojo --json
```

The command atomically writes `.run/content-audits/terrain-local-dojo.json` by
default; use `--output FILE.json` for another out-of-source destination. The
report is sorted and contains no timestamps, frame counters, process IDs, or
machine paths. Each missing entry includes its canonical resource identifier,
the exact `assets/<namespace>/...` target path expected from a loose source or
resource pack, and the logical registry/model consumers that requested it.
Run the command twice without reloading: both the reported fingerprint and the
file hash must match. Add or adapt the highest-value targets through one of the
manifest sources, restart the generation, and compare counts and fingerprints;
newly reachable models may reveal their dependent textures on the next audit.

For a cumulative staged refinement, name each audit and relaunch through the
corresponding manifest boundary:

```sh
./play.sh content audit --trajectory terrain-local-dojo --stage 00-baseline --json
./play.sh content compose --stage faithful-32x --json
./play.sh content compose --stage vanilla-evolved --json
./play.sh content compose --stage open-assets-lib --json
./play.sh content compose --stage gui-revision --json
```

Stage files remain local under
`.run/content-audits/terrain-local-dojo-stages/`. On the next composition the
generated compatibility source consumes every JSON file there in lexical order,
so earlier missing targets are retained even when a later layer makes them no
longer observable. Its original water overlay, repeating entity glint, shadow,
and vignette are deterministic raster outputs; no generated binary is checked
into the repository. A transparent 8x8 `minecraft:etf_nose` placeholder keeps
the skeletal model's compatibility lookup quiet, while the player renderer
supplies the runtime-derived ETF material whenever a skin actually requests it.
Every other audited missing texture target is rasterized by the deterministic
`GeneratedTextureLibrary` keyed by its resource path, so repeated composition
converges the cumulative texture inventory toward zero missing entries.

Triage the cumulative backlog into separate work lanes with the content
submission queue:

```sh
./play.sh content queue --json
```

The command writes a deterministic queue to
`.run/content-queues/standalone.json` by default. Every cumulative audited
target is classified into one of two actionable lanes: `generate` (a distinct
raster family or non-trivial model shape exists, so deterministic generation is
the right answer) or `select` (only a generic placeholder exists, so the real
asset should be chosen from another package — Faithful, Vanilla Evolved,
VoxeLibre, a mod, or a user overlay). A target whose authored asset already wins
in the composed stack is marked `resolved` and drained from the actionable
queue. Each entry records its resource, target path, disposition, detail (raster
family or model shape), consumers, and the manifest's non-generated selection
sources. For every `select` target the queue probes those concrete packages and
records a `candidates` list: an `exact` match when a source carries the target
path itself, or a `near` match when it carries the same basename under a sibling
directory (for example a block-item model served by its `models/block/` parent,
or an item texture served by its `textures/block/` counterpart). The summary
splits `select` into `selectWithCandidates` and `selectUnavailable` so the
adoptable backlog is visible at a glance. Run the command twice: both the queue
fingerprint and the file hash must match. The composer keeps source providers
independent when a higher-priority archive overrides the same path, so a select
target's authored override never leaks into the generated base.

Emit a deterministic, documented authoring backlog with `--top K`:

```sh
./play.sh content queue --top 10 --authoring --json
```

`--top K` ranks the actionable queue by a documented scoring rule: adoption-ready
`select-with-candidate` targets score highest (400), then select textures without
candidates (300), select models without candidates (200), and `generate` targets
(100); ties break on reachable consumer count descending (a texture sums the
consumers of its sibling blockstate and models) and then target lexicographic.
`--authoring` restricts the backlog to targets that need new authored input —
adoption-only `select-with-candidate` entries drop out — and adds an authoring
leverage tie-break that prefers targets whose whole block+item family is still
actionable, so one authored asset resolves more queue entries. The emitted
`ranking` object carries the rule text, per-rank score, tier, kind, target,
`consumerCount`, candidates, and consumers; identical inputs always produce the
identical top-k.

Share the full triage with a production team as a CSV:

```sh
./play.sh content queue --csv
```

The command writes `.run/content-queues/standalone.csv` by default (or
`--csv-output FILE.csv`). Every queue entry becomes one row with columns
`kind, resource, target, disposition, tier, priority, detail, consumerCount,
consumers, candidateSources, candidateTargets`; rows are ordered by priority
tier so the team can filter on `tier` (adopt now / author texture / author model /
generate / done) or sort by `priority`. The file is UTF-8, RFC-4180-quoted, and
deterministic for identical inputs.

## Managed render checks

Use the live tier when changing GPU upload, shaders, world sampling, presentation
state, or the actual near/far composition. Resolve and record the exact runtime
state first as required by the live-runtime contract.

For water and emissive/light behavior in a bounded reversible fixture:

```sh
./play.sh status --json
./play.sh scenario run \
  acceptance/scenarios/terrain-light-fluid-materials.json \
  --trajectory <named-trajectory> --json
```

For far-terrain producer behavior without a framebuffer baseline:

```sh
./play.sh scenario run \
  acceptance/scenarios/terrain-distant-only.json \
  --trajectory <named-trajectory> --json
```

For deterministic meshing, packed region artifacts, and view-dependent distant
main/shadow publication, first run the headless gate, then use the exact local
debug-world identity recorded beside the cardinal baselines:

```sh
./gradlew localTerrainTest
./play.sh scenario run \
  acceptance/scenarios/terrain-distant-cardinal-views.json \
  --trajectory terrain-distant-view-baseline-2026-08-09 --json
```

The cardinal screenshot crops deliberately exclude the asynchronously hydrated
far-horizon silhouette. Every yaw still reaches a complete terrain boundary and
requires nonzero distant main/shadow draws with zero missing pages; the image
oracle freezes the stable ground render and camera transform.

For the platform-qualified near/far visual boundary, use only the exact
`diverse-medium-biomes-2026-08-01` world, pose, framebuffer, and presentation
identity recorded beside its baselines:

```sh
./play.sh scenario run \
  acceptance/scenarios/terrain-dh-near-mask-diverse-medium.json \
  --trajectory diverse-medium-biomes-2026-08-01 --json
```

Normal scenario runs never update baseline images. Use
`--update-screenshots` only for an intentional, reviewed recapture, then verify
the new image dimensions, hashes, capture README, and restoration state.
