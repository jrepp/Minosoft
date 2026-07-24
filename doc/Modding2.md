# Modding (v2)

> This file records the original mod-loader goals. For the current Fabric
> adaptation levels, ownership rules, and compatibility trajectories, use the
> [mod workflow evidence map](agents/areas/08-modding.md). Model, texture, EMF,
> GeckoLib, OptiFine-format, and Animated Java boundaries are documented in the
> [content and asset system](Assets.md).

## Goals

- Not supported (i.e. no backwards compatibility)
  - basic api, everything else is unsafe
- inject main class
- kotlin support
- mixins (with java)
- jar loading or folder loading with jcl
- build system (gradle?): easy debugging, executing and hot reloading
- soft dependency management
  - depend on other mods by name
  - no libraries (aka bundle that with your jar)
- basic api
  - connect events (by regex, port, hostname, ip, …)
- load minosoft api for now from jitpack, then publish stable versions to maven central
- gui with list of mods
- loading procedure:
  - priority
  - load meta -> inject to classpath -> initialize main class
- main class template (with logging, assets, ...)
- Multiple `mods` folders
  - pre boot (before loading anything)
  - boot (while loading everything else)
  - post boot (start loading after booting, but don't wait for them. Only wait before loading session)
- No classic event system, events are stateless. Everything that is stateful should use observables
