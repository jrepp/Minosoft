# Agent guidance

This file is deliberately small. Use it as the working contract, then read the
relevant evidence maps in [`doc/agents/`](doc/agents/README.md) before changing
code. Repository-wide contribution, review, and commit expectations are in
[`Contributing.md`](Contributing.md).

## Start here

1. Read the layer index and select every map the task crosses. Start at the
   earliest affected layer when a boundary is unclear.
2. Inspect the referenced source and tests. Grounding documents are maps, not a
   substitute for current code.
3. For Java or Kotlin changes, follow the
   [JVM implementation guidance](doc/agents/guides/java-kotlin.md).
4. Keep the change scoped and preserve unrelated work already in the tree.

## Working contract

- Follow the task, the nearest applicable `AGENTS.md`, and the selected evidence
  maps. If documentation and code disagree, verify behavior and update or flag
  the stale grounding.
- Preserve Minosoft's multi-version and headless behavior unless the task
  explicitly changes it.
- Preserve valid copyright and license notices. For each new, nontrivial file
  whose format supports a header, copy the nearest applicable project GPL
  license notice and credit its actual author; use `Copyright (C) 2026 Jacob
  Repp` for Jacob Repp's 2026 work. Cover comment-hostile files through the
  nearest directory-level or project-level license notice instead of inserting
  invalid syntax. In an existing file, add a separate `Copyright (C) 2026 Jacob
  Repp` line only for a substantial, original contribution such as a new
  feature, coherent implementation, or major test/documentation body. Do not
  add or extend a copyright line for a small patch, mechanical change, or minor
  refactor, and never replace another holder's valid notice.
- Do not hand-edit generated output or local runtime state (`build/`, `.gradle/`,
  `.run/`, `it/`, or `server/`) unless the task explicitly targets it.
- Keep pack definitions under `modpacks/`; keep third-party artifacts and
  trajectory state in the configured out-of-source modpack store.
- For play-parent or reload changes, use `./play.sh status --json` and the
  hot-reload acceptance protocol. Copy durable conclusions into `doc/agents/`;
  do not commit raw `.run/` lifecycle logs.
- For debug-channel changes, read `doc/agents/areas/09-debug-control-plane.md`.
  Keep wire/transport behavior in `debug-core`, consume it through the shared
  `DebugClient`, and verify both endpoint cleanup and `core.capabilities`.
- Put focused tests beside the behavior they cover. Use
  `src/integration-test/kotlin` when real assets, packet fixtures, or subsystem
  integration are required.
- Update the relevant evidence map when a change moves an entry point, changes
  an invariant, establishes a decision, or alters the recommended validation.
  Do not record temporary implementation detail.

## Build and verification

CI runs on Java 17 while emitted bytecode targets Java 11. Prefer a Java 17
runtime for Gradle.

```sh
./gradlew compileKotlin
./gradlew test
./gradlew integrationTest
./gradlew assemble
./gradlew :debug-core:test :play-util:installDist :debug-server-fabric:remapJar
```

Run the smallest relevant check first, then broaden in proportion to risk.
Document checks you could not run and why.
