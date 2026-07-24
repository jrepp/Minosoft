<!-- Copyright (C) 2026 Jacob Repp -->

# Fabric client-event bridge evidence — 2026-07-22

## Decision

The next general-purpose Fabric integration is the client event backbone rather
than another mod-ID-specific adapter. Client-side rendering, UI, map, and shader
mods commonly need stable lifecycle and render boundaries; Minosoft can provide
those boundaries directly in source without runtime patching.

This slice deliberately implements source API compatibility, not Fabric binary
API compatibility. Callbacks receive Minosoft's `RenderContext`; classes built
against Mojang client types or Fabric API callback interfaces still do not link.

## Implemented boundary

`FabricClientEvents` provides six render-thread phases:

```text
client-started
before-world-render -> after-world-render
before-hud-render   -> after-hud-render
client-stopping
```

Registrations are owner-attributed, deterministic, timed through
`FabricModDiagnostics`, and removable through `FabricRegistrationScope`.
An owner may register multiple callbacks per phase; installed state clears only
when its final callback closes. A callback exception is logged and isolated so
later callbacks and the render loop proceed.

The pinned Fabric API adapter publishes `client-events` as an owned capability.
Its functionality catalog reports the source bridge as mapped and the upstream
lifecycle/render APIs as partial because their binary types remain unsupported.

## Automated checks

The Java 17 focused run completed successfully:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricClientEventsTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest
```

The event test proves registration order, multiple callbacks per owner,
exception isolation, invocation timing, unregister behavior, and installed-state
cleanup.
`FabricTechCapabilitiesTest` also includes the provider registration in the
shared scope-cleanup contract.

## Live generation evidence

The existing `debug-control-plane` parent retained PID `94754` and server PID
`94976` while source changes activated client generations 6 through 8. Final
generation 8 at PID `27683` reached `playing` with `renderReady=true`.
`debug mods` reported Fabric API active with the owned `client-events` hook
installed and 581 live
render-thread crossings in generation 7, then 4817 fresh crossings in generation
8 at capture time (60 ns average, 3250 ns maximum for the host capability
marker). Discovery contained only client generation 8 and the
original server endpoint, proving old-generation endpoint/provider cleanup.
An internal framebuffer capture at generation 7 frame 540 produced a valid
1800×1000 composited world/pause-menu image, providing a visual smoke check for
the new world and HUD boundaries without OS-level screen capture.

## Next gates

- Register a source-native visual canary on each phase and prove exact phase
  ordering from a captured frame rather than only the host crossing counter.
- Add client-tick phases at a single deterministic session tick boundary; the
  current independent scheduled tasks are not yet such a boundary.
- Complete failure-preserving candidate swaps for shader and texture reloads;
  the source lifecycle now exists but GPU rollback does not.
- Translate selected Fabric API callback interfaces only after a stable mapping
  exists for every exposed argument type.
- Build Iris/Xaero-specific behavior on these phases only after their other
  required rendering, resource, input, and persistence capabilities are mapped.
