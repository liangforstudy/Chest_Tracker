// Built once per Minecraft version by Stonecutter (versions/<mc>/ reuse this script).
plugins {
    // Applies the right Loom variant for the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = JavaVersion.VERSION_21

repositories {
    // MCPFabric — dev client only, lets Claude drive the game over MCP (CLAUDE.md 0.3)
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    "mappings"("net.fabricmc:yarn:${property("deps.yarn")}:v2")

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    modLocalRuntime("maven.modrinth:mcpfabric:${property("deps.mcpfabric")}")
    // Dev client only: other mods to check how we sit alongside them
    modLocalRuntime("maven.modrinth:shulkerboxtooltip:${property("deps.shulkerboxtooltip")}")
    modLocalRuntime("maven.modrinth:modmenu:${property("deps.modmenu")}")
    modLocalRuntime("maven.modrinth:placeholder-api:${property("deps.placeholder_api")}") // needed by Mod Menu
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        // Dev runs live outside the repo, in the RnD folder (see CLAUDE.md section 0)
        runDirectory = rootProject.file("../RnD/run")
    }
    // Fixed name so the offline UUID stays the same between runs; -PdevUser=Name for a second account
    runs.named("client") { programArgs("--username", (findProperty("devUser") as String?) ?: "Bluetails_dev") }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
        }

        filesMatching("fabric.mod.json") { expand(props) }
        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies them to build/libs/{mod version}/"
        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
