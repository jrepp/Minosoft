<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric client-tick bridge evidence — 2026-07-22

## Decision and prior behavior

Client tick events are the next general-purpose Fabric integration after render
and lifecycle phases. They support map caches, key-driven state, animation,
client gameplay helpers, and future safe reload coordination.

Bytecode inspection of KUtil 1.31's `RepeatedTask` established that the old
`SessionTicker` submitted each entity, world, random-display, particle, and
extension task independently to the default thread pool. Each task prevented
self-overlap, but different tasks could run concurrently. There was therefore no
truthful start/end client-tick boundary.

## Implemented safe point

`SessionTicker` now submits one non-overlapping repeated task at the existing
20 Hz interval. It snapshots and executes this order synchronously:

```text
Fabric START
  entities
  world
  random display
  optional debug/time tasks
  extension tasks in registration order
Fabric END
```

Registrations made during a cycle enter the next snapshot. Each task retains the
old failure-isolation behavior: its exception is reported and later tasks still
run. `END` runs from `finally` once start completes. This boundary does not make
simulation run on the render thread.

`FabricClientTickEvents` supports multiple ordered callbacks per owner and phase
through the existing owner-attributed registry. Fabric API publishes the
generation-owned `client-tick-events` capability; callbacks and capability
crossings appear in `FabricModDiagnostics`.

## Automated evidence

The Java 17 focused checks proved ordered execution, per-task failure isolation,
guaranteed end, provider cleanup, and catalog coverage:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.protocol.network.session.play.tick.SessionTickRunnerTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
```

The complete root unit suite and complete integration suite also passed. The
integration run covered supported protocol registries plus physics, chunks,
entities, particles, input, and rendering fixtures.

## Live generation and cadence

The `debug-control-plane` hot-reload parent retained PID `94754` and server PID
`94976` while client generation 9 activated at PID `35933`. The client reached
`playing` with `renderReady=true`. Fabric API diagnostics reported the
`client-tick-events` hook installed with 1,808 crossings on the first sample.

A fixed-window debug measurement observed 202 start/end crossings over
5.2778 seconds, or 101 complete cycles at 19.14 cycles/second including two CLI
round trips. This is consistent with the configured 20 Hz interval without
inventing a tick count from wall-clock time.

## Remaining gates

- Add explicit safe-point quiescence so reload can wait for a current cycle and
  reject old-owner invocation before the next one.
- Attribute callback cost separately from the near-zero host capability marker;
  do not attribute core world/entity tick cost to Fabric API.
- Translate Fabric's binary `ClientTickEvents` callback interfaces only after a
  stable Minecraft-client/session type mapping exists.
- Add payload and explicit world-change lifecycle bridges for the next Xaero
  compatibility layers; source input, key-binding, connection, and resource
  reload lifecycles are now established separately.
