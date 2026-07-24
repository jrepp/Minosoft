<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric catalog and runtime-diagnostics evidence — 2026-07-22

## Scope

- Minecraft: 1.20.4
- Fabric Loader metadata baseline: 0.15.11
- Trajectory: `catalog-2026-07-22`
- Live pack: `fabric-stack`
- Supervisor session: `2026-07-22T23:48:28.229181Z-17812`

This pass separates two questions that were previously mixed together:

1. **Catalog:** which upstream mod features have a Minosoft mapping?
2. **Diagnostics:** which adapters and owned host hooks actually loaded and ran
   in the current client generation?

## Catalog results

`FabricCompatibilityAdapter.functionality` is the source of truth used by both
`FabricPackPreflightCli` and Pause → Mod settings. Status means:

- `mapped`: an explicit source-native Minosoft implementation exists;
- `partial`: a related host facility exists but semantics or UI are incomplete;
- `unmapped`: no supported host implementation is claimed.

| Mod | Mapped | Partial | Unmapped |
| --- | ---: | ---: | ---: |
| Sodium 0.5.8 | 3 | 9 | 17 |
| Entity Culling 1.10.5 | 1 | 1 | 7 |
| ImmediatelyFast 1.5.5 | 1 | 2 | 7 |
| Fabric API 0.97.3 | 0 | 1 | 5 |
| Reborn Core 5.10.4 | 2 | 0 | 3 |
| Tech Reborn 5.10.4 | 3 | 1 | 8 |

The catalog is deliberately adapter/version specific. It does not infer support
from a mod ID, mixin name, or the presence of an upstream configuration screen.

## Dependency correction

The first real `fabric-stack` inspection reported Entity Culling blocked on its
declared Fabric API dependency. The pack now includes the already pinned Fabric
API 0.97.3 artifact and its exact adapter. A repeated inspection reported four
mods, `activation=adapted`, and no blockers or dependency issues.

## Runtime diagnostics

`FabricModDiagnostics` records process-local evidence for each adapted mod:

- ready/activating/active/failed/inactive lifecycle;
- adapter activation time and failure text;
- installed/uninstalled host-hook state;
- hook invocation count plus total, average, and maximum elapsed time;
- launcher trajectory and process-generation number;
- pack preflight time and active-pack uptime.

The in-game diagnostics screen adds current global FPS as context. It does not
attribute whole-frame time to a mod: only work crossing an owned adapter hook is
timed. Mods with only catalog or registration behavior therefore remain active
with no invented render cost.

## Live acceptance

Initial launch kept parent PID `17812` and server PID `17816`, started client PID
`17973`, activated Fabric API, ImmediatelyFast, Sodium, and Entity Culling, and
logged installation plus invocation for all three performance hooks. Pause → Mod
settings rendered all four catalog summaries and the Runtime diagnostics entry.

A base source edit produced a successful candidate and replaced client `17973`
with `20230` while the parent and server stayed fixed. The replacement command
contained:

```text
--mod-trajectory=catalog-2026-07-22
--hot-reload-generation=2
```

The replacement process again logged pack activation and invocation of Sodium
chunk scheduling, Entity Culling visibility, and ImmediatelyFast frame batching.
A subsequent UI-label candidate advanced to client PID `20577` without replacing
the parent or server.

## Automated acceptance

The focused suite passed:

```sh
./gradlew test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricModDiagnosticsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest \
  --tests de.bixilon.minosoft.dev.PlayUtilityTest
```

It covers unique catalogs, capability-to-function mappings, valid menu targets,
diagnostic lifecycle/timing snapshots, adapter selection/ownership, and launcher
contracts.

## Remaining boundary

Timing an adapter hook is observability, not proof of upstream behavioral or
performance parity. Future work should add rolling windows, exportable snapshots,
baseline comparisons, error counters, and behavior-specific acceptance scenes.
Binary Fabric API, general mixin/access-widener execution, and arbitrary upstream
configuration screens remain outside the current source-native adapter boundary.
