<!-- Copyright (C) 2026 Jacob Repp -->

# Mob rendering and launcher title evidence

Date: 2026-07-23

## Scope

This trajectory addressed two presentation gaps:

- zombies and other living entities without registered model factories were
  invisible;
- Eros opened directly into its play form instead of presenting a recognizable
  game title screen.

It preserves the direct-connect, `--no-eros`, and headless paths.

## Entity renderer diagnosis

`DefaultEntityModels` registered only players, pigs, cows, sheep, items,
falling blocks, and primed TNT. `EntityRendererManager` assigned every remaining
entity a `DummyEntityRenderer`. That dummy has no drawable model feature, so a
perfectly valid zombie in world state could remain visually absent.

The first zombie correction reused the wide player model with the vanilla
zombie texture. Live inspection then exposed a more specific failure: the
zombie's left arm and left leg were transparent. Modern player models use
separate left-limb texture regions, while the classic zombie texture populates
only the right-limb regions.

## Corrected contracts

- Zombies use `ZombieRenderer` and a dedicated six-element
  `entities/zombie/zombie.smodel`.
- The zombie model contains head, body, both arms, and both legs. Its left arm
  and leg deliberately mirror the populated vanilla right-limb UV regions.
- Zombie subclasses such as husks choose the visible zombie renderer when an
  exact factory is absent.
- Any other unmapped `LivingEntity` receives a debug-textured humanoid fallback
  instead of a model-less dummy. This is a fail-visible diagnostic fallback,
  not a claim that spiders and other non-humanoids already have correct species
  geometry.
- Unmapped non-living entities retain the existing dummy behavior.

## Minecraft-style title boundary

Eros now begins at a dedicated `TITLE` activity. Its title resource provides:

- a block-world-inspired background and large Minosoft title treatment;
- Multiplayer, Profiles & Options, Mods, and Quit Game buttons;
- routes into the existing Eros activities rather than duplicating their
  account, profile, mod, or connection logic;
- a logo-based route back to the title screen.

This remains an Eros-only presentation layer. Direct server launch and
`--no-eros` do not construct the JavaFX title controls.

## Validation

- `EntityRendererManagerTest` verifies exact zombies, a zombie variant, and an
  otherwise unmapped living mob choose drawable renderers.
- `ZombieModelResourceTest` verifies all six body elements and the mirrored
  left-limb UV contract.
- `TitleScreenResourceTest` verifies the title activity/resource and all four
  FXML action routes.
- Both new FXML resources parse with `xmllint`.
- A supervised hot reload kept parent PID `94379` and server PID `94418`
  stable while activating client PID `25771`. The client loaded and entered its
  render loop with no missing skeletal-model error.
- The post-fix framebuffer capture was taken while the pause menu faced
  terrain, so it did not contain a zombie. The corrected limb appearance is
  structurally covered but still requires a zombie to be in-frame for a final
  visual assertion.
