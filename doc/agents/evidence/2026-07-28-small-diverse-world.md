<!-- Copyright (C) 2026 Jacob Repp -->

# Small diverse-biome world

## World contract

The dedicated Fabric server now selects:

```text
level-name=world-diverse-small-2026-07-28
level-seed=17364287501928473
```

The `fabric-stack` server loaded Terralith 2.4.11 and Tectonic 2.3.5b. Startup
discovered `terralith`, `tectonic/terratonic`, Naturalist, Fabric, and the
world-owned `file/minosoft-small-world` datapack before reporting that it was
creating new world data.

The small-world datapack establishes a persistent scoreboard flag. On the first
load it waits until the first player reaches Minecraft's naturally selected
spawn, then:

1. centers the world border at that player;
2. sets the border diameter to 1,024 blocks;
3. makes the same safe position the durable world spawn;
4. sets the spawn radius to eight blocks; and
5. records completion so later server loads do not move the border.

This bounds exploration without assuming that `(0,0)` is land.

## Origin failure and recovery

The first generation forced spawn and border center to `(0,0)`. A server block
sample proved the surrounding checked columns ended at Y=62 water, while the
player descended underwater around Y=43. The captured frame confirmed the bad
location. That complete 11 MB attempt was moved, after a graceful supervisor
shutdown, to:

```text
server/backups/world-diverse-small-2026-07-28.origin-ocean-attempt
```

No older world directory was moved or overwritten.

The corrected same-seed generation used Minecraft's natural spawn at
`(-794.5, 91, 614.5)`. A 4×8×4 authoritative block sample around the player
contained stone, dirt, grass, rooted dirt, coarse dirt, moss, fern, short grass,
and open air. The checked screenshot shows a valid lush coastal cliff rather
than an underwater or embedded camera.

## Diversity and size evidence

The same-seed initial Anvil scan completed before it was backed up:

- 1,154 readable chunks, including 626 full chunks;
- no failed chunks;
- surface heights from Y=62 through Y=142;
- Minecraft plains, sparse jungle, deep/lukewarm oceans, lush caves, and deep
  dark;
- Terralith granite and mantle cave families.

The corrected generation has the same seed and worldgen datapack set. A live
inspection retry was not accepted as artifact evidence because it raced an
active region write and ended with `EOFException`; no claim is based on that
failed live read.

The synchronized client border was tested through a reversible canary. Before
the canary it reported center `(-794.5, 614.5)` and radius `512`; restoration
returned exactly those values.

## Screenshots and lifecycle

Agent reference:

```text
.run/agent-screenshots/diverse-small-biomes-2026-07-28/2026-07-28_18.11.48.png
```

User F2 screenshot:

```text
<trajectory game home>/screenshots/2026-07-28_18.13.06.png
```

The `diverse-small-biomes-2026-07-28` parent, server, and client were left
running with `both.ready`, one joined creative player, and the corrected world.
