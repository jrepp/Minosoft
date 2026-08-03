<!-- Copyright (C) 2026 Jacob Repp -->

# Entity rendering

Entity rendering is a quite hard topic.

## Production path

`EntitiesRenderer` owns renderer instances, feature registration, visibility,
and one `EntityDrawer`. Preparation computes camera, main-view, and optional
shadow visibility once per entity. Worker-local collection batches are merged
once on the render thread; opaque, translucent, shadow, and outline queues are
then sorted and submitted by graph passes.

An `EntityRenderer` owns a primary model renderer plus independent
`EntityRenderFeature` objects such as armor, held items, names, flames,
hitboxes, outlines, leashes, and projected shadows. A feature declares its
layer, additional layers, shadow behavior, priority, distance, and stable order.
Composite features can submit only their translucent sublayers without
replaying the opaque base.

## Skeletal models

- `SkeletalModel`: normalized source model and animation declarations.
- `BakedSkeletalModel`: retained meshes, transform tree, material layers, and
  content-generation ownership.
- `SkeletalInstance`: per-entity animation, material choice, root matrix, and
  evaluated transforms.
- `TransformInstance`: mutable pose nodes packed into the skeletal uniform
  buffer immediately before a mesh draw.

Baked geometry is shared by model/material where possible. Draw submission is
still per feature/model-layer instance because `SkeletalManager` currently
overwrites one transform UBO before each instance. This is the principal
boundary for future repeated-model instancing.

## Model designing

### Entities

Entities are always designed without any rotation (i.e. `yaw`=`0`)

## Things to consider

- name rendering (without culling and visible through walls) (and scoreboard objective)
- hitbox rendering
- entity model itself
    - player with arms, legs, ...
    - has animations, ...
    - different poses (sneaking, etc)
- yaw vs head yaw
- "features"
    - armor (and armor trims)
    - elytra
    - stuck arrows (and bee stingers)
    - cape
    - shoulder entities (parrots)
    - held item
- light (shade and lightmap)

## Ordering and shader routing

Opaque queues sort by feature priority, a producer-declared immutable render
state key, near-to-far distance, and stable entity order. The state key names
the program family, vertex/state ABI, material layer, and mesh group instead of
using a class hash. Translucent queues preserve far-to-near distance before the
state key; shadow queues use priority, state key, and stable order. Iris draw
scopes add entity/item/block identity and overlay color, and the pinned shader
pipeline maps each feature's semantic contract to a shader-pack program.

Repeated-model instancing remains open performance work. Translucent changes
must preserve depth ordering. The grounded constraints
and measurement workloads are in
[Render performance and OpenGL submission](Performance.md).

## Hitboxes

Each enabled hitbox subfeature owns one fixed-capacity indexed line mesh.
Position, rotation, velocity, and interpolated color update its loaded vertex
buffer in place; degenerate padding keeps the upload size invariant. The mesh
is not replaced every frame. Feature toggles are dynamic and newly created
profiles keep hitboxes disabled by default.

## Tests

- persistent hitbox geometry, self-assignment, and retirement cleanup;
- main/shadow visibility and collection;
- layer, priority, distance, and stable-order sorting;
- Iris scene-contract selection and draw-state propagation; and
- content-model reload, rejected-candidate cleanup, and entity removal.
