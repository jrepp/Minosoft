<!-- Copyright (C) 2026 Jacob Repp -->

# EMF/ETF living-entity render reference evidence

## Outcome

The exact EMF `3.0.17` and ETF `7.0.13` adapters now have one checked
production-render integration lane. A managed 1.20.4 fixture:

- selects ETF rule 1 with `nbt.1.Health=20`;
- exposes that selection to EMF as the pinned public variable `rule_index`;
- replaces only the aliased native zombie `left_arm`;
- keeps the remaining native zombie parts;
- executes through the normal local data-pack runtime;
- renders through `ZombieRenderer` and the retained skeletal path;
- survives rejected upload/publication transactions and an accepted recovery;
- removes through the still-mounted data pack.

This is a bounded positive reference, not broad OptiFine, EMF, or ETF parity.
Non-skeletal/block-entity ETF features, the full entity alias catalog,
configuration, non-world CEM paths, other drivers, and remote servers remain
separate gates.

## Pinned contract correction

The first live probe used ETF's predicate-context spelling `texture_rule` in a
CEM expression and failed closed with `Unknown expression variable`. The
staged EMF `3.0.17` bytecode (`VariableRegistry`) registers `rule_index`;
Minosoft's `CemExpressionVariableCatalog` and
`CemEntityExpressionContextFactory` already matched that exact public name.
The fixture was corrected instead of adding a compatibility alias that the
pinned producer does not expose.

ETF's `textureRule`/`texture_rule` name remains valid only inside ETF property
condition chaining. Keeping those two expression domains separate prevents a
fixture from manufacturing unsupported EMF syntax.

## Managed fixture

`src/integration-test/resources/content_fidelity/emf-etf-zombie-render/`
contains three resource files and three data files:

- `optifine/cem/zombie.jem`, with one `leftArm` replacement and
  `this.rx = rule_index * 0.65`;
- `optifine/random/entity/zombie/zombie.properties`, selecting suffix/rule 1
  when health is 20;
- load/remove functions that summon a tagged, fixed-position, no-gravity
  zombie and remove it again.

The fixture reuses the vanilla zombie texture. It does not copy a third-party
texture or invent a Gecko route. `fixtures.tsv` pins the complete sorted
resource/data manifest, and `Play` validates and stages it out of source beside
the exact Animated Java export.

`LocalDisplayEntityFactory` admits `minecraft:zombie` as the first explicit
bounded living fixture type and maps standard `NoGravity` NBT into native
entity data. This is not an unrestricted local summon registry.

## Headless verification

`ContentFidelityMultiVersionTest.render fixture binds zombie CEM pose to the
selected ETF rule` verifies:

- the production zombie alias maps `leftArm` to `left_arm`;
- the expression evaluates to `0.65` when `rule_index=1`;
- the ETF properties retain suffix 1 and `nbt.Health=20`.

`LocalDisplayEntityFactoryTest.summons the bounded living content fidelity
fixture` verifies the entity type, command NBT/tag retention, and no-gravity
state. Both tests passed on Java 17 before the OpenGL run.

## Real-OpenGL reference

The checked scenario is
`acceptance/scenarios/emf-etf-zombie-render-reference.json`. On macOS 26.2,
Apple M4 Max (32-core GPU, Metal 4), framebuffer `3456x1910`, it compares the
top-left crop `[1350,400,750,1150]` against
`acceptance/baselines/emf-etf-3.0.17-7.0.13/` with zero pixel threshold,
changed ratio, and mean error.

The scene waits three seconds after summon. Immediate adjacent captures can
change while `LivingEntityRenderer`'s light interpolator approaches the world
light; after settling, adjacent crops were pixel-identical. The composed model
has one retained skeletal feature, replaces only the targeted part, and showed
no competing body geometry. The observed initialization tint was not
z-fighting. `visual.prepare-reference` also suppresses non-persistent hitbox
and moving-cloud rendering so the crop measures the entity rather than debug
or sky animation.

Two normal-path records are:

- baseline-authoring review:
  `emf-etf-zombie-render-reference-2026-07-26T07-37-51-181269Z-78357`,
  passed in 6.117 seconds;
- read-only baseline validation:
  `emf-etf-zombie-render-reference-2026-07-26T07-38-14-126247Z-78598`,
  passed in 6.103 seconds.
- supervised source/manifest replacement, client generation 4:
  `emf-etf-zombie-render-reference-2026-07-26T07-52-26-876792Z-86906`,
  passed in 6.214 seconds with all four comparisons exact.
- final combined-fixture completion audit:
  `emf-etf-zombie-render-reference-2026-07-26T22-58-25-400271Z-58608`,
  passed in 6.840 seconds with both rejection checkpoints and all four
  comparisons exact.

Raw reports remain untracked under `.run/acceptance/`.

An intervening generation-3 run correctly failed because moving clouds entered
the crop after process replacement even though the zombie pixels were
unchanged. That failure established `hideClouds` as part of the bounded
`visual.prepare-reference` contract. The accepted generation-4 run suppresses
the cloud pass without writing the rendering profile. A later completion run
also established that macOS focus loss can reopen a pause/settings screen
between captures; the scenario now repeats the bounded preparation immediately
before every checked screenshot.

In the read-only run, both rejection points preserved generation 3 and exact
pixels. Each candidate created and deleted 33 OpenGL objects with zero live
delta:

| Type | Created | Deleted | Live delta |
| --- | ---: | ---: | ---: |
| Buffer | 21 | 21 | 0 |
| Vertex array | 11 | 11 | 0 |
| Texture | 1 | 1 | 0 |

The accepted recovery published normally, the recovered crop remained exact,
and the remove function left only the local player.

## Blockbench boundary

This fixture validates the Minosoft consumer side used by Blockbench-authored
CEM/ETF resources; it is not itself evidence that a specific Blockbench plugin
exported those files. Producer acceptance still requires an isolated real
Blockbench export with pinned host, plugin, project, settings, and output
manifest as defined by the
[Blockbench producer-integration protocol](../acceptance/blockbench.md).

## Remaining gates

1. Add a real producer-captured CEM/ETF Blockbench fixture when a specific
   exporter/plugin identity is selected; do not relabel this hand-authored
   bounded fixture as producer evidence.
2. Add independent emissive/blink/variant visual references and non-skeletal
   ETF feature bindings.
3. Expand EMF aliases, special entity/limb behavior, attachments, non-world
   render paths, diagnostics, and configuration.
4. Validate the separate Naturalist/Gecko route on a negotiated remote registry
   instead of inventing an ungrounded local entity filename convention.
5. Repeat the checked reference on another driver/platform and on a remote
   1.20.4 server.
