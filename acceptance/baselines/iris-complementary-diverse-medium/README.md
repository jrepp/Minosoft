<!-- Copyright (C) 2026 Jacob Repp -->

# Complementary Unbound terrain macOS reference image

This image is a platform-qualified framebuffer reference for the exact
Complementary Unbound r5.8.1 terrain pipeline. It is not a portable golden image
for another GPU, operating system, framebuffer size, shader pack, or option set.

Capture identity:

- captured 2026-08-03 with Java 25.0.1 and Minecraft 1.20.4;
- macOS on Apple M4 Max, OpenGL 4.1;
- trajectory `diverse-medium-biomes-2026-08-01`;
- overworld pose `(32.6740054977249, 78.0, -608.6999999880791)`, yaw
  `115.75401`, pitch `4.5429916`;
- Complementary Unbound r5.8.1 fingerprint
  `385d3c1777dc297dde25dc7e5fec55234f593f824a896fd21eca3cde3e6c38a8`;
- distant-terrain presentation disabled;
- physical framebuffer `3456x1910`, time 6000, clear weather, and transient
  GUI, HUD, hitboxes, clouds, world border, entities, and particles hidden;
- crop `[1900,450,1000,900]`, pixel threshold `12`, maximum changed ratio
  `0.05`, and maximum mean error `3.0`.

Reference:

| File | SHA-256 |
| --- | --- |
| `static-terrain-macos-retina-3456x1910.png` | `104351a36e0828d7e94df1051028190cfe06b83c6e4555252dc29cc995b7fc6c` |

The accepted capture runs were
`iris-complementary-diverse-medium-2026-08-03T15-09-23-481151Z-12946` and
`iris-complementary-diverse-medium-2026-08-03T15-14-43-761914Z-14858`.

Run the managed scenario only after verifying that the exact trajectory, pose,
shader artifact, options, and framebuffer are active:

```sh
./play.sh scenario run \
  acceptance/scenarios/iris-complementary-diverse-medium.json \
  --trajectory diverse-medium-biomes-2026-08-01 --json
```

Only use `--update-screenshots` after reviewing an intentional renderer,
shader, camera, platform, or baseline-identity change. A normal scenario run
never writes this file.
