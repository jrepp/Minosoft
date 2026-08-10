<!-- Copyright (C) 2026 Jacob Repp -->

# Distant-terrain near-mask macOS reference images

These images are platform-qualified framebuffer references for enabled and
disabled distant-terrain presentation at one fixed near/far boundary. They are
not portable golden images for another GPU, operating system, framebuffer size,
world, camera, or terrain residency state.

Capture identity:

- captured 2026-08-03 with Java 25.0.1 and Minecraft 1.20.4;
- macOS on Apple M4 Max, OpenGL 4.1;
- trajectory `diverse-medium-biomes-2026-08-01`;
- overworld pose `(32.6740054977249, 78.0, -608.6999999880791)`, yaw
  `115.75401`, pitch `4.5429916`;
- Iris persistently disabled;
- physical framebuffer `3456x1910`, time 6000, clear weather, and transient
  GUI, HUD, hitboxes, clouds, world border, entities, and particles hidden;
- pixel threshold `12`, maximum changed ratio `0.05`, and maximum mean error
  `3.0` after the near and distant queues reach the scenario's idle boundary.

References:

| File | Crop | SHA-256 |
| --- | --- | --- |
| `dh-enabled-distant-macos-retina-3456x1910.png` | `[0,400,700,700]` | `ce994aeab0be67e8fcb4944b58a7a0cddf0a9d3e88b19d7023611b78505196e0` |
| `dh-enabled-foreground-macos-retina-3456x1910.png` | `[700,300,2756,1400]` | `327f4c636f0924a53e05c0dd562306a2dea1904219444f54d597f5a01d234e74` |
| `dh-disabled-distant-macos-retina-3456x1910.png` | `[0,400,700,700]` | `9763ee58270bbb71e826eae7fe52681a3554cd69b6b0575fca0acd9623c3aa2d` |
| `dh-disabled-foreground-macos-retina-3456x1910.png` | `[700,300,2756,1400]` | `e6e3be8c98ad9485f2f6c9a16c4d5fd09b8b45915e12aa21f2791588239d8bde` |

The accepted capture runs were
`terrain-dh-near-mask-diverse-medium-2026-08-03T15-25-51-202689Z-18438`
and
`terrain-dh-near-mask-diverse-medium-2026-08-03T15-30-58-037349Z-20337`.

Run the managed scenario only after verifying that the exact trajectory, pose,
world state, and framebuffer are active:

```sh
./play.sh scenario run \
  acceptance/scenarios/terrain-dh-near-mask-diverse-medium.json \
  --trajectory diverse-medium-biomes-2026-08-01 --json
```

The foreground crops intentionally describe the whole near/far composition and
currently include first-person presentation pixels. At the next intentional
recapture, suppress those pixels or narrow the crop before changing tolerance;
they are not evidence of distant-terrain correctness by themselves.

Only use `--update-screenshots` after reviewing an intentional renderer,
camera, platform, world, or baseline-identity change. A normal scenario run
never writes these files.
