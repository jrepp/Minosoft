<!-- Copyright (C) 2026 Jacob Repp -->

# Terratonic Fabric world-generation evidence

Date: 2026-07-23

## Installation decision

The upstream Terratonic FAQ says the Tectonic mod automatically loads Terratonic
when the Terralith mod is installed and ensures the required priority. The
standalone Terratonic datapack is for datapack-form Tectonic/Terralith installs.
Because this trajectory already uses the Terralith Fabric mod, pack version
`0.6.0` adds server-only Tectonic `2.3.5b` rather than duplicating its embedded
compatibility pack.

`modpacks/fabric-stack/mods/tectonic.pw.toml` pins Modrinth version `GxY5oM7Y`
and SHA-512:

```text
02d7cc6d5c4a87012b28db8ce4608dff6dcfa6b97a900abcb21c28abf50676d6b37f5b7350977f04d04946408c6d41c8998158cf1dfdb1e235c95200ad71d0a5
```

The resolved server artifact matched the digest. Its Fabric entrypoint checks
`FabricLoader.isModLoaded("terralith")`, and its archive contains
`resourcepacks/terratonic` with Terratonic `3.1.1` metadata and specialized
Minecraft/Tectonic/Terralith density functions.

## Recoverable regeneration

`server.properties` still names `world-fresh-2026-07-21`. After graceful
supervisor shutdown, the complete Terralith-only world was moved to:

```text
server/backups/world-fresh-2026-07-21.pre-terratonic-2026-07-23-1906
```

The server then recreated `server/world-fresh-2026-07-21` from no existing world
data. The earlier pre-Terralith backup and unrelated `server/world` sibling were
not changed.

## Loader and datapack proof

Session `2026-07-24T05:05:33.844848Z-44429` started parent `44429`, Fabric
server `44484`, and client `44686`. The current server log established this
ordering before spawn generation:

1. Fabric Loader inventoried `tectonic 2.3.5b` and `terralith 2.4.11`.
2. Minecraft discovered `terralith`.
3. Minecraft discovered `tectonic/terratonic`.
4. Minecraft reported `No existing world data, creating new world`.
5. Spawn generation completed in 36.538 seconds.
6. The live server `mods.debug` inventory independently returned both mods.

At the time of this generation run, the supervisor emitted `server_ready`
before this run's own `Done` line because the old readiness check only accepted
a TCP connection. The later automation work now publishes `server_port_open`,
`server_debug_ready`, and `server_game_ready`; an owned Fabric server reaches
game-ready only when its exact-PID endpoint reports `core.status.ready=true`.

The new `level.dat` enables:

```text
vanilla, fabric, fabric-convention-tags-v1, terralith, tectonic/terratonic
```

The old backup has the same prefix but no `tectonic/terratonic`. This is
artifact-level proof that the compatibility pack belongs to the regenerated
world, not merely to the server mods directory.

## Terrain-shaping proof

Terralith's overworld noise settings route through the standard
`minecraft:overworld/noise_router/*` density identifiers. The enabled
Terratonic pack overrides those exact identifiers. Its active final-density
graph references:

- `tectonic:overworld/caves`
- `tectonic:overworld/legacy/cliffs`
- `tectonic:overworld/underground_river/total`
- `tectonic:constants/terralith_extra_terrain_sum`

The last reference dynamically consumes Terralith's
`terralith:overworld/extra_terrain_sum` or legacy
`terralith:extra_terrain_sum` tag member. This is the direct compatibility
join: Terralith selects biome/extra-terrain inputs while Terratonic supplies the
overworld density graph. The generated `server/config/tectonic.json` keeps the
mod enabled with terrain scale `1.125`, deeper oceans, desert dunes, lava
rivers, and underground rivers enabled.

A read-only Anvil scan then measured 1,257 full generated chunks and 321,792
surface columns:

- every chunk contained at least one `terralith:*` biome palette entry;
- surface height ranged from Y=57 to Y=270;
- median surface height was Y=113 and the 90th percentile was Y=177;
- per-chunk relief reached 79 blocks;
- adjacent-column gradients reached 50 blocks, with 3,876 edges at least
  16 blocks high;
- observed surface families included volcanic peaks, desert canyon, savanna
  badlands, rocky/tropical jungle, brushland, and hot shrubland.

The preceding Terralith-only backup has a different random seed, so its lower
height distribution is context rather than a deterministic A/B claim. The
causal assertion rests on the enabled pack, its higher-priority overrides of
the identifiers used by Terralith's noise settings, and the chunks generated
after that registry graph loaded.

The regenerated world remains live and the previous world is recoverable from
the backup path above.
