<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.
-->

# Java and Kotlin implementation guidance

Use this guide for JVM source changes after selecting the relevant layer maps.
It records durable rules exposed by code review and regressions; current source
and tests remain the behavioral authority.

## Validate at trust boundaries

- Bound every externally influenced byte count, string length, collection size,
  nesting depth, recursive call count, Cartesian expansion, and execution
  budget. Apply the bound before allocating, parsing, compiling, or expanding
  the data.
- Validate dimensions and use checked arithmetic such as `Math.addExact` and
  `Math.multiplyExact` before array, buffer, texture, or mesh allocation.
- Reject unsupported input explicitly. Do not silently ignore a command,
  selector, model feature, or protocol value and report success.
- Parse and dispatch through the canonical implementation. Rewriting an alias
  must re-enter the normal parser and validation stack; it must not call a
  privileged executor that bypasses signing, permissions, or syntax checks.
- Use `require` for invalid caller or content input and `check` for invalid
  lifecycle state. Do not use JVM assertions for either because production
  builds may disable them.

## Keep numeric state finite

- Validate `Float` and `Double` values with `isFinite()` before interpolation,
  normalization, matrix construction, allocation math, or conversion to an
  integer.
- Clamp user-facing settings at the profile-to-runtime boundary. Treat `NaN`
  and infinities as invalid or replace them with the documented safe default.
- Normalize vectors and quaternions only after checking their squared length in
  a sufficiently wide type. A zero or non-finite rotation must not contaminate
  a render matrix.
- Prefer squared-distance comparisons in hot filters. Take a square root only
  when the actual magnitude is part of the result.

## Make ownership and cleanup explicit

- Validate before acquiring native, GPU, file, socket, executor, or generation
  resources. If later initialization fails, release everything already
  acquired in reverse ownership order.
- Cleanup must attempt every independently owned resource. Preserve the first
  failure and attach later failures with `addSuppressed`.
- Make `close` and `unload` idempotent when callers can legitimately converge
  on the same lifecycle boundary. Otherwise, fail with a clear state error.
- Keep GPU allocation, upload, replacement, and deletion on the render thread.
  Publish a replacement only after its complete candidate state is ready.
- A callback registration must return an ownership handle when its lifetime can
  be shorter than the host. Closing that handle removes only its own callback.

## Preserve transactional publication

- Build and validate candidate state before replacing last-known-good state.
- When a load or reload can mutate scores, storage, entities, caches, or other
  shared state, snapshot or journal every affected owner and roll all of them
  back on failure.
- Commit only after the complete load phase succeeds. If rollback or candidate
  cleanup also fails, suppress those errors onto the original load failure.
- Copy mutable maps and lists that cross an ownership boundary. Do not expose a
  candidate's mutable backing collection through a published snapshot or cache
  key.

## Concurrency and callbacks

- Release locks in `finally` or through the repository's scoped lock helper.
  Do not split a check and its mutation across separate lock acquisitions.
- Do not invoke arbitrary callbacks while holding a mutable-state lock. Take a
  stable snapshot or use a copy-on-write collection when callback churn is low.
- Use atomics or a lock for check-then-set state transitions. `volatile`
  provides visibility but does not make a compound transition atomic.
- Select concurrent collections according to the access pattern. Iterating a
  mutable `HashMap` or `HashSet` while another thread registers or unregisters
  is not safe.
- Lifecycle end hooks belong in `finally`. If an end hook fails while handling
  another failure, preserve the original and suppress the hook failure.

## Keep hot paths predictable

- Pre-index trees or registries when the same lookup otherwise walks the full
  structure every frame, tick, pose, or entity.
- Bound fan-out before using `flatMap`, `associate`, or recursive traversal on
  content-controlled data. A later command budget does not protect the memory
  consumed while constructing an intermediate list.
- Cache only with immutable keys that include every input affecting the result.
  Keep caches generation-owned and clear or close them with that generation.
- Avoid per-frame copies and allocations unless they provide a required
  ownership snapshot. Prefer immutable precomputed data for model bindings,
  aliases, and layout geometry.

## Kotlin and Java idioms

- Kotlin: prefer `use` for `Closeable` streams, safe casts at untrusted
  boundaries, and immutable return types. Avoid `runCatching` when cleanup
  failures need to be retained rather than discarded.
- Kotlin: do not use `put` followed by a duplicate check when replacement is
  invalid; use `putIfAbsent` so a rejected registration cannot overwrite the
  accepted owner.
- Java: prefer try-with-resources and attach cleanup failures to the primary
  exception when coordinating multiple owners manually.
- In both languages, include units in names for time, distance, byte counts, and
  sizes. Convert once at the boundary and keep the internal representation
  consistent.

## Verification checklist

- Add a focused test for the behavior and at least one failure, boundary, or
  cleanup case exposed by the change.
- For parsers and adapters, cover malformed, oversized, deeply nested,
  non-finite, duplicate, and unsupported input as applicable.
- For lifecycle changes, prove successful replacement, failed-candidate
  rollback, repeated close/unload behavior, and cleanup after partial setup.
- For concurrent state, exercise registration/removal during iteration or use a
  deterministic unit test for the atomic transition.
- Run the smallest relevant Gradle target first, then the affected module or
  integration suite, followed by the broader gates required by the selected
  evidence maps.
