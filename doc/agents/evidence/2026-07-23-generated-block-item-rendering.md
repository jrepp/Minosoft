<!-- Copyright (C) 2026 Jacob Repp -->

# Generated block-item rendering evidence

## Failure

The crafting result for a ladder rendered above and to the left of its output
slot. Minecraft defines the ladder inventory appearance as a generated item
model backed by `minecraft:block/ladder`, while its world model is a thin quad
positioned against a block face.

`ItemLoader` previously discarded every dedicated item model for a block item
when a world block model was already present. The GUI therefore consumed the
ladder's world geometry and applied block-item transforms to it.

## Rendering contract

An explicit item model is now attempted for block items as well as non-block
items:

- If the item model produces generated layers, its flat item prototype replaces
  the world block fallback.
- If the item model only inherits block geometry and produces no item layers,
  loading returns no replacement and the existing 3D block renderer remains.
- Built-in entity item models retain their existing fallback behavior.

This makes the behavior data-driven and covers other block items with dedicated
generated inventory models without adding item-specific exceptions.

## Automated acceptance

The renderer regression asserts that `minecraft:ladder` receives
`FlatItemRender`, while `minecraft:oak_planks` continues to use its block-item
renderer.

```text
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test :integrationTest --console=plain
```

Result:

- unit: 1450 tests, 0 skipped, 0 failures, 0 errors
- integration: 2014 tests, 115 skipped, 0 failures, 0 errors

## Live acceptance

The running client hot-reloaded generation 16 without restarting the server.
Using the client debug pipe, the acceptance trajectory:

1. sampled the nearby area and located the crafting table at
   `(30, 69, 18)`;
2. opened the crafting table through injected client input;
3. placed sticks in the seven ladder-recipe slots;
4. sampled framebuffer frame 3721.

The Faithful ladder icon was centered and contained by the result slot, with
the result count anchored at the lower right. The client remained in the
playing state with the Fabric mod stack active, and the server process remained
stable.
