<!-- Copyright (C) 2026 Jacob Repp -->

# Naturalist integration evidence

Date: 2026-07-24

## Compatibility decision

`fabric-stack` version `0.7.0` pins Naturalist `5.0pre3` and its GeckoLib
dependency. The available Naturalist Fabric artifact targets Minecraft 1.20.1,
while the stack targets Minecraft 1.20.4. Running Naturalist's upstream server
entrypoint on the managed 1.20.4 Fabric server failed at linkage with
`NoClassDefFoundError: net/minecraft/class_2368`. Both Packwiz entries are
therefore client-only, and the launcher does not install them into the server
view.

This is an exact source-native adaptation, not Fabric binary compatibility.
`NaturalistCompatibilityAdapter` validates the known `5.0pre3` metadata,
mounts the artifact's compatible assets, and does not execute Naturalist's
entrypoints, mixins, nested libraries, AI, spawning, or registry code.

## Content boundary

The adapter owns:

- 31 Naturalist entity routes across 24 Gecko geometry identities;
- one deterministic idle/movement controller for every routed geometry;
- current geometry and `.rp_anim.json` assets, excluding obsolete animations
  and unrouted geometry;
- a source-path alias from Naturalist's ostrich geometry to the zebra route;
  the upstream artifact has a zebra animation and texture but stores the matching
  mesh as `geo/entity/ostrich.geo.json` with identifier
  `geometry.sf_nba.ostrich`;
- deterministic texture fallbacks for upstream geometry whose default texture
  path does not correspond directly to an artifact file.

The shared Gecko parser now recognizes `.rp_anim.json` files and scalar Gecko
channel values. Content model names include their source path so the
`caterpillar` and `lizard` files can safely retain the same upstream
`geometry.unknown` identifier.

Naturalist's synchronized entity registration, gameplay AI, spawning, and
state-specific texture selection remain unmapped. Those require a compatible
server-side content contract before this rung can advance beyond partial.

## Verification

Focused unit tests cover exact adapter selection, asset filtering and aliases,
31 entity routes, 24 controller bindings, cleanup, `.rp_anim.json` discovery,
scalar Gecko channels, source-qualified content identities, and the zebra
geometry alias against the pinned artifact layout. The adapted Gecko route also
passed the focused integration test through content baking and render-layer
creation.

`./play.sh modpack prepare fabric-stack` and `modpack inspect` resolved the
immutable pack with nine active client artifacts and no activation blocker.
The supervised `naturalist-integration` trajectory kept the same ready managed
server while the client advanced through eleven hot-reload generations. The
final client generation joined the 1.20.4 server, reached render-ready state,
and reported exact active adapters for GeckoLib `4.4.4` and Naturalist
`5.0pre3`. Its latest activation emitted:

```text
NATURALIST_CONTENT_ACTIVE version=5.0pre3 entityRoutes=31 geometries=24 upstreamGameplay=false
```

No Naturalist-specific missing-texture, exception, or fatal record followed
that activation.
