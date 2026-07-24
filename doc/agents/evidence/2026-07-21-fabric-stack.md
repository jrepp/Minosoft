<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric stack activation and reload evidence — 2026-07-21

## Scope

This run tested a generalized source-native Fabric adaptation path with three
independent client optimization artifacts. It proves metadata fingerprinting,
adapter selection, owned host-hook registration, live invocation, pack cleanup,
and process-generation reactivation. It does not prove Fabric/Minecraft binary
compatibility or full upstream behavior parity.

## Pinned inputs

| Mod | Artifact | SHA-512 | Upstream surface | Native capability |
| --- | --- | --- | --- | --- |
| Sodium | `sodium-fabric-0.5.8+mc1.20.4.jar` | `bd00b956…8d554` | client/preLaunch entrypoints, mixins, access widener, five nested JARs | `chunk-render-scheduling` |
| Entity Culling | `entityculling-fabric-1.10.5-mc1.20.4.jar` | `874198f9…6dbbf` | client/modmenu entrypoints, one mixin declaration, two nested libraries, Fabric API dependency | `entity-visibility` |
| ImmediatelyFast | `ImmediatelyFast-Fabric-1.5.5+1.20.4.jar` | `8733e4f1…482ffd` | two mixin declarations, access widener, one nested library | `frame-batching` |

The tracked Packwiz source is `modpacks/fabric-stack/`. The successful immutable
view was `/tmp/minosoft-sodium-live/packs/fabric-stack/7ad961214e44f3e282adec66c797dd74eaf5ab80d7a2ed14d14c6b854c1f5f7d`.
Downloaded JARs remained outside the repository.

Upstream license metadata was checked before pinning: Sodium uses PolyForm Shield
1.0.0, Entity Culling uses the tr7zw Protective License, and ImmediatelyFast is
LGPL-3.0-or-later. The pack redistributes none of these artifacts in source.

## Automated evidence

- `FabricPackPreflightTest` covers duplicate adapter IDs, ambiguous adapter
  matches, all three pinned metadata surfaces, independent registrations, and
  cleanup. Packwiz preparation separately verifies artifact hashes.
- `./play.sh modpack inspect fabric-stack --trajectory generalized-stack-2026-07-21`
  reported three mods in `activation=adapted`, each with its expected adapter,
  capability, and an empty blocker list.
- `./gradlew test assemble integrationTest` passed on Java 17.

## Live evidence

Session: `2026-07-22T08:27:37.712693Z-80540` (UTC event timestamp; local date
2026-07-21). Java 17 on macOS, local Minecraft 1.20.4 server, trajectory
`generalized-stack-live-2026-07-21`.

Initial status was parent `80540`, server `80560`, client `80657`, with
`serverReady=true`. Runtime logs then showed:

- `FABRIC_PACK_ACTIVE pack=minosoft_fabric_stack mode=adapted`
- `SODIUM_HOOK_INSTALLED` and `SODIUM_HOOK_INVOKED`
- `ENTITY_CULLING_HOOK_INSTALLED` and `ENTITY_CULLING_HOOK_INVOKED`
- `IMMEDIATELY_FAST_HOOK_INSTALLED` and `IMMEDIATELY_FAST_HOOK_INVOKED`

A one-file base source change produced `candidate_detected` at
`08:28:52.484714Z`, `candidate_ready` at `08:28:56.409095Z`, and
`candidate_activated` at `08:28:58.834558Z`. The old client emitted
`FABRIC_PACK_INACTIVE`; the replacement client PID was `81349` and emitted all
three install/invoke pairs again. Parent `80540` and server `80560` were stable.
Clean shutdown emitted `parent_stopping` and `parent_stopped`; final JSON status
reported null parent/server/client PIDs and `serverReady=false`.

An earlier fresh-store launch activated the three adapters but stalled in the
unrelated shared-asset verification stage before renderer invocation. Reusing
the already verified shared asset store completed the live gate; trajectory
state remained separate.

## Conclusions

- Adapter selection and hook ownership generalize without mod-ID branches in
  pack-loader control flow.
- Packwiz hash verification plus pinned-version metadata matching makes upstream
  changes fail closed in the launcher workflow.
- One scope can transact and clean up several independent hooks across a client
  generation restart.
- The hook call site's thread is part of its contract: frame batching ran on the
  render thread, while entity visibility was also observed in asynchronous
  renderer preparation.
- Remaining work is semantic and resolver-focused: dependency satisfaction,
  nested-JAR candidates, Fabric-shaped source APIs, binary API modules, and
  behavior/performance parity must each earn separate evidence.

## References

- [Sodium source and license](https://github.com/CaffeineMC/sodium)
- [Entity Culling source and license](https://github.com/tr7zw/EntityCulling)
- [ImmediatelyFast source and license](https://github.com/RaphiMC/ImmediatelyFast)
- [Fabric `fabric.mod.json` structure](https://docs.fabricmc.net/develop/getting-started/project-structure)
