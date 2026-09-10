<!--
 Minosoft
 Copyright (C) 2026 Jacob Repp

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.
-->

# Local block-lighting macOS references

These full-frame references cover the self-resetting
`content-place-blocks-lighting` scenario on the macOS Apple OpenGL trajectory
documented in
[`2026-08-08-asset-preview-continuation.md`](../../../doc/agents/evidence/2026-08-08-asset-preview-continuation.md).
They use a physical `1800x1000` framebuffer, fixed night time, clear weather,
hidden HUD/clouds/hitboxes, and the pinned Bliss content presentation.

| File | SHA-256 |
| --- | --- |
| `stone-no-torch.png` | `ed6138ba2167eaf3729990ff4fade075c19811eb9c71a9c3193e3da4f6b539d6` |
| `stone-with-torch.png` | `3b743c0deab876c4966ba197a2133e42c16cd6a21834a0406ebd9ce02334438d` |

The accepted run is
`.run/acceptance/content-place-blocks-lighting-capstone-clean`. It sampled
adjacent block light as `[0,0]` before placement and `[0,13]` afterward, while
the checked image changed from a dark stone fixture to four visible torches and
an illuminated center block.

Only update these images after reviewing the complete framebuffer and the
parallel structural light assertions. Captures containing HUD, debug chat,
OpenGL errors, or an off-frame fixture are invalid baselines.
