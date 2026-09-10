<!-- Copyright (C) 2026 Jacob Repp; SPDX-License-Identifier: GPL-3.0-or-later -->
# Workspace item-preview evidence

Item previews invoked `content_preview:show_item` without mounting its data pack
in the standalone profile. A missing function returned `executed: 0`, which the
launcher accepted as successful placement. This could produce terrain-only
screenshots that appeared to be valid item evidence.

`Play.runContentPreview` now explicitly enables the preview data pack for item
captures. `configurePreviewDataPack` preserves user packs, mounts the fixture
once, and removes that exact mount for ordinary launches. The launcher rejects
zero-command placement. After terrain/frame settling, it executes the no-op
`content_preview:camera` function to restore the requested camera through the
existing local-function API without replacing the settled display entity.
`visual.capture` includes same-frame scene diagnostics.

Verified on macOS with Java 25 and the configured `distant-horizons-bliss` stack:

- `./gradlew :play-util:test --tests ContentPreviewTest` passed. Coverage includes
  fixture mounting/removal, missing fixtures, and zero-command placement refusal.
- Content-forge's real hosted-editor run captured iron and gold in separate
  isolated local previews. Both 1800×1000 PNGs were visually inspected and show
  their respective ingot. Both frames report the matching visible
  `ItemDisplayEntityRenderer`, item ID, and material texture.
- The capture runner checked that the composed stack selected every candidate
  output hash and observed no parent/client before or after the previews.

The inspected capture bundle is in the sibling content-forge checkout at
`.forge-workspace/validation/e2f336ed816a2ab0de88245a5cfbfe7215eeeda0ad8b78586b67efebc432d8ad/evidence.json`.
Iron PNG SHA-256:
`fd071332bfa7cf03b01c358ed24e5e660fd33b3d29032514f34bae764d42f874`.
Gold PNG SHA-256:
`d582db5cdbebf512c34e7ad285e5ee8cea204b30abbda40b899e50942be5cf8a`.
Launcher JAR SHA-256:
`c8c99bc72d27d90e65ddad69d945bbdf926850daf55eeb96d0ef25ed4648b8d0`.

Reproduce from content-forge with
`FORGE_CONSUMER_CHECK=1 npm run workspace:editor-check`, or use
`./play.sh content preview assets/minecraft/models/item/iron_ingot.json --manifest standalone --output /tmp/iron-preview.png --settle 30000 --strict --json`
with an isolated `MINOSOFT_TRAJECTORY` and no existing client. These are workflow
and fixture checks; they do not grant artistic approval to the ingots.
