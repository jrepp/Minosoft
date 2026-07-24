<!-- Copyright (C) 2026 Jacob Repp -->

# Tech Reborn source-native registry and world generation — 2026-07-21

## Outcome

The exact Tech Reborn 5.10.4 artifact now supplies a source-native content
surface to Minosoft's in-process local world. This is an adapted host contract,
not execution of Tech Reborn, Reborn Core, or Fabric gameplay bytecode.

The implemented slice covers:

- decoding 310 blockstate definitions, 629 item-model definitions, and 15
  `minecraft:ore` configured/placed feature pairs from the immutable JAR;
- inferring block properties from variant and multipart conditions;
- deterministic registry IDs plus a distinct block-state palette and content
  fingerprint shared by local authority, world state, and renderer;
- mounting Tech Reborn and Reborn Core asset namespaces in the normal session
  asset stack;
- deterministic overworld stone/deepslate ore generation in the authoritative
  `LocalConnection` world;
- seed-selectable, memory-backed world regeneration through `play.sh`.

## Automated evidence

Commands:

```sh
./gradlew test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest
./gradlew test
./gradlew integrationTest
./play.sh modpack inspect tech-reborn --trajectory worldgen-2026-07-21
```

All passed on Java 17. The focused fixture covers ore JSON decoding, height
anchors, property schema inference, stable registry/state palette round trips,
energy semantics, and owned registration cleanup. Preflight reported all three
top-level artifacts as `activation=adapted` with empty blocker/dependency sets.

## Live evidence

Command, repeated twice with the same seed:

```sh
./play.sh start client --local-world \
  --world-seed 6072333650475958863 \
  --modpack tech-reborn \
  --trajectory worldgen-2026-07-21
```

The second run used immutable pack view
`05ad32456c22ccb2dc60e49216581cab325dcd4c7fb571b3bb2594e93818e033`
and emitted:

- `TECH_REBORN_CONTENT_ACTIVE ... blocks=310 items=629 states=3814 ores=15 fingerprint=2720ba05...53cb58`
- `FABRIC_REGISTRY_SYNCED ... blocks=310 items=629 registry=667 states=3814`
- `Assets verified!`
- `TECH_REBORN_WORLD_GENERATED seed=6072333650475958863 chunk=-10,-10 ores=590 features=15 fingerprint=2720ba05...53cb58`

Both runs generated 590 placements for the first logged chunk with the same
seed; this is the regeneration determinism canary. The second model load no
longer emitted missing-property warnings for Tech Reborn keys such as `active`,
`hassap`, or connection/shape variants. Shutdown finished with both server and
client stopped according to `./play.sh status --json`.

## Authority and remaining boundaries

`LocalConnection` is the authority for this scenario and installs the same
fingerprinted registry snapshot used by its generated chunks. An external
vanilla server cannot synchronize these runtime registry additions, and this
work does not create a Fabric dedicated server or execute Fabric `server`
entrypoints.

Standard blockstate/model assets enter Minosoft's renderer, including the ore
blocks used by generation. Full Tech Reborn graphics are not accepted yet:
live loading still reports unsupported custom/builtin model paths, one missing
machine texture, and an existing OpenGL error loop on the current macOS render
trajectory. Machine behavior, recipes, energy networks, menus, payloads,
persistence, drops, and cross-process registry synchronization also remain
outside this slice.

## Regeneration contract

The local Tech Reborn world currently uses memory storage. Every new
`--local-world` launch is therefore a clean regeneration; `--world-seed` makes
the result reproducible. There is no world directory to delete. Persistent
save migration must be designed before this registry epoch can be used for
long-lived saves.
