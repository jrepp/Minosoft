<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.

  This program is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
  FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Contributing to Minosoft

Contributions are welcome. Keep each change focused, preserve multi-version and
headless behavior, and make the evidence for correctness easy to review.

## Ways to contribute

Code, tests, documentation, reproducible bug reports, feedback, and project
recommendations are all useful contributions. For development setup and coding
details, also read the [development guide](doc/contributing/Development.md).

### Report an issue

When reporting a bug against `master`:

1. Confirm that the behavior is reproducible and search existing issues first.
2. Describe the expected behavior, actual behavior, and minimal reproduction.
3. Include the Minosoft version or commit, operating system, Java version, and
   any other relevant environment details.
4. Include the first relevant exception and enough surrounding log context to
   diagnose it. A complete log is useful when the relevant section is unclear.
5. Remove email addresses, access tokens, account identifiers, server addresses,
   and other sensitive data before posting a log.
6. Put logs directly in a fenced code block or repository attachment instead of
   an unrelated third-party paste service.

Questions and suggestions can also be raised through the issue tracker, by
email at [bixilon@bixilon.de](mailto:bixilon@bixilon.de), or in the
[#minosoft:matrix.org](https://matrix.to/#/#minosoft:matrix.org) Matrix room.

## Contribution license

Unless a file clearly states different terms, contributions are submitted under
the same license terms that apply to that file. Core Minosoft code is distributed
under the GNU General Public License, version 3 or, at your option, any later
version; see [`LICENSE.md`](LICENSE.md).

By submitting a contribution, you represent that you have the right to license
it on those terms and agree that the project may distribute it under those
terms. You retain your copyright. The project does not currently require a
copyright assignment, Contributor License Agreement, or Developer Certificate
of Origin sign-off.

Preserve valid copyright and license notices. New nontrivial files should copy
the nearest applicable project license header and name their actual author.
Use `Copyright (C) 2026 Jacob Repp` for Jacob Repp's original work completed in
2026. Add that separate line to an existing file only for a substantial original
contribution, not for a small patch, mechanical edit, or minor refactor. Never
replace another copyright holder's valid notice. For formats that cannot safely
contain comments, rely on the nearest applicable directory or project notice
instead of adding invalid syntax.

Do not submit code, assets, or generated output that you cannot redistribute
under the applicable terms. Identify copied or adapted material and preserve
its required attribution and license information.

## Binary and generated artifacts

Only track a binary or generated artifact when it is a durable input or
reviewed reference consumed by a repository workflow. Do not commit one-off
captures, build output, downloaded dependencies, runtime logs, or diagnostic
state.

Every tracked third-party or generated artifact must have nearby documentation
that identifies its purpose and consumer, provenance and applicable license,
an exact version or source commit, a cryptographic hash, and the intentional
update or reproduction procedure. Platform-qualified visual references must
also record the capture environment, crop, tolerance, and recapture rules.
Keep dependency archives in the configured out-of-source store. For Gradle
wrapper updates, pin the distribution checksum and verify the wrapper JAR
against the upstream release.

## Before changing code

1. Read [`AGENTS.md`](AGENTS.md) and select every relevant evidence map from
   [`doc/agents/`](doc/agents/README.md).
2. Inspect the current implementation and focused tests. Documentation is a map,
   not a substitute for source behavior.
3. Start at the earliest affected layer when a boundary is unclear.
4. Keep generated output and runtime state out of the change unless the task
   explicitly targets them.

## Implementation and tests

- Keep changes narrowly scoped and preserve unrelated work already in the tree.
- Follow the [Java and Kotlin implementation guidance](doc/agents/guides/java-kotlin.md)
  for JVM changes.
- Put focused tests beside the behavior they cover. Use
  `src/integration-test/kotlin` when real assets, packet fixtures, or subsystem
  integration are required.
- Update the applicable evidence map when an entry point, invariant, decision,
  or recommended validation changes.
- Run the smallest relevant check first, then broaden verification in
  proportion to risk. CI, Gradle, tests, launch tooling, and emitted bytecode
  use Java 25.

Common verification commands are:

```sh
./gradlew compileKotlin
./gradlew :render-contracts:test
./gradlew test
./gradlew integrationTest
./gradlew assemble
./gradlew :debug-core:test :play-util:installDist :debug-server-fabric:remapJar
```

Document checks that could not be run and explain why.

## Review guidance

Before requesting review, inspect the complete diff line by line, including new
files, tests, configuration, and documentation. Review for:

- correctness at success, failure, boundary, and cleanup paths;
- bounded and validated external input before allocation or expansion;
- finite numeric state and checked allocation arithmetic;
- explicit ownership and complete cleanup of native, GPU, file, callback, and
  generation resources;
- atomic state transitions, safe callback concurrency, and last-known-good
  transactional publication;
- avoidable work or allocation in render, tick, packet, and other hot paths;
- consistent naming, nullability, exception handling, and established
  Java/Kotlin idioms;
- preserved multi-version, headless, license, and copyright behavior;
- focused regression coverage and documentation claims supported by current
  code or repeatable evidence.

Resolve review findings in the commit that introduces the affected behavior
when practical. Use a separate follow-up commit when doing so preserves review
history or keeps an already-reviewed change stable. Do not hide unrelated
cleanup inside a review fix.

## Commit guidance

Use small, targeted
[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
that each express one coherent behavior or concern:

```text
fix(rendering): preserve the active model on reload failure
test(datapack): cover bounded execute fanout
docs(agents): record JVM resource ownership rules
```

- Use `type(scope): imperative summary`; keep the summary concise and omit a
  trailing period.
- Prefer `feat`, `fix`, `perf`, `refactor`, `test`, `docs`, `build`, `ci`, or
  `chore` according to the change's primary purpose.
- Include a behavior's focused tests in the same commit when they prove that
  behavior.
- Separate mechanical formatting, generated metadata, documentation, and
  unrelated fixes from behavior changes.
- Order layered work from foundational contracts and data structures through
  implementation and integration, followed by documentation or packaging
  metadata.
- Keep commits buildable and reviewable independently where practical. Explain
  an intentional dependency on an earlier commit in the commit body.
- Never amend, squash, reorder, or commit another contributor's unrelated
  working-tree changes without their explicit approval.

Commit messages and repository history record authorship, but they do not
replace required file-level copyright and license notices.
