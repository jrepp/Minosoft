/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */

plugins {
    kotlin("jvm")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.0")
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

val standardTest = tasks.named<Test>("test")

tasks.register<Test>("localTerrainTest") {
    group = "verification"
    description = "Runs deterministic distant-terrain contract tests."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = standardTest.get().testClassesDirs
    classpath = standardTest.get().classpath
    useJUnitPlatform()
    filter {
        includeTestsMatching("de.bixilon.minosoft.terrain.distant.lighting.DistantDetachedLightingTest")
        includeTestsMatching("de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageHierarchyTest")
        includeTestsMatching("de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMesherBaselineTest")
        includeTestsMatching("de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageMeshingTest")
        includeTestsMatching("de.bixilon.minosoft.terrain.distant.hierarchy.DistantPageSelectorTest")
        includeTestsMatching("de.bixilon.minosoft.terrain.model.coverage.TerrainCoverageMaskingTest")
        isFailOnNoMatchingTests = true
    }
}
