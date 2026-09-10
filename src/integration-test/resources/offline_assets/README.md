<!--
 Minosoft
 Copyright (C) 2026 Jacob Repp

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.
-->

# Offline integration asset stand-in

This Minosoft-authored resource pack supplies only the contracts exercised by
`RenderTestLoader`: an empty-but-valid default font, a generated ladder item, a
handheld wooden sword, and an element-backed oak-planks item. It deliberately
reuses the bundled `minosoft:white` texture and contains no Mojang asset
payloads. A test-only fallback supplies generic item models and the same white
texture for unrelated renderer-bootstrap lookups; it does not replace the exact
fixtures above.

The stand-in is always mounted during integration tests. Set
`MINOSOFT_CONTENT_FORGE_ROOT` to mount a content-forge output directory into
the renderer test as a lower-priority provider. The same explicit setting runs
`ContentForgeAssetsIT`, which independently checks every provenance hash, JSON
document, PNG header, and Blockbench producer entry. Without the setting, that
external gate is skipped and the checked-in stand-in remains hermetic.
