<!-- Copyright (C) 2026 Jacob Repp -->

# Screenshot capability and terrain-state diagnosis

## Result

The three-state/four-phase terrain alternation was not introduced by terrain
snapshotting, worker scheduling, vertex publication, or visibility. The normal
`fabric-stack` trajectory had mounted `iris-labpbr-render`, an intentionally
animated checked-pixel fixture whose constant diffuse sand independently
cycles neutral, normal-only, specular/emissive-only, and combined LabPBR
companion frames every 20 ticks.

That fixture remains available under integration-test resources, but
`modpacks/fabric-stack/fixtures.tsv` no longer mounts it during ordinary play.
The index and pack hashes were updated transactionally. The Naturalist
controller fixture remains mounted.

## Screenshot contracts

The standard user control is F2 through `DefaultKeyBindings.SCREENSHOT`.
`ScreenshotTaker` reads the final framebuffer on the render path, then writes
the PNG asynchronously to:

```text
<active game home>/screenshots/yyyy-MM-dd_HH.mm.ss[_N].png
```

There is no connection/server subdirectory. Same-second collisions receive the
vanilla-style numeric suffix, and the existing clickable save/delete chat
message is unchanged.

`visual.capture` now uses the same `ScreenshotTaker.capture` boundary. Its
attachment metadata includes dimensions, frame, capture time, SHA-256,
suggested filename, user screenshot directory, `rgba8`, and `top-left`.
`./play.sh debug visual capture` may omit its output path; the CLI then writes a
collision-safe file under:

```text
.run/agent-screenshots/<endpoint trajectory>/
```

The CLI hashes the written file and requires it to match endpoint metadata.
Supplying an explicit output path remains supported.

## Live evidence

The supervised `complementary-unbound-blend-2026-07-28` trajectory accepted the
pack change and rebuilt a joined/render-ready client without replacing its
parent or server. Complementary Unbound r5.8.1 remained selected with
`RP_MODE=3;SHADOW_QUALITY=1`.

An injected normal-path F2 press wrote
`2026-07-28_17.52.54.png` directly beneath the reported game-home screenshot
directory. A no-output debug capture wrote beneath the selected trajectory
rather than the CLI's default trajectory and reported identical endpoint and
post-write SHA-256 values.

After clearing transient GUI state and restoring the reference pose, six
one-second 256×256 sand-region samples at frames 6203–6662 all reported an empty
`materialAnimationStates` array. Average luminance changed smoothly from
0.59199 to 0.59789 with advancing daylight; the former discrete repeating
LabPBR fixture phases were absent.

## Validation

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.util.ScreenshotTakerTest
./gradlew :play-util:installDist
./play.sh debug visual capture \
  --role client \
  --trajectory complementary-unbound-blend-2026-07-28 \
  --json
```

Both focused screenshot tests passed. The first combined Gradle invocation also
passed those tests and installed the play utility, but the build as a whole
reported failure because a root `--tests` filter propagated into `debug-core`,
which has no class by that name; the correctly scoped rerun passed.

## Durable boundary

Animated material fixtures are acceptance content and must be mounted only by
an explicit acceptance trajectory. Ordinary modpack definitions must not carry
time-varying checked-pixel fixtures. User screenshots belong to the active game
home; agent artifacts belong to repository runtime state and must retain the
endpoint trajectory plus a verified content hash.
