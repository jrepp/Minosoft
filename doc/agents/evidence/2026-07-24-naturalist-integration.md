<!-- Copyright (C) 2026 Jacob Repp -->

# Naturalist integration evidence

Date: 2026-07-24

This record captures the original adapter/server activation checkpoint. The
[2026-07-26 registry-sync evidence](2026-07-26-naturalist-registry-sync.md)
supersedes its route counts and remote-registry gap without rewriting the
historical launch observations below.

## Compatibility decision

`fabric-stack` version `0.8.0` pins the ported Naturalist `5.0.0-pre.4`
artifact and GeckoLib `4.4.4` for Minecraft 1.20.4. Naturalist and GeckoLib are
both installed into the client and managed Fabric server views. The ported
artifact is not published on Modrinth, so its exact SHA-512 identity is declared
with a `minosoft-cache:` URL and resolved from `MINOSOFT_MODPACK_CACHE`. This
keeps locally produced third-party binaries out of the repository while making
their source cache independent of each machine's platform-specific runtime
store.

The superseded `5.0pre3` artifact targeted Minecraft 1.20.1 and failed when its
server entrypoint linked against 1.20.4 with
`NoClassDefFoundError: net/minecraft/class_2368`. The `5.0.0-pre.4` port updates
that server/runtime boundary and declares Minecraft `>=1.20.4 <1.20.5`.

This is an exact source-native adaptation, not Fabric binary compatibility.
`NaturalistCompatibilityAdapter` validates the exact `5.0.0-pre.4` metadata,
mounts the artifact's compatible assets, and does not execute Naturalist's
entrypoints, mixins, nested libraries, AI, spawning, or registry code inside
the Minosoft client. The managed Fabric server does execute the upstream
Naturalist runtime and owns gameplay behavior.

## Content boundary

The adapter owns:

- 31 Naturalist entity routes across 24 Gecko geometry identities;
- one deterministic idle/movement controller for every routed geometry;
- current geometry and `.rp_anim.json` assets, excluding obsolete animations
  and unrouted geometry;
- an adapter-local native bridge for Naturalist's zebra renderer. Pinned
  bytecode shows `ZebraModel` extends Minecraft 1.20.4's horse layer and adds
  conditional chest boxes; the artifact's `geo/entity/ostrich.geo.json` is an
  unrelated two-legged winged mesh, not a renamed zebra. The default bridge
  retains the adult, unsaddled, unchested horse cuboids and exposes the control
  bone names used by the shipped zebra idle/walk timelines;
- deterministic texture fallbacks for upstream geometry whose default texture
  path does not correspond directly to an artifact file.

The shared Gecko parser now recognizes `.rp_anim.json` files and scalar Gecko
channel values. Content model names include their source path so the
`caterpillar` and `lizard` files can safely retain the same upstream
`geometry.unknown` identifier.

Naturalist's remote registry negotiation and state-specific texture selection
remain unmapped on the Minosoft client. The compatible managed server now owns
entity registration, gameplay AI, and spawning, but a synchronized content
contract is still required before this rung can advance beyond partial.

## Verification

Focused unit tests cover exact adapter selection, asset filtering and aliases,
31 entity routes, 24 controller bindings, cleanup, `.rp_anim.json` discovery,
scalar Gecko channels, and source-qualified content identities. The later zebra
regression parses the generated horse-layer bridge, proves four adult leg cubes
and no wing bones, attaches both sets of shipped animation channel names, and
rejects the ostrich geometry from the adapter view. The adapted content route
also passed a supervised client generation through parsing, baking, join, and
render readiness.

The Naturalist fork's `5.0.0-pre.4` build passed client and dedicated-server
launches on Minecraft 1.20.4, including alligator and giraffe summons and
Shellstone registration. Minosoft's focused launcher and adapter tests pass on
Java 17. A fresh runtime store imported the exact Naturalist artifact from the
portable cache, prepared `fabric-stack` `0.8.0`, and inspected all nine client
artifacts without an activation or dependency blocker. Preflight selected
`minosoft:naturalist-5.0.0-pre.4-fabric-mc1.20.4`.

An isolated managed Fabric server then staged seven support mods, reported
Naturalist `5.0.0-pre.4` and GeckoLib `4.4.4` in Loader inventory, and reached
`server.game-ready`. It was stopped cleanly after acceptance.

```text
NATURALIST_CONTENT_ACTIVE version=5.0.0-pre.4 entityRoutes=31 geometries=24 upstreamGameplay=false
```

The activation message remains explicit that the Minosoft process mounts
source-native content rather than executing upstream gameplay code; gameplay is
owned by the managed Fabric server.
