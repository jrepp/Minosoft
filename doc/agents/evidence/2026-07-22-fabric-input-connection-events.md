<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric input, key-binding, and connection evidence — 2026-07-22

## Decision

Input and client connection lifecycle are the next general-purpose Fabric
integration points after render, tick, and resource reload. Map and configuration
mods need configurable controls that survive session recreation, observe the same
path as developer automation, and stop invoking discarded mod owners. They also
need connection/world boundaries without assuming Minosoft has one global client.

This is a source-level bridge over Minosoft types. It does not make Fabric's
`KeyBindingHelper`, Mojang key classes, networking callbacks, or Xaero binaries
link against Minosoft.

## Normalized input boundary

`FabricInputEvents` observes key, Unicode code-point, mouse-move, and scroll
events in `InputManager`. GLFW and the debug channel already publish the same
session render events, so both reach this boundary. Key observations occur after
screen handling and Minosoft binding updates; repeat events remain observable.
Each event states whether a screen/input consumer was active.

Callbacks run synchronously on the render thread, preserve registration order,
isolate failures, carry owner attribution, and are removable through their
registration handle. The bridge is observational: a mod callback cannot consume
or replace host input routing.

## Configurable key-binding ownership

`FabricKeyBindings` holds namespaced definitions independently of any particular
render context. A definition registered before rendering attaches when each
`InputManager` initializes; definitions registered later fan into all attached
managers. Each context receives its own copy of the mutable default binding.

The registry rejects duplicate resource IDs deterministically. Closing a mod
registration removes only that callback from every live manager. Closing a
render context detaches only that manager. Persisted profile overrides remain
normal user configuration, but no mod callback or render context is retained by
the registry after its handle closes. Callback exceptions are logged and timed
without aborting host input.

`BindingsManager.register` now returns a callback-specific handle. Existing host
callers may ignore it; reloadable integrations own it through their Fabric scope.

## Connection lifecycle

`FabricClientConnectionEvents` follows each `PlaySession.state` and emits:

```text
CREATED
STATE_CHANGED(previous, current)
JOINED when current == PLAYING
DISCONNECTED when current is DISCONNECTED, KICKED, or ERROR
```

Events carry the concrete session and do not rely on `CLI.session` or a singleton.
They execute on the thread changing session state, which may be a loading,
network, render, or local-authority thread. Consumers must enqueue graphics work
onto the render context.

## Automated evidence

The focused Java 17 suite passed definition fan-out to current/future targets,
per-target detach, per-registration cleanup, duplicate rejection, Fabric API
provider cleanup, and catalog coverage. The complete root unit and integration
suites then passed with the final implementation, including existing input,
protocol registry, physics, chunk, entity, particle, and rendering fixtures.

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricKeyBindingRegistryTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
./gradlew :test :integrationTest
```

## Live evidence

The `debug-control-plane` parent retained PID `94754` and server PID `94976`
while final client generation 16 activated at PID `51665`. The client reported
`ready=true`, `sessionState=playing`, and `renderReady=true` with all four
`fabric-stack` artifacts active.

Fabric API diagnostics reported all three new providers installed. The fresh
session recorded 11 `client-connection-events` crossings through `PLAYING`.
On generation 16 the input counter was sampled at 0, then debug-channel `KEY_M`
press and release operations both returned `injected=1`; the next sample was
exactly 2. This proves debug automation crosses the same normalized bridge once
per key event. `key-bindings` correctly reported zero invocations because Fabric
API publishes the capability but does not invent a gameplay binding.

## Catalog impact and remaining gates

Xaero Minimap and World Map settings are now partial rather than wholly unmapped:
the host binding lifecycle exists, while their binary registration, config
screens, profiles, map rendering, caches, and gameplay code remain blocked.

- Add an external/source-native canary binding that opens a disposable test
  screen, then prove registration removal across a mod-only generation swap.
- Add explicit world-change phases distinct from connection state for dimension
  and server identity changes.
- Define bounded client payload/channel registration with owner cleanup before
  mapping Fabric networking.
- Translate binary Fabric input/key-binding interfaces only with explicit type
  adapters and behavioral fixtures.
