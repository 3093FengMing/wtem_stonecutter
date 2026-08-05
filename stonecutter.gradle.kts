plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2.x"

// Each version node publishes on its own, so without ordering they race to create the GitHub
// release for the tag. Ordering the endpoint tasks (not `publishMods`, which only delegates to
// them) makes one node create the release and the rest attach their files to it.
stonecutter tasks {
    order("publishGithub")
    order("publishModrinth")
    order("publishCurseforge")
}

tasks.register("buildAll") {
    group = "build"
    description = "Builds every version and collects the jars into `build/libs/{mod version}/`"
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}

tasks.register("testAll") {
    group = "verification"
    description = "Runs the tests against every version"
    dependsOn(stonecutter.tasks.named("test"))
}

tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes every version to all configured platforms"
    dependsOn(stonecutter.tasks.named("publishMods"))
}

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"${property("mod.version")}\";"
    swaps["minecraft"] = "\"${node.metadata.version}\";"
    constants["release"] = property("mod.id") != "template"
    dependencies["fapi"] = node.project.property("deps.fabric_api") as String

    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
        }

        string(current.parsed >= "1.21.11", "resource_key_api") {
            replace(".location()", ".identifier()")
        }


        // 26.1 made ChunkPos a record; earlier mappings expose public x/z fields.
        string(current.parsed >= "26.1", "chunk_pos_api") {
            replace("chunkPos.x", "chunkPos.x()")
            replace("chunkPos.z", "chunkPos.z()")
        }

        // 1.21.5 turned the primitive tags into records and renamed the key accessor
        string(current.parsed >= "1.21.5", "nbt_api") {
            replace(".getAsString()", ".value()")
            replace(".getAllKeys()", ".keySet()")
            replace(".getAsInt()", ".intValue()")
            replace(".getAsDouble()", ".doubleValue()")
        }

        string(current.parsed >= "26.1") {
            replace("classTweaker v2 named", "classTweaker v2 official")
            replace("createPathToResource", "resolvePath")
        }

        string(current.parsed >= "26.1", "screen") {
            replace("render", "extractRenderState")
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("drawCenteredString", "centeredText")
            replace("drawString", "text")
        }
    }
}
