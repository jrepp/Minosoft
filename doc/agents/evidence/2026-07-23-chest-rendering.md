<!-- Copyright (C) 2026 Jacob Repp -->

# Chest item and placement rendering evidence

## Scope

This evidence closes the 1.20.4 failure in which crafted chest items rendered
as white rectangles and newly placed chests remained invisible until a client
reload.

## Root causes

Two independent rendering assumptions combined into the user-visible failure:

1. `ItemLoader` intentionally leaves block items to their block model. Chests,
   trapped chests, and ender chests use `builtin/entity` semantics and have no
   normal baked block faces, so the fallback supplied no usable item renderer.
2. `ChunkRendererChangeListener.canIgnore` treated a transition whose previous
   and current baked models were both absent as visually irrelevant.
   Entity-backed blocks can legitimately have no baked block model while still
   contributing an entity renderer to the section mesh, so chest placement was
   skipped.

## Corrected contracts

- Chest renderer registration now installs a `ChestItemRender` for the matching
  item. It uses the same resolved entity texture as the skeletal world renderer,
  so active resource-pack overrides also reach inventory, held, and dropped
  chest items.
- The compatibility item renderer composes body, lid, and lock regions from the
  64×64 entity texture. This is a textured 2D item representation until the GUI
  render path can host skeletal model instances.
- Placement, removal, or state replacement involving an `ENTITY` block state
  always invalidates the section mesh. Only an unchanged entity-backed state may
  reuse the mesh.

## Automated evidence

Focused integration tests:

```sh
env -u NO_COLOR \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
  ./gradlew :integrationTest \
    --tests de.bixilon.minosoft.gui.rendering.chunk.util.ChunkRendererChangeListenerTest \
    --tests de.bixilon.minosoft.gui.rendering.chunk.entities.renderer.storage.chest.ChestItemRenderTest \
    --console=plain
```

Result: `BUILD SUCCESSFUL`; five focused checks passed. They cover entity-block
placement/removal/replacement invalidation, unchanged-state reuse, and the
resource texture plus UV regions used by the chest item renderer.

Broad regression:

```sh
env -u NO_COLOR \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
  ./gradlew :test :integrationTest --console=plain
```

Result after the generalized item-render work: `BUILD SUCCESSFUL in 20s`;
1,450 unit tests and 2,013 integration tests executed with zero failures or
errors. The integration suite reported 116 skipped cases.

## Live acceptance

The `debug-control-plane` trajectory hot-reloaded the fix as generation 10:

- supervisor PID `35417`;
- Fabric server PID `35639`;
- client PID `63804`;
- client endpoint `client-63804-10-61962509`;
- Minecraft `1.20.4`;
- active five-mod `minosoft_fabric_stack`.

The server PID remained stable during the entire check. Using only debug input,
framebuffer capture, client state, and AOI sampling:

1. Frame 3514 selected an existing chest and showed both adjacent chests with
   their Faithful-compatible entity textures.
2. The selected chest was harvested. Frame 3684 showed the dropped chest with a
   textured body, lid, and lock rather than a white quad.
3. The player picked it up. Frame 3861 showed a textured chest in hotbar slot 2.
4. The chest was selected and placed back into the sampled area. Client AOI
   immediately changed from one chest state to two chest states.
5. The placement opened normally, and frame 4477—still on client PID
   `63804`—showed the newly placed chest textured and visible in the world.

Because no process-generation change occurred between harvest and the final
frame, the placed-chest result verifies update-driven remeshing rather than a
full reload masking the failure.

The final client remained ready and playing. The live log contained no new
fatal error or `GL_INVALID_*` event during this sequence. Existing finalizer
`MemoryLeakException` warnings remain a separate graphics-lifecycle diagnostic;
they predate and are not introduced by the chest model or invalidation paths.

## Stable learning

Absence of a baked block model is not proof that a block-state transition has no
visual effect. Render invalidation must account for alternate render ownership,
including block entities. Likewise, block-item fallback is only valid when the
block actually supplies a baked model; `builtin/entity` families need an
explicit item-render bridge until skeletal GUI rendering exists.

## Generalized block-item audit

The chest diagnosis was expanded across the Minecraft 1.20.4 item-model assets.
The client now treats `minecraft:builtin/entity` as a model sentinel rather than
a missing JSON file, preserves explicit item-render priority over same-id block
models, and records structured coverage after dynamic renderers register.

The loader and special-render tests cover particle-only models with and without
an explicitly requested compatibility fallback, chest texture-region
composition, shulker texture-region composition, and entity-backed section
invalidation.

Generation 15, endpoint `client-2133-15-896d6a4d`, reported:

```text
BLOCK_ITEM_ENTITY_RENDER_AUDIT precise=20 fallback=41 unresolved=0
SPECIAL_ITEM_ENTITY_RENDER_AUDIT precise=0 fallback=1 unresolved=0
```

The 61 affected block items are:

| Family | Count | Current handling |
| --- | ---: | --- |
| Chest, trapped chest, ender chest | 3 | Precise resource-pack-aware body/lid/lock renderer |
| Uncolored plus dyed shulker boxes | 17 | Precise resource-pack-aware body/lid renderer |
| Dyed beds | 16 | Visible particle-texture compatibility fallback; dedicated geometry remains unmapped |
| Dyed banners | 16 | Visible particle-texture compatibility fallback; cloth/pattern renderer remains unmapped |
| Player/mob heads and skulls | 7 | Visible particle-texture compatibility fallback; profile/mob geometry remains unmapped |
| Conduit | 1 | Visible particle-texture compatibility fallback; animated entity component remains unmapped |
| Decorated pot | 1 | Visible particle-texture compatibility fallback; sherd-aware geometry remains unmapped |

`ItemLoader` applies the compatibility fallback only when the resolved item
model actually inherits `builtin/entity`; normal crafted block items retain
their baked 3D block model. A second `BLOCK_ITEM_RENDER_MISSING` diagnostic lists
any block item that has neither a normal/explicit renderer nor a usable
particle-texture fallback. This is namespace-agnostic, so mounted mod assets are
audited by the same path.

The crafted shield is the one registered non-block item whose primary 1.20.4
model also inherits `builtin/entity`; it now receives the same visible
particle-texture fallback and its own structured audit. The trident's primary
inventory model is `item/generated`, so it does not share this inventory
failure, although its in-hand predicate variants use special entity models that
remain outside Minosoft's predicate handling.

The active five-mod Fabric stack currently contains no mod that adds its own
crafted blocks. For block-adding packs, source-native JSON block/item models
continue through the normal loader, `builtin/entity` items receive the visible
fallback, and custom Fabric model loaders or block-entity renderers remain
explicit integration work rather than being reported as fully supported. That
hook family is recorded in the Fabric backlog with hot-add, reload, unload,
section-remesh, and resource-lifetime acceptance gates.

Generation 15 remained ready and playing on Minecraft 1.20.4 after the final
hot reload. The Fabric server remained on PID `35639`, generation 1, and the
five mounted client adapters remained active. No
`BLOCK_ITEM_RENDER_MISSING` diagnostic was emitted, and no new
`minecraft:builtin/entity` missing-model warning occurred after the sentinel
handling was installed.
