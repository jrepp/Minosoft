<!-- Copyright (C) 2026 Jacob Repp -->

# Offline integration asset boundary

## Decision

`RenderTestLoader` and `CreditsAssetsIT` must not require a locally imported
Mojang asset index or client JAR. `OfflineTestAssets` selects
`LocalMinecraftAssets.NONE` for its loader instance and mounts a checked-in
Minosoft-authored resource-pack stand-in through `AssetsLoader`'s explicit
caller-priority seam. It does not mutate the resources profile. The default
selection still maps the profile's independent index/JAR controls, retaining
the local Mojang compatibility lane for the ordinary client.

The exact fixture covers the font and item/block-model contracts asserted by
the renderer test; a test-only fallback supplies inert generated item models
and Minosoft's bundled white texture for unrelated renderer-bootstrap lookups.
The fallback supplies a generated 256x256 white matrix for the two colormaps
and cloud coverage because those consumers require exact dimensions. This is a
deterministic bootstrap, not completeness evidence. The target is a complete
Minosoft-authored stand-in that no longer reaches the generic fallback during
normal renderer initialization.

The ordinary client passes no caller-priority assets, so its asset order,
profile-controlled local sources, and local-only provenance contract are
unchanged. The integration-suite bootstrap
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

`npm run publish` regenerated the ignored `out/provenance.json` from the
current output and declared 5,609 targets. The publication-integrity gate then
passed: every declared target exists and matches its SHA-256, and the existing
966 blockstates, 2,171 models, and 2,472 textures pass the JSON/PNG and
Blockbench-producer checks. The content-forge worktree's unrelated in-progress
authoring and producer changes were not modified.

The focused external publication gate, all three renderer-loader checks, and
the credits asset check passed on Java 25.0.4 without the Mojang index or
client JAR.

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
