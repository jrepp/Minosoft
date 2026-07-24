<!-- Copyright (C) 2026 Jacob Repp -->

# Debug rendering menu — 2026-07-23 HST

## Scope

This change exposes Minosoft's existing runtime debug-rendering state through a
dedicated in-game menu at Pause → Debug options → Debug rendering.

## Stable behavior

- World wireframe changes the active world framebuffer between `FILL` and
  `LINE`; the menu does not introduce a second polygon-mode flag.
- Debug HUD changes the existing `DebugHUDElement.enabled` state used by the F3
  HUD.
- Button labels are refreshed from live renderer/HUD state whenever the page
  opens and after each action.
- Reset restores filled world rendering and disables the debug HUD.
- The legacy `F4+P` binding now toggles the framebuffer's current polygon mode,
  so menu changes and keyboard changes cannot become state-desynchronized.

These controls are session-local diagnostics and are intentionally not written
to a rendering profile.

## Validation

- `DebugRenderingControlsTest` covers fill-to-line, line-to-fill, point-mode
  normalization, and wireframe-state presentation.
- A supervised Minecraft 1.20.4 client hot reload preserved the play parent and
  Fabric server.
- Live debug-input acceptance navigated through Pause and Debug options to the
  new page. The initial page showed both controls Off; clicking World wireframe
  changed its label to On and the captured world to GL line rendering.
- A subsequent client generation returned the runtime-only mode to filled
  rendering. Raw lifecycle logs remain under `.run/` and are not durable
  evidence.
- The Java 17 `:test`, `:integrationTest`, and `assemble` gate passed after the
  live acceptance.
