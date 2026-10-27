// Built once per Minecraft version by Stonecutter (versions/<mc>/ reuse this script).
plugins {
    // Applies the right Loom variant for the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

// 26.x needs Java 25; older versions stay on 21
val requiredJava: JavaVersion = if (loomx.isUnobfuscated) JavaVersion.VERSION_25 else JavaVersion.VERSION_21

// Renames between 1.21.11 (how the sources are written) and 26.2, applied by Stonecutter to the
// 26.2 build so the many call sites don't each need a //? block. Each pattern is scoped (to the
// `context` graphics variable, a receiver, a whole word) so it can't hit unrelated code, and the
// 26.2 side must never appear in the 1.21.11 sources, so the way back is exact too.
val mc26 = sc.eval(sc.current.version, ">=26.2")
sc.replacements {
    fun rename(old: String, new: String) = regex(mc26) { replace(Regex.escape(old), new, Regex.escape(new), old) }
    fun word(old: String, new: String) = regex(mc26) { replace("\\b$old\\b", new, "\\b$new\\b", old) }

    // Screens render through an extractor now
    word("GuiGraphics", "GuiGraphicsExtractor")
    rename("void render(", "void extractRenderState(")
    rename("super.render(context", "super.extractRenderState(context")
    word("renderBackground", "extractBackground")
    word("renderContent", "extractContent")
    rename("context.drawString(", "context.text(")
    rename("context.drawCenteredString(", "context.centeredText(")
    rename("context.renderItem(", "context.item(")
    rename("context.renderItemDecorations(", "context.itemDecorations(")
    // The current screen moved from Minecraft to Minecraft.gui
    regex(mc26) { replace("\\b(minecraft|client)\\.setScreen\\(", "\$1.gui.setScreen(", "\\b(minecraft|client)\\.gui\\.setScreen\\(", "\$1.setScreen(") }
    rename("client.screen == null", "client.gui.screen() == null")
    // Shulker box contents as a stream of copies
    rename(".nonEmptyStream()", ".nonEmptyItemCopyStream()")
    // Fabric API moved to Mojang's names
    rename("api.client.keybinding.v1.KeyBindingHelper", "api.client.keymapping.v1.KeyMappingHelper")
    rename("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
    word("ClientCommandManager", "ClientCommands")
    rename("Screens.getButtons(", "Screens.getWidgets(")
}

repositories {
    // MCPFabric — dev client only, lets Claude drive the game over MCP (CLAUDE.md 0.3)
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") { name = "Modrinth" } }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Mojang's mappings on obfuscated versions; nothing on 26.x, which ships with real names
    loomx.applyMojangMappings()

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
    runs.named("client") { programArguments.addAll("--username", (findProperty("devUser") as String?) ?: "Bluetails_dev") }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    // MIT requires the license text to travel with copies of the mod
    jar { from(rootProject.file("LICENSE")) }

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
