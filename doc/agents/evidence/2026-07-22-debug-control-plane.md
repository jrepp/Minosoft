<!-- Copyright (C) 2026 Jacob Repp -->

# Debug control-plane acceptance evidence — 2026-07-22

## Scope

- Host: macOS, Java 17
- Minecraft server: 1.20.4
- Fabric Loader: 0.15.11
- Fabric API: 0.97.3+1.20.4
- Client pack: `fabric-stack`
- Trajectory: `debug-control-plane`
- Final parent/server: PID `94754` / `94976`
- Client generations: PID `95703` → `98547` → `99377`

This pass implemented and exercised the version-one client/server debug control
plane through the compiled repository Java utility. No AppleScript, OS screenshot
API, RCON, or server-console parsing was used for the debug operations.

## Build and automated checks

The following completed successfully with the Java 17 toolchain:

```sh
./gradlew :debug-core:test
./gradlew :debug-core:compileJava compileKotlin \
  :play-util:installDist :debug-server-fabric:remapJar
./gradlew test
```

The full unit run included launcher contracts, Fabric catalog/diagnostics and
pack ownership, lighting, local generation, and the existing repository suite.
Focused debug-core tests covered:

- framed JSON/binary round trips and payload rejection;
- owner-only discovery publication and cleanup;
- stale PID/process-start rejection;
- wrong-token authentication failure;
- operation registration/removal and unsupported operations;
- attachment framing;
- execution deadlines followed by continued connection use;
- endpoint credential/socket cleanup on shutdown;
- bounded Unix and Windows transport-address derivation.

The Windows JNA named-pipe backend and owner/SYSTEM security descriptor compiled
as part of these builds. Windows runtime ACL/pipe behavior was not executed on
this macOS host.

## Lifecycle and discovery

`./play.sh stop` shut down the previous parent, client, and server. Immediately
afterward, JSON status contained no process PIDs and `debug endpoints --json`
returned an empty array.

The final launch used:

```sh
./play.sh dev both --modpack fabric-stack \
  --trajectory debug-control-plane
```

It started the pinned Fabric server plus owned debug bridge, then client
generation 1. Discovery returned exactly one client and one server descriptor.
Server status reported ready, Minecraft 1.20.4, three worlds, and one connected
player. Client status reported the active four-mod adapted pack.

On macOS, live permissions were:

```text
debug/v1, endpoints/, credentials/       drwx------
endpoint descriptors and credentials     -rw-------
runtime directory                         drwx------
Unix sockets                              srw-------
```

Endpoint output exposed role, PID, trajectory, generation, transport, address,
and protocol range; it did not expose credentials.

## Visual and input acceptance

The CLI captured the live composited framebuffer as an 1800×1000 PNG with frame
number and timestamp. Point/region sampling returned RGBA, SHA-256, and average
luminance. Visual inspection showed Minosoft's actual death screen and Respawn
button, proving the data was the game framebuffer rather than a placeholder.

Two internal mouse-move events followed by `MOUSE_BUTTON_LEFT` press/release
traversed the normal Minosoft input path and selected Respawn. The next
`client.player` sample changed from `dead` to `playing`, health 20, without
window focus or OS input synthesis.

## State, Fabric, and mod diagnostics

Client and server samples agreed on the player's position
`(-8.5, 118.0, 10.5)` after respawn. Server state also returned authoritative
dimension/time/player summaries. Client `mods.debug` reported Fabric API,
ImmediatelyFast, Sodium, and Entity Culling active with installed hooks and live
invocation/timing counters. Server `mods.debug` enumerated Fabric Loader, the
Fabric API modules, Minecraft, and `minosoft_debug_bridge`.

Client capabilities included these generation-owned operations:

```text
mods.fabric-api.summary
mods.sodium.summary
mods.immediatelyfast.summary
mods.entityculling.summary
```

`mods.sodium.summary` reported the exact adapter, active lifecycle, trajectory,
generation, installed chunk-render scheduling hook, and invocation timing.

## AOI comparison and defect found

A loaded-only inclusive box from `(-10,116,8)` through `(-6,120,12)` sampled 125
cells from both endpoints. The first comparison correctly exposed one semantic
serialization mismatch:

```text
client minecraft:birch_log[axis=Y]
server minecraft:birch_log[axis=y]
```

The client wire serializer now emits canonical lowercase property keys/values,
and the CLI normalizer also handles property case plus ordering. The repeated
comparison reported:

```text
volume=125
clientNotLoaded=0
serverNotLoaded=0
differenceCount=0
equal=true
```

An intentionally oversized server box returned the stable JSON error
`limit_exceeded` with the 32768-block maximum and no Java stack trace.

## Reload and provider ownership

A base client-source correction produced generation 2 at PID `98547` while the
parent remained `94754` and server remained `94976`. Discovery contained only
the new client endpoint and the original server endpoint. The four-mod stack and
Sodium provider were active in generation 2, and the normalized block comparison
passed.

A subsequent shared `debug-core/src/main` connection-lifecycle hardening change
exercised the newly added shared-core watch root. It produced generation 3 at PID
`99377`, again retaining the same parent/server. Discovery contained only client
generation 3 and server generation 1. `mods.sodium.summary` identified generation
3 and fresh counters, proving provider removal/re-registration rather than a
stale old-generation operation.

## Remaining boundary

Version one deliberately advertises bounded one-shot operations only. Event/AOI
subscriptions, streaming backpressure, advanced AOI layers, input held-state
cleanup/semantic sequences, provider metrics/AOI registration, and Windows/Linux
runtime transport evidence remain later gates. Their absence is visible through
`core.capabilities`; the CLI does not silently fall back to an OS automation
path.
