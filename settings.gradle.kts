pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("dev.kikugie.loom-back-compat") version "0.4.3"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One Fabric build per Minecraft version. Only 1.21.11 for now; older versions need the
// NBT item reader (CLAUDE.md 4.5), 26.x is planned in PORTING-26.2.md.
stonecutter {
    create(rootProject) {
        versions("1.21.11")
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "entitytracker"
