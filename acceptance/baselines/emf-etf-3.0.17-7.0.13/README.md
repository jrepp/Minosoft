<!-- Copyright (C) 2026 Jacob Repp -->

# EMF 3.0.17 and ETF 7.0.13 macOS reference image

This image is a platform-qualified framebuffer reference for Minosoft's
bounded living-entity fixture. The fixture composes a one-part OptiFine CEM
replacement into the native zombie model, selects ETF rule 1 from
`nbt.1.Health=20`, and drives the replacement rotation with EMF's public
`rule_index` expression variable.

Capture identity:

- macOS 26.2 on Apple M4 Max (32-core GPU, Metal 4);
- Minosoft Java 17 local void world, seed `6072333650475958863`;
- Minecraft `1.20.4`, `content-fidelity` managed pack;
- EMF `3.0.17` and ETF `7.0.13`;
- physical framebuffer `3456x1910`;
- top-left crop `[1350,400,750,1150]`;
- transient GUI overlays, HUD, entity hitboxes, and moving clouds disabled;
- three seconds allowed for spawn-light interpolation to settle;
- exact comparison: pixel threshold `0`, changed ratio `0`, mean error `0`.

Reference:

| File | Scene | SHA-256 |
| --- | --- | --- |
| `zombie-rule-1-macos-retina-3456x1910.png` | Native zombie with ETF-selected, CEM-composed left-arm pose | `544dd71095d38df6d0b83302116e7c3cfd3824c10653651997659eaa2bd3828f` |

Launch the supervised client, wait for `client.render-ready`, then run:

```sh
./play.sh scenario run \
  acceptance/scenarios/emf-etf-zombie-render-reference.json \
  --trajectory emf-etf-render-reference --json
```

The scenario mounts and executes the managed resource/data fixture, verifies
the native `ZombieRenderer`, compares the settled scene, rejects real content
transactions after upload and after publication, compares after both
rollbacks, accepts a recovery reload, compares again, and removes the zombie.
Each rejected candidate must retire every created OpenGL object with zero live
delta.

The three-second settle is intentional. Adjacent captures taken immediately
after summon can differ while the entity light interpolator approaches the
world light. Once settled, adjacent crops are pixel-identical. That transition
is not z-fighting and must not be hidden by weakening screenshot tolerance.

Only use `--update-screenshots` after reviewing an intentional fixture,
renderer, camera, platform, or baseline-identity change. A normal run never
writes this file.
