<!-- Copyright (C) 2026 Jacob Repp -->

# Animated Java 1.10.2 macOS reference images

These images are platform-qualified framebuffer references for the exact
Animated Java 1.10.2 minimal armor-stand export captured by the Blockbench
producer gate. They are not portable golden images for other GPUs, operating
systems, window sizes, UI scales, or producer versions.

Capture identity:

- macOS 26.2 on Apple M4 Max (32-core GPU, Metal 4);
- Minosoft Java 17 local void world, seed `6072333650475958863`;
- Minecraft `1.20.4`, `content-fidelity` managed pack;
- physical framebuffer `3456x1910`;
- top-left crop `[1100,900,550,550]`;
- transient GUI overlays cleared; HUD, entity hitboxes, and moving clouds disabled;
- exact comparison: pixel threshold `0`, changed ratio `0`, mean error `0`.

References:

| File | Pose | SHA-256 |
| --- | --- | --- |
| `default-pose-macos-retina-3456x1910.png` | Exported default pose after display interpolation settles | `125a9589a99dea58274b8c205d0098ee66718f481003ec045be0f1bd0b623931` |
| `walk-frame-10-macos-retina-3456x1910.png` | Static exported `walk` frame 10 after display interpolation settles | `ecc2fa1587e04951f4834885a310b0c2ae79136b762497e1e272f6fa7460ad93` |

Launch the supervised client, wait for `client.render-ready`, then run:

```sh
./play.sh scenario run \
  acceptance/scenarios/animated-java-render-reference.json \
  --trajectory animated-java-render-reference --json
```

The scenario mounts and executes the exact fixture, checks the root/passenger
and custom-model-data identities, compares the default and walk crops, reloads
content through the production transaction, compares the walk crop again, and
removes the hierarchy. It also checks typed OpenGL accounting fields.

Only use `--update-screenshots` after reviewing an intentional producer,
renderer, camera, platform, or baseline-identity change. A normal run never
writes these files.
