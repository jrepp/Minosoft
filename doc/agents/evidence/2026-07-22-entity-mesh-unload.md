<!-- Copyright (C) 2026 Jacob Repp -->

# Entity mesh unload evidence — 2026-07-22

## Failure

The live client repeatedly reported:

```text
java.lang.IllegalStateException: Vertex buffer is not uploaded: UNLOADED
  at OpenGlVertexBuffer.unload
  at Mesh.unload
  at MeshedFeature.enqueueUnload
```

`MeshedFeature.unload()` disposed its current mesh and then assigned `null`
through the normal replacement setter. That setter queued disposal of the same
mesh a second time. A queued replacement cleanup could likewise encounter a
mesh already disposed by another lifecycle path.

## Contract

Entity-feature mesh cleanup is state-aware and idempotent:

- `PREPARING` meshes drop their CPU-side buffers;
- `LOADED` meshes unload their GPU buffers exactly once;
- `UNLOADED` meshes require no work;
- clearing the current mesh during immediate feature shutdown must not enqueue
  another disposal;
- queued disposal rechecks state on the render thread.

The low-level GPU buffer retains its strict state checks. Lifecycle ownership is
fixed at `MeshedFeature` rather than weakening `OpenGlVertexBuffer`.

## Acceptance

- The change activated through supervised base reload as client generation 10,
  PID 71380, while parent PID 77297 and ready server PID 77325 remained stable.
- Before the change, the warning repeated every few frames or seconds.
- After activation, live scene rendering and injected interaction produced no
  further `Vertex buffer is not uploaded` entries in the sampled
  post-activation log window. A separate startup `GL_INVALID_VALUE` remained;
  it was later traced to resource-pack texture dimensions and sampler defaults,
  not entity mesh disposal.
- All five enabled `HitboxFeatureTest` cases passed.
- Full Java 17 acceptance passed: 1,441 unit tests and 2,001 integration tests
  with zero failures (116 integration skips).
