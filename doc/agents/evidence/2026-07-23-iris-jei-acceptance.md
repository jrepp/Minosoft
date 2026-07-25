<!-- Copyright (C) 2026 Jacob Repp -->

# Iris and JEI behavioral acceptance — 2026-07-23 HST

> Historical checkpoint: the presentation-only Iris path described here has
> since been removed. Current render-substrate status and remaining shader-pack
> gates are recorded in the
> [2026-07-24 R0–R7 checkpoint](2026-07-24-render-substrate-r0-r7.md).

## Scope

This run extends exact adapted activation into repeatable behavioral evidence.
It proves that the Iris adapter owns a visible Minosoft world-framebuffer
presentation shader as well as live render and shader-reload boundaries, that
JEI is installed on the owned Fabric server and exposes a usable client recipe
workflow, and that registrations move cleanly between supervised client
generations. The Iris presentation is a source-native compatibility pass, not
OptiFine-format shader-pack execution.

## Environment

- Host: Darwin arm64
- Java: OpenJDK 17
- Minecraft/Fabric: 1.20.4 / Loader 0.15.11
- Pack: `fabric-stack` 0.4.0, seven exact adapted artifacts, no blockers
- Trajectory: `iris-jei-acceptance-2026-07-23`
- Lifecycle session: `2026-07-23T19:57:37.399491Z-68768`
- Final generation: 8

## Ownership and reload result

| Process | Initial | Final | Result |
| --- | ---: | ---: | --- |
| Play parent | 68768 | 68768 | stable |
| Fabric server | 68791 | 68791 | stable, ready |
| Minosoft client | 68904 | 57002 | supervised generations 1→8 |

Each replacement emitted `FABRIC_PACK_INACTIVE` before the next client exposed
the Iris/JEI operations. Endpoint discovery contained only the current client
generation plus the stable server endpoint; old provider operations did not
remain discoverable.

## Iris gates

Generation 8 exposed `mods.iris.summary`, `mods.iris.presentation`, and the
generation-owned `mods.iris.reload-shaders` operation. On its first
before-world-render callback, the adapter compiled
`minosoft:framebuffer/world/compatibility/iris.fsh` and installed it in the
owner-scoped `WorldFramebuffer.postProcessors` selection point.

The presentation operation objectively disabled and restored that exact shader:

```json
{"enabled":false,"installed":false,"shader":"minosoft:framebuffer/world/compatibility/iris.fsh","presentation":"source-native-iris-compatibility","frame":7153}
{"enabled":true,"installed":true,"shader":"minosoft:framebuffer/world/compatibility/iris.fsh","presentation":"source-native-iris-compatibility","frame":7211}
```

Live captures showed the expected presentation change only in the world layer:
cool lifted shadows, warmer highlights, higher color separation, and a bounded
vignette. HUD and container UI retained their native presentation. Captures
were inspected at `/tmp/iris-final-proof-disabled.png`,
`/tmp/iris-final-proof-enabled.png`, and
`/tmp/iris-final-enabled-reloaded.png`; they remain local acceptance artifacts
and are not committed. A first exact-frame night comparison also rejected a
weaker grade as visually insufficient; the accepted shader has stronger
shadow lift and split-tone separation.

The reload operation then queued the same native shader reload transaction used
by `ReloadCommand` onto the render owner:

```json
{"reloaded":true,"type":"shaders","presentationInstalled":true,"frame":1901}
```

Final diagnostics:

```text
client-event:before-world-render installed=true invocations=7270
shader-pipeline installed=true invocations=7270
world-post-process installed=true invocations=7270
resource-reload-event:complete installed=true invocations=2
shader-reload installed=true invocations=1
```

The complete counter changed from one initial-session completion to two after
the requested reload, and the rendered world remained capturable afterward.

## JEI gates

- Server `mods.debug` listed `jei` 17.3.1.5 as a universal Fabric mod alongside
  Fabric API and Inventory Management.
- Opening the player inventory displayed the owned Recipes control on the left,
  independent of Inventory Management's control on the right.
- The control opened 1,174 synchronized recipes over 147 pages.
- Page 1 → Next → Page 2 → Previous → Page 1 → Back returned directly to the
  inventory. Page replacement prevents pagination from growing the GUI history.
- Generation 4 diagnostics reported all three owned surfaces installed and
  invoked once:

```text
container-screen:minosoft:jei_container installed=true invocations=1
screen:minosoft:jei_recipes installed=true invocations=1
recipe-viewer installed=true invocations=1
```

Visual captures were inspected from `/tmp` and were not committed. Pages 1 and
2 rendered without the former diagonal index corruption, and the final Back
capture showed the inventory with the Recipes control still attached.

## Defects found by the stricter run

1. A fresh managed-server launch rejected
   `fabric-api-0.97.3+1.20.4.jar` because cleanup used the identifier-name
   pattern for artifact filenames. `Play` now uses a separate constrained
   managed-filename pattern that admits `+` while continuing to reject path
   separators and unsafe characters. The next launch prepared three support
   mods and reached server ready.
2. JEI Previous/Next originally pushed every page onto the GUI stack. Pagination
   now replaces the current recipe page, so Back returns to the owning
   container rather than traversing page history.
3. Iris's supplementary shader-reload counter originally lacked a matching
   diagnostic installation. It now has symmetric install/uninstall ownership,
   making the final telemetry truthful.
4. The earlier Iris adapter observed render and reload boundaries but did not
   own a program used to present a frame. `WorldFramebuffer` now exposes an
   owner-scoped post-processor selection point, and the Iris adapter installs,
   toggles, reloads, and removes its concrete presentation shader through that
   point. Closing a stale registration cannot remove the current owner.

## Automated validation

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricFunctionalityCatalogTest \
  --tests de.bixilon.minosoft.gui.rendering.framebuffer.world.WorldPostProcessorsTest \
  --tests de.bixilon.minosoft.gui.rendering.gui.mesh.GuiMeshBuilderTest \
  --tests de.bixilon.minosoft.dev.PlayUtilityTest

env -u NO_COLOR \
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test :integrationTest assemble
```

Focused checks passed. The final broad run reported 3,493 tests, zero
failures/errors, 116 intentional skips, and successful assembly.

## Final assertion

The exact Iris adapter now has a real, visible, toggleable, reload-surviving
shader presentation rather than telemetry-only render hooks. This accepts the
bounded source-native world post-process slice. It does not claim Iris/OptiFine
shader-pack discovery or compilation, shadow maps, upstream terrain/entity/sky
programs, the settings UI, or binary Sodium interop. JEI's declared native
recipe-viewer slice remains accepted independently.
