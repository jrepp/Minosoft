<!--
 Minosoft
 Copyright (C) 2026 Jacob Repp

 This program is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free Software
 Foundation, either version 3 of the License, or (at your option) any later
 version.

 This program is distributed in the hope that it will be useful, but WITHOUT
 ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Distant-terrain cardinal-view macOS references

These images are platform-qualified framebuffer references for four rotations
at one fixed camera position. They are not portable golden images for another
GPU, operating system, framebuffer size, world, camera, content stack, or
terrain residency state.

Capture identity:

- captured 2026-08-09 local time (2026-08-10 UTC) with Java 25.0.4 and
  Minecraft 1.20.4;
- macOS on Apple M5 Max, OpenGL 4.1 Metal 90.5;
- isolated trajectory `terrain-distant-view-baseline-2026-08-09` and regenerated
  debug local world seed `6072333650475958863`;
- standalone content stack fingerprint `a532d878f7c8`, 16,796 processed files
  from 12 sources, and zero acceptance content fixtures;
- built-in presentation selected through a non-persistent Iris disable, with
  distant terrain enabled through a non-persistent override;
- physical framebuffer `1800x1000`, camera `(80.5,60.0,80.5)`, pitch `30`, and
  north/east/south/west yaws `0/-90/180/90`;
- fixed time 6000, clear weather, and transient GUI, HUD, hitboxes, clouds,
  world border, entities, and particles hidden;
- source crop `[100,350,1150,550]`, pixel threshold `4`, maximum changed ratio
  `0.02`, and maximum mean error `1.0`.

References:

| File | Output size | SHA-256 |
| --- | --- | --- |
| `north-built-in-macos-1800x1000.png` | `1150x550` | `bd83ad351f93d0040cda5d89dad4d6e21501aba8a1c625a082420bb573f22809` |
| `east-built-in-macos-1800x1000.png` | `1150x550` | `c89ae696c7acac977343aa9657daad44d30ed7db7c768570b010ed1a1c42a06a` |
| `south-built-in-macos-1800x1000.png` | `1150x550` | `23c9d034146a21306f1bc8bdb857b989100018441cb2afa33819d0f65c9dca6b` |
| `west-built-in-macos-1800x1000.png` | `1150x550` | `0764d3a761ebfcb7bc294143c2ca7f1bf0e4ccfaff4b8ec85eb6c75b064931c6` |

The accepted baseline-writing run was
`terrain-distant-cardinal-views-2026-08-10T03-15-50-520370Z-17438`.
Compare-only runs
`terrain-distant-cardinal-views-2026-08-10T03-17-06-886785Z-18194` and
`terrain-distant-cardinal-views-2026-08-10T03-19-10-901703Z-19404` passed; the
latter used a newly launched client process and endpoint.

The crop deliberately excludes the asynchronously hydrated far-horizon
silhouette, which is not a process-stable pixel oracle. Distant-rendering
correctness remains part of every cardinal step: the scenario reaches the full
terrain idle boundary, then requires nonzero main and shadow draw pages and
zero missing pages. The pixels freeze the stable rendered ground and cardinal
camera transform.

Run the managed scenario only after verifying the exact trajectory and
framebuffer identity:

```sh
./play.sh scenario run \
  acceptance/scenarios/terrain-distant-cardinal-views.json \
  --trajectory terrain-distant-view-baseline-2026-08-09 --json
```

Only use `--update-screenshots` after reviewing an intentional renderer,
camera, platform, world, or baseline-identity change. A normal scenario run
never writes these files.
