<!-- Copyright (C) 2026 Jacob Repp -->

# Terralith Fabric world-generation evidence

Date: 2026-07-23

## Artifact and pack boundary

`modpacks/fabric-stack` version `0.5.0` pins Modrinth version `zA1qYB0L`
(`Terralith_1.20.4_v2.4.11_FABRIC.jar`) as a server-only artifact. Its declared
SHA-512 is:

```text
563e21b485d5ab38655712c5ce6f254d3c25e6661f2c55ec89cbeaf1389a826255790d5d6d4fd84476f23e02918f80e4d0af8b25e47b7420252b927ba884dbc8
```

The resolved server copy matched that digest. Its `fabric.mod.json` identifies
`terralith` version `2.4.11`, and its archive contains the expected
`data/terralith/worldgen/biome` resources. Because the entry is `side =
"server"`, it is installed by `Play.prepareFabricServer` but remains outside the
Minosoft client compatibility/preflight classpath. The seven existing client
adapters continue to resolve independently.

## Recoverable regeneration

`server.properties` names `world-fresh-2026-07-21` as the active level. After a
graceful supervised shutdown, that exact 23 MB directory was moved to:

```text
server/backups/world-fresh-2026-07-21.pre-terralith-2026-07-23-1845
```

The stale sibling `server/world` was not touched. Restart created a new
`server/world-fresh-2026-07-21` from no existing world data.

## Runtime acceptance

Supervised restart activated:

- parent PID `32002`;
- Fabric server PID `32060`;
- Minosoft client PID `32219`.

The server log and debug endpoint established the complete loading path:

1. Fabric Loader's startup inventory included `terralith 2.4.11`.
2. Startup reported `Found new data pack terralith, loading it automatically`.
3. Startup reported `No existing world data, creating new world`.
4. Datapack loading reported 163 new biomes before overworld spawn preparation.
5. Spawn preparation completed and the client joined the new world.
6. `mods.debug` independently returned Terralith 2.4.11 from the live server's
   `FabricLoader.getAllMods()` inventory.

The generated Anvil region files provide the final world-generation assertion.
An NBT scan read 625 newly generated chunks; all 625 contained at least one
`terralith:*` biome palette entry. Observed namespaces included:

- `terralith:forested_highlands`
- `terralith:highlands`
- `terralith:yellowstone`
- `terralith:cave/andesite_caves`
- `terralith:cave/deep_caves`
- `terralith:cave/mantle_caves`
- `terralith:cave/thermal_caves`
- `terralith:cave/tuff_caves`

This proves more than artifact staging: the dedicated Fabric server loaded the
Terralith datapack before new-world creation, and the resulting chunk biome
containers reference Terralith registry keys.
