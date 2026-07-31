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

# Java 25 build baseline

## Decision

Java 25 is the repository-wide runtime and bytecode baseline. Gradle, tests,
Play, the main distribution, `debug-core`, `play-util`, and the Fabric debug
bridge require Java 25. The launcher rejects any other Java feature release
before starting Gradle or the compiled Play utility. Java 17 execution and Java
11-compatible artifacts are no longer supported.

Historical evidence that names Java 17 remains valid as provenance for those
earlier runs; it is not evidence for the current baseline.

## Compatible build stack

| Component | Accepted version |
| --- | --- |
| Local JDK | Oracle JDK 25.0.1 LTS, macOS aarch64 |
| Gradle wrapper | 9.5.0 |
| Kotlin Gradle plugin/language | 2.4.0 |
| Fabric Loom | 1.17.17 |
| Java/Kotlin bytecode | Java 25, class-file major version 69 |

Gradle 9.5.0 is intentional rather than merely the newest available wrapper.
It runs on Java 25, which Gradle supports from
[9.1.0](https://docs.gradle.org/9.1.0/release-notes.html), and is the highest
Gradle release in Kotlin Gradle plugin 2.4.0's
[documented fully supported range](https://kotlinlang.org/docs/gradle-configure-project.html#apply-the-plugin).

## Verification

The four JVM entry points compiled successfully:

```sh
./gradlew compileKotlin \
  :debug-core:compileJava \
  :play-util:compileJava \
  :debug-server-fabric:compileJava
```

`javap -verbose` reported class-file major version 69 for:

- `de.bixilon.minosoft.Minosoft`;
- `de.bixilon.minosoft.debug.DebugClient`;
- `Play`; and
- `de.bixilon.minosoft.debug.fabric.MinosoftDebugBridgeMod`.

The Java 25 launcher path passed:

```sh
MINOSOFT_JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home \
  ./play.sh help
```

The repository gate passed:

```sh
./gradlew test integrationTest assemble \
  :debug-core:test \
  :play-util:installDist \
  :debug-server-fabric:remapJar
```

Results were 2,136 main tests with three skipped, 2,157 integration tests with
116 skipped, nine `debug-core` tests, five Fabric bridge tests, and four Play
utility tests, with zero failures or errors. Both CI packaging shapes also
passed:

```sh
./gradlew fatJar -Parchitecture=amd64
./gradlew fatJar -Parchitecture=aarch64
```

The first broad run exposed an underpowered terrain telemetry p95 guard: it
measured 24 samples despite the repository requirement of at least 100. Raising
the gate to 100 samples made its implementation match the documented
performance protocol; the focused rerun and complete gate then passed.

## Boundaries

- GitHub Actions and GitLab CI both select Azul Zulu Java 25. The remote matrix
  was not executed locally.
- This build gate does not replace a live OpenGL or hot-reload trajectory.
- Gradle reports existing Gradle 10 deprecations, and Java 25 warns when LWJGL
  calls restricted native loading without an explicit native-access option.
  Neither warning failed this baseline, but both remain maintenance work.
