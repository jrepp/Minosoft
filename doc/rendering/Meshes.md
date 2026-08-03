<!-- Copyright (C) 2026 Jacob Repp -->

# Meshes

`MeshBuilder` owns CPU vertex/index data until `bake` creates a `Mesh` backed by
a rendering-system `VertexBuffer`. `load` initializes its GPU buffer and VAO;
`draw` submits it; `unload` retires the GPU objects. Builders that keep their
CPU storage can update a loaded buffer in place, which is preferred for
fixed-capacity dynamic geometry such as hitboxes, entity shadows, and leashes.

OpenGL's default front face is counter-clockwise. Minosoft's model builders use
the project's clockwise face convention and emit either native quads where the
negotiated driver path permits them or indexed triangles otherwise. Each
`MeshStruct` fixes the physical attribute stride and locations used by the VAO.

Terrain does not allocate one `Mesh` per region page. Near and distant semantic
artifacts upload into checked region vertex/index arenas. A frame obtains fresh
storage leases, reuses cached region/material/view command templates plus their
topology-grouped physical packets, and submits compatible page ranges with
multi-draw. The conventional `ChunkMesh`
path remains for pages that could not enter region storage and for non-OpenGL
implementations.

Texture/material identity is generally vertex data: a packed texture-array
index/layer for ordinary meshes and explicit terrain material fields for shader
providers. See [Render performance and OpenGL submission](Performance.md) for
the batching boundary and remaining draw-call work.
