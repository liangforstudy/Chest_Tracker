plugins {
    id("dev.kikugie.stonecutter")
    // Loom for obfuscated versions (<= 1.21.x); loom-back-compat applies it per version.
    id("net.fabricmc.fabric-loom-remap") version "1.17.21" apply false
}

stonecutter active "1.21.11"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"${property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
}
