plugins {
    id("dev.kikugie.stonecutter")
    // Both Loom variants, same version; loom-back-compat applies the right one per Minecraft version:
    // the remapping one for obfuscated versions (<= 1.21.x), the plain one for unobfuscated 26.x.
    id("net.fabricmc.fabric-loom-remap") version "1.19.3" apply false
    id("net.fabricmc.fabric-loom") version "1.19.3" apply false
}

stonecutter active "1.21.11"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"${property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
}
