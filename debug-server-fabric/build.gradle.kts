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
    id("fabric-loom") version "1.17.17"
}

group = "de.bixilon.minosoft"
version = "0.1.0"

base {
    archivesName.set("minosoft-debug-bridge-fabric-1.20.4")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.20.4")
    mappings("net.fabricmc:yarn:1.20.4+build.3:v2")
    modImplementation("net.fabricmc:fabric-loader:0.15.11")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.97.3+1.20.4")

    implementation(project(":debug-core"))
    include(project(":debug-core"))
    implementation(project(":render-contracts"))
    include(project(":render-contracts"))
    include(implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.0")!!)
    // Jackson annotations intentionally versions itself as 2.22 (without a patch component).
    include(implementation("com.fasterxml.jackson.core:jackson-annotations:2.22")!!)
    include(implementation("com.fasterxml.jackson.core:jackson-core:2.22.0")!!)
    include(implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")!!)
    include(implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.0")!!)
    include(implementation("net.java.dev.jna:jna-jpms:5.18.1")!!)
    include(implementation("net.java.dev.jna:jna-platform-jpms:5.18.1")!!)
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
}
