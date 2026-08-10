<!-- Copyright (C) 2026 Jacob Repp -->

# Offline integration asset boundary

## Decision

`RenderTestLoader` and `CreditsAssetsIT` must not require a locally imported
Mojang asset index or client JAR. `OfflineTestAssets` disables both managers and
mounts a checked-in Minosoft-authored resource-pack stand-in through
`AssetsLoader`'s explicit caller-priority seam. The exact fixture covers the
font and item/block-model contracts asserted by the renderer test; a test-only
fallback supplies inert generated item models and Minosoft's bundled white
texture for unrelated renderer-bootstrap lookups. The fallback supplies a
generated 256x256 white matrix for the two colormaps and cloud coverage because
those consumers require exact dimensions.

The ordinary client passes no caller-priority assets, so its asset order and
local-only provenance contract are unchanged. The integration-suite bootstrap
also loads `MinosoftProperties` before boot tasks, matching the application
pre-boot invariant required by render-time debug elements.

## External authored-content lane

Set `MINOSOFT_CONTENT_FORGE_ROOT` to an absolute content-forge output directory
to add that directory below the exact stand-in fixtures and above the generic
fallback. The same explicit setting enables `ContentForgeAssetsIT`, which:

- bounds, normalizes, de-duplicates, and root-confines every provenance target;
- verifies every target exists and matches its declared SHA-256;
- parses every JSON and `.mcmeta` file under `assets/` as an object;
- opens every PNG header and bounds its dimensions and pixel count; and
- requires non-empty blockstate, model, texture, and Blockbench-producer lanes.

The environment variable is intentionally explicit. Out-of-source build output
does not silently change the result of the ordinary suite; without the setting,
the external test skips and the checked-in stand-in is deterministic.

## 2026-08-10 local result

The current `/Users/jrepp/d/content-forge/out` tree contains 966 blockstates,
2,171 models, and 2,472 textures. Its full JSON/PNG traversal passed, and the
current bytes completed Minosoft's dummy renderer bootstrap and both focused
model assertions without the Mojang index or client JAR.

The publication-integrity gate correctly remains red: all 3,871 declared
targets exist, but 681 hashes differ from `provenance.json`. The manifest is
dated 2026-08-08 while representative changed model output is dated 2026-08-10.
The content-forge worktree already contains unrelated in-progress authoring and
producer changes; it was inspected read-only. Republish that output and its
provenance together before treating the external gate as accepted.

The complete hermetic `integrationTest` suite passed on Java 25.0.4 with the
external gate skipped, including all three renderer-loader checks and the
credits asset check.

## Validation

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home

# Hermetic Minosoft-authored stand-in; external gate skips.
./gradlew integrationTest \
  --tests de.bixilon.minosoft.assets.ContentForgeAssetsIT \
  --tests de.bixilon.minosoft.gui.rendering.RenderTestLoader \
  --tests de.bixilon.minosoft.gui.rendering.gui.gui.screen.CreditsAssetsIT

# Explicit external publication and ingestion gates.
MINOSOFT_CONTENT_FORGE_ROOT=/absolute/path/to/content-forge/out \
  ./gradlew integrationTest \
  --tests de.bixilon.minosoft.assets.ContentForgeAssetsIT
MINOSOFT_CONTENT_FORGE_ROOT=/absolute/path/to/content-forge/out \
  ./gradlew integrationTest \
  --tests de.bixilon.minosoft.gui.rendering.RenderTestLoader
```
