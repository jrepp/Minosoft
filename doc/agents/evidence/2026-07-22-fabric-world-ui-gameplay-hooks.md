<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric world, UI, networking, and gameplay hook evidence — 2026-07-22

## Decision

General Fabric integration uses explicit Minosoft domain boundaries rather than
packet-specific callbacks or Mojang-class emulation. Every registration has an
owner cleanup handle, deterministic order where multiple callbacks are allowed,
failure isolation, and attributed diagnostics. Session-bearing contexts never
assume a singleton client.

## Implemented families

| Family | Authoritative boundary | Contract |
| --- | --- | --- |
| World/dimension | initialize, respawn, local connect, reconfigure, and play-session state | Before/after identity change plus playable join/leave, with old/new identity and cause. |
| Chunk | `ChunkManager.update`, `unload`, and post-lock world clear | Create/update/unload/bulk-clear after native mutation; local and remote paths converge. |
| Block mutation | `World.set` and single/batched `Chunk.apply` | Absolute positions and previous/current states, batched after the native update boundary. |
| Client payloads | `PlayChannelManager` | One-MiB source API limit, immutable callback view, explicit-session send, ordered owner receivers. |
| Screens | `FabricScreens` and the render queue | Namespaced factories, duplicate rejection, render-thread open, owner cleanup. |
| HUD layers | `HUDManager` current/future attachment | Builders add/remove through the render queue; toggles use separately owned `FabricKeyBindings`. |
| Client commands | `ChatNode` before server command dispatch | Exact local command names receive explicit session/raw arguments; unknown commands continue to the server. |
| Entities | `WorldEntities` | Add/remove/bulk-clear after the store lock, including both packet and local paths. |
| Player interactions | attack/use domain entry points | Ordered PASS/DENY policy for attack block/entity and use block/entity/item before native handling. |
| Particles and sound | accepted particle queue and audio request boundary | Render/audio observations retain explicit sessions and native Minosoft objects. |

Fabric API activation installs provider markers for every family in the pack's
`FabricRegistrationScope`. Closing the scope removes all provider markers and
consumer registrations independently.

## Focused automated evidence

The focused Java 17 suite covers duplicate rejection, independent/idempotent
cleanup, current/future HUD target fanout, payload ordering and limit, provider
lifecycle, and catalog honesty:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricUiHooksTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPayloadChannelsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
```

World, chunk, entity, interaction, media, and payload invocation also require a
real play/render session and are therefore part of the live-generation gate,
not simulated with null or singleton test sessions.

## Acceptance result

Java 17 acceptance on 2026-07-22 passed cleanly:

```sh
./gradlew :test :integrationTest --console=plain
```

- unit: 1,434 tests, 0 failures, 0 skipped;
- integration: 2,001 tests, 0 failures, 115 skipped.

The first full integration pass exposed session-less entity fixtures at the new
event boundary. `FabricEntityEventContext.session` is therefore explicitly
nullable for entities created before session attachment; production lifecycle
events continue to carry the owning `PlaySession`. A separate pre-existing
timing-sensitive `KeyHandlerTest` failed once, passed in isolation, and the clean
full rerun passed.

## Live-generation evidence

The `debug-control-plane` trajectory remained running through source reload and
reported client generation 35 (PID 71095) ready in `minecraft:overworld`, with
one session in `playing` state. Its Fabric 1.20.4 server (PID 94976) remained
ready with one player and three worlds.

`./play.sh debug mods --role client --trajectory debug-control-plane` reported
Fabric API active and every one of its 18 source-native provider hooks installed.
Runtime counters proved the new world seams were reached by real traffic:

| Hook | Invocations | Average provider overhead |
| --- | ---: | ---: |
| World lifecycle | 3 | 805 ns |
| Chunk lifecycle | 453 | 251 ns |
| Block mutation | 4 | 1,021 ns |
| Entity lifecycle | 266 | 920 ns |

Client event, tick, connection, input, and reload counters were also non-zero.
Consumer-driven hooks such as commands, screens, HUD layers, payload receivers,
and interaction policy correctly remain at zero until a mod registers/uses them.
The runtime log contains `FABRIC_API_MODULES_ACTIVE` with all implemented flags
enabled.

## Deferred families

The exact blockers and acceptance requirements for atomic GPU replacement,
registry/data epochs, model resolvers, render passes, block entities, persistent
state, a Minosoft server host, binary Fabric APIs, and mixin/access-widener
translation are maintained in the [Fabric hook backlog](../backlog/fabric-hooks.md).
