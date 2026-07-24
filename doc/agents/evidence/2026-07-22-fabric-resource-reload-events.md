<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric resource-reload bridge evidence — 2026-07-22

## Decision

Resource reload is the next general-purpose Fabric integration after explicit
render and tick boundaries. It gives compatibility adapters a stable place to
invalidate caches, stage dependent data, and report failures without exposing
Minosoft's concrete asset or OpenGL implementations.

This slice defines source-level lifecycle semantics. It does not claim binary
compatibility with Fabric Resource Loader APIs, automatic filesystem watching,
or last-known-good GPU replacement.

## Implemented lifecycle

`FabricResourceReloadEvents` publishes these phases for session assets, shaders,
and textures:

```text
PREPARE -> prepare candidate -> APPLY -> publish/apply -> COMPLETE
                       failure --------------------------> FAILED -> rethrow
```

Callbacks are ordered, owner-attributed, exception-isolated, and removed through
their registration handles. They execute synchronously on the initiating host
thread. Session assets use the loading worker. Shader and texture reloads are
queued to the render thread before entering the transaction.

Initial `PlaySession` assets now load into a candidate manager before the
session field is assigned. If preparation fails, the candidate is unloaded; a
cleanup failure is retained as a suppressed exception and the original failure
is rethrown. Successful preparation publishes the candidate at `APPLY`.

The manual shader and texture commands now expose the same lifecycle. Their
existing implementations still mutate at apply time. In particular,
`OpenGlNativeShader.reload()` unloads the active shader before loading its
replacement, so an invalid shader may still lose the previous GPU program. Do
not treat this lifecycle bridge as failure-preserving shader hot reload.

## Automated evidence

The focused Java 17 suite passed transaction ordering/result propagation,
prepare-failure non-application, apply failure reporting, provider cleanup, and
functionality-catalog coverage:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricResourceReloadEventsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
```

The complete root unit and integration suites passed with the final resource
integration in place. The integration run covered supported protocol registries
plus physics, chunks, entities, particles, input, and rendering fixtures.

## Live generation

The `debug-control-plane` parent retained PID `94754` and server PID `94976`
while final client generation 12 activated at PID `44599`. Debug status reported
`ready=true`, `sessionState=playing`, and `renderReady=true` for Minecraft
1.20.4 with the four-mod `fabric-stack` active.

Fabric API diagnostics reported `resource-reload-events` installed with exactly
three invocations on the fresh generation. Those correspond to the successful
initial session-asset `PREPARE`, `APPLY`, and `COMPLETE` crossings. The provider
marker measures bridge crossing overhead, not the asset load or whole reload
cost.

Manual shader/texture commands were not invoked in this generation because the
supervised client does not expose its interactive command input through the
debug CLI. Their transaction and render-queue placement are source-verified;
visual and invalid-resource preservation remain acceptance gates.

## Remaining gates

- Add filesystem/resource watching and immutable candidate staging.
- Compile/link shader candidates without unloading the active program, atomically
  swap on success, and dispose the old program afterward.
- Define texture candidate ownership and prove repeated reload does not grow GPU
  or asset-manager counts.
- Expose a bounded debug operation for manual resource reload so success,
  failure, visual change, and last-known-good preservation are automatable.
- Translate binary Fabric resource callback interfaces only for explicitly
  supported modules and Minosoft-owned context types.
