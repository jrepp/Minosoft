<!-- Copyright (C) 2026 Jacob Repp -->

# Iris and JEI adapted activation evidence — 2026-07-23 HST

> Subsequent behavioral acceptance added and visually verified an owner-scoped
> Iris compatibility presentation shader. See
> [Iris and JEI behavioral acceptance](2026-07-23-iris-jei-acceptance.md).
> OptiFine-format shader-pack execution remains unmapped.

## Scope

This evidence accepts the exact Iris 1.7.2 and JEI 17.3.1.5 artifacts for
Minecraft 1.20.4 as blocker-free, source-native Minosoft adaptations. It proves
owned activation, live host-boundary invocation, cleanup across supervised
client generations, and a usable JEI recipe view. It does not claim that their
Mojang-targeted classes, mixins, access wideners, or Fabric entrypoints execute,
nor that their complete upstream behavior is implemented.

## Exact artifacts

| Mod | Upstream identity | Pinned SHA-512 | Validated metadata |
| --- | --- | --- | --- |
| Iris | `iris-1.7.2+mc1.20.4.jar`, Modrinth version `hq98tuSS` | `3c2f88abfa60c7e46c720663511a4f2ac7c4b3f59ce9af8d0d5d2d48b109310aade0128c8e24313ee8ec46094659eac525eed7cecd6d4eb85c0822942b30cc0c` | client environment, `modmenu` entrypoint, nine mixin declarations, `iris.accesswidener`, five nested JARs, Sodium `[0.5.8,0.5.11]` |
| JEI | `jei-1.20.4-fabric-17.3.1.5.jar` | `ab734007486afcdb6cf5829bc5a1a829f6a9a56d8fd46b93a4ca0c4bf21c89c523347045b9c4b25e8ebbe4f33e6bdf39728812df4e12f6d15883526069dcaa79` | both environment, `client`/`jei_mod_plugin`/`main` entrypoints, one mixin declaration, `jei.accesswidener`, no nested JARs |

Adapter matching is exact and deny-by-default. Any change to these surfaces
requires a new manifest pin and adapter review.

## Structured preflight

`./play.sh modpack inspect fabric-stack --trajectory
iris-jei-activation-2026-07-23` prepared immutable pack view
`f68aa67067cccfb0c0329e255b4a798a18e7761389cad0e02070e6c22d7e6d23`
and reported:

```text
pack=minosoft_fabric_stack version=0.4.0 mods=7 activation=adapted
mod=iris version=1.7.2+mc1.20.4 environment=client activation=adapted adapter=minosoft:iris-1.7.2-mc1.20.4 capabilities=shader-pipeline,resource-reload-events mapped=0 partial=2 unmapped=4 nested=5 blockers= dependencyIssues=
mod=jei version=17.3.1.5 environment=* activation=adapted adapter=minosoft:jei-17.3.1.5-mc1.20.4 capabilities=container-screen-extensions,recipe-viewer mapped=2 partial=0 unmapped=8 nested=0 blockers= dependencyIssues=
```

All seven pack artifacts reported `activation=adapted` with empty blocker and
dependency-issue lists.

## Live acceptance

- Host: Darwin arm64; Java 17; Minecraft 1.20.4 local offline-mode server.
- Lifecycle session: `2026-07-23T09:35:15.369726Z-35417`.
- Accepted client generation: 33, PID 56989.
- Parent PID 35417 and server PID 35639 remained stable and
  `serverReady=true` across the activation/fix generations.
- The client emitted exact-adapter activation plus
  `FABRIC_PACK_ACTIVE ... mods=fabric-api,immediatelyfast,sodium,entityculling,inventorymanagement,iris,jei blocked=`.
- Iris installed before-world-render, completed-shader-reload, and
  shader-pipeline diagnostics. Generation 33 recorded 22,776 render-boundary
  invocations and one completed shader reload.
- JEI attached one owned Recipes control to the inventory screen. Opening it
  invoked its container extension, screen, and recipe-viewer diagnostics.
  The view contained 1,174 synchronized session recipes over 147 pages;
  Previous, Next, and Back were exercised and rendered cleanly.
- `core.capabilities` exposed `mods.iris.summary` and `mods.jei.summary`, proving
  the active generation owned both debug providers.
- Earlier supervised candidates emitted `FABRIC_PACK_INACTIVE`; replacement
  generations registered one screen/extension/callback set rather than
  accumulating duplicates. Focused tests independently close each registration
  scope and assert all owned registrations disappear.

## Host defects exposed and fixed

The integration uncovered two Minosoft host defects rather than adapter
linkage failures:

1. `BackgroundedContainerScreen.getAt` rejected pointers left of the centered
   vanilla panel before querying container extensions. It now translates the
   pointer and lets extensions participate in hit testing, making the visible
   JEI control clickable.
2. `GuiMeshBuilder.fixIndex` treated vertex count as quad count and emitted four
   times too many index groups. It now validates complete groups of four
   vertices and emits one six-index group per quad. `GuiMeshBuilderTest` covers
   zero, one, and 64 quads plus incomplete input. This removed the diagonal
   corruption seen when the multi-row recipe menu first rendered.

## Validation

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.gui.mesh.GuiMeshBuilderTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest

env -u NO_COLOR \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test :integrationTest

env -u NO_COLOR \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew assemble

./play.sh modpack inspect fabric-stack \
  --trajectory iris-jei-activation-2026-07-23
./play.sh status --json
./play.sh debug mods --role client \
  --trajectory debug-control-plane --json
./play.sh debug capabilities --role client \
  --trajectory debug-control-plane --json
```

The focused checks, assembly, and full root plus integration suites passed.
The two suites reported 3,491 tests, zero failures/errors, and 115 intentional
skips. `NO_COLOR` was removed for the full run because
`LogArgumentTest.default no color` explicitly asserts the default color-enabled
environment while this agent session exports that standard variable.

## Remaining boundary

Activation is complete for the declared source-native capabilities. Iris shader
pack discovery/compilation, shadow maps, settings, and binary Sodium interop
remain cataloged as unmapped; its two host-boundary slices remain partial. JEI
ingredient overlays, recipe transfer, bookmarks, search, ingredient sync,
plugin APIs, configuration, and developer tools remain cataloged as unmapped.
Those honest parity records do not reintroduce activation blockers.
