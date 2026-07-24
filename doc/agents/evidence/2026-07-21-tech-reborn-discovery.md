<!-- Copyright (C) 2026 Jacob Repp -->

# Tech Reborn industrial-mod discovery — 2026-07-21

## Decision

Use Tech Reborn as the Fabric-native industrial gameplay target for the current
Minecraft 1.20.4 compatibility lab. Its exact artifact graph may activate
through explicit source-native capability adapters. Keep full gameplay claims
blocked until Minosoft implements authoritative gameplay-mod contracts; adapted
activation is not a substitute for block/item/machine/network behavior.

Two requested inputs could not join the active baseline:

- RealisticCraft is a whole Fabric modpack. Its published versions target 1.19,
  1.21.10, or 1.21.11, not 1.20.4.
- Official Mekanism 10.5.20.41 targets NeoForge on Minecraft 1.20.4 and has no
  official Fabric artifact.

These decisions are recorded in `modpacks/catalog.tsv` so a future nested-pack,
cross-version, or NeoForge trajectory can revisit them without weakening the
current pack contract.

## Pinned dependency closure

| Project | Version | SHA-512 | Metadata surface |
| --- | --- | --- | --- |
| Tech Reborn | 5.10.4 | `f82a1f02…65fe0` | main/client/REI entrypoints, access widener, two nested JARs |
| Reborn Core | 5.10.4 | `04578f19…0fd51` | main/client entrypoints, two mixin declarations, access widener, nested Energy API |
| Fabric API | 0.97.3+1.20.4 | `ea1e4767…4f108` | aggregate provider with 50 nested Fabric API modules |

All are represented by Packwiz metafiles under `modpacks/tech-reborn/`; no JAR
is stored in source. Tech Reborn and Reborn Core are MIT-licensed. Fabric API is
Apache-2.0.

## Preflight evidence

Command:

```sh
MINOSOFT_MODPACK_STORE=/tmp/minosoft-tech-reborn \
./play.sh modpack inspect tech-reborn \
  --trajectory tech-reborn-discovery-2026-07-21
```

Immutable view:
`/tmp/minosoft-tech-reborn/packs/tech-reborn/83bb91d3ba7d823159be4469c34d20e48c6cdf988a98ad991b1c78581947c59a`.

The initial report parsed three top-level mods and returned
`activation=blocked`:

- Reborn Core: activation adapter, entrypoint linkage, mixins, access widener,
  and nested-JAR blockers.
- Tech Reborn: activation adapter, entrypoint linkage, access widener, and
  nested-JAR blockers.
- Fabric API: activation adapter and nested-JAR blockers.

That initial result protected client launch while the graph and host contracts
were incomplete. The implementation then added recursive nested-provider
resolution, exact metadata adapters, and transactional host registries.

## Activation evidence

Focused checks:

```sh
./gradlew test \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest \
  --tests de.bixilon.minosoft.modding.loader.fabric.FabricTechCapabilitiesTest
./play.sh modpack inspect tech-reborn --trajectory activation-2026-07-21
```

Both passed. The immutable view was
`05ad32456c22ccb2dc60e49216581cab325dcd4c7fb571b3bb2594e93818e033`.
Preflight returned `activation=adapted` for all three top-level artifacts with
empty blocker and dependency-issue sets, ordered Fabric API, Reborn Core, then
Tech Reborn. Recursive discovery observed 50 Fabric API modules, the Reborn
Core Energy API, and three nested providers beneath Tech Reborn.

Live command:

```sh
./play.sh start both --modpack tech-reborn \
  --trajectory activation-2026-07-21
```

The vanilla 1.20.4 server reached ready state and the client stayed live under
the Java parent. Runtime evidence was:

- `FABRIC_API_MODULES_ACTIVE version=0.97.3+1.20.4 modules=50`
- `REBORN_ENERGY_ACTIVE api=team_reborn_energy version=3.0.0`
- `TECH_REBORN_CONTENT_ACTIVE resources=4320 blockstates=310 models=1226 recipes=2474 worldgen=36`
- `FABRIC_PACK_ACTIVE ... mods=fabric-api, reborncore, techreborn`
- `FABRIC_PACK_INACTIVE ...` during parent shutdown

After `./play.sh stop both`, both managed processes reported stopped. The fresh
trajectory remained in vanilla asset verification during the observation
window, so this run does not claim a completed server join. More importantly,
the server is deliberately vanilla: no Tech Reborn gameplay bytecode, registry
sync, machines, recipes, or payloads executed.

## Capability trajectory

The first and fourth items below now have a local-authority implementation; see
[Tech Reborn source-native registry and world generation](2026-07-21-tech-reborn-worldgen.md).
They remain open for external-server, persistence, and full gameplay semantics.

The next useful work is not another exact-version adapter. Establish these
general host contracts in order:

1. Extend the current owned content catalog into transactional registry epochs
   for blocks, items, fluids, recipes, menus,
   world generation, and their assets.
2. Extend bounded energy storage with network transfer, sided access,
   persistence, invalidation, and Team Reborn conformance fixtures.
3. Add scoped custom-payload networking and synchronized machine/container
   state.
4. Run matching mod logic on an authoritative server before accepting gameplay.
5. Only then map rendering, reload, persistent-state migration, and integration
   scenarios.

## References

- [Tech Reborn source](https://github.com/TechReborn/TechReborn)
- [Tech Reborn on Modrinth](https://modrinth.com/mod/techreborn)
- [RealisticCraft on Modrinth](https://modrinth.com/modpack/realisticcraft)
- [Mekanism source](https://github.com/mekanism/Mekanism)
