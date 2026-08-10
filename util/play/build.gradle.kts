/*
 * Minosoft
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":debug-core"))
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        java.setSrcDirs(listOf("."))
        java.include("Play.java")
        java.include("ContentPackAdapter.java")
        java.include("ContentPackComposer.java")
        java.include("ContentStackManifest.java")
        java.include("ContentSubmissionQueue.java")
        java.include("GeneratedContentPack.java")
        java.include("GeneratedTextureLibrary.java")
        java.include("MotionNoiseAnalyzer.java")
        java.include("TrajectoryDiagnostics.java")
        java.include("TrajectoryCheckpointStore.java")
        java.include("TrajectoryLeaseStore.java")
        java.include("WorldSnapshot.java")
    }
    test {
        java.setSrcDirs(listOf("src/test/java"))
    }
}

application {
    mainClass.set("Play")
}

tasks.test {
    useJUnitPlatform()
}
