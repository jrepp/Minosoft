<!-- Copyright (C) 2026 Jacob Repp -->

# Minosoft credits behavior evidence

Date: 2026-07-23

## Scope

The win-game GUI previously displayed hard-coded placeholder text. It now
constructs a scrolling credit sequence from original project content at
`minosoft:texts/credits.json` and same-key contributions supplied by local
resource packs or installed mods.

The earlier implementation briefly read Mojang-authored `minecraft:texts/*`
content. That behavior was removed on 2026-07-24 and is not part of the accepted
contract.

## Corrected contracts

- Structured credit sections preserve section, title, and name ordering.
- Multiple local/mod providers append in asset-manager priority order.
- The fallback copy is original Minosoft text.
- Only lines intersecting the viewport are emitted into the changing GUI mesh;
  the complete credit roll is not rebuilt on every tick.
- The base speed is 30 scaled pixels per second at the GUI's 20 Hz update rate.
  Space and Control expose the fast-forward tiers.
- Closing or completing the screen sends the existing version-aware
  `PERFORM_RESPAWN` client action.
- Missing local resources fail visibly with a minimal Minosoft message instead
  of the former implementation apology.

## Validation

`CreditsContentTest` and `CreditsScrollStateTest` cover the project namespace,
structured ordering, multiple-provider ordering, original fallback copy, base
cadence, both acceleration tiers, and completion padding. `CreditsAssetsIT`
loads the actual session asset stack, verifies that the selected provider is not
a `MinecraftAssetsManager`, and checks project-authored credit content.

The focused Java 17 run passed all seven unit tests and the actual-asset
integration check. The final `test integrationTest assemble` run reported 3,559
tests with zero failures or errors.

A supervised `minecraft-parity-1-20-4` launch reached server-ready,
client-joined, and client-render-ready with parent PID `27697`, server PID
`27720`, and client PID `27899`. A 3456×1924 framebuffer was captured after
render readiness, the seven-mod client compatibility stack reported active
hooks, and shutdown returned every lifecycle predicate and PID to the stopped
state. A live win-event framebuffer sequence remains the presentation gate
because the ordinary test server does not expose a safe command that emits the
win-game packet.

## Parity boundary

This closes one explicit presentation placeholder without incorporating
copyrighted Minecraft content. It does not establish global behavioral parity.
The remaining surfaces and acceptance gates are tracked in [the 1.20.4 parity
backlog](../backlog/minecraft-1.20.4-parity.md).
