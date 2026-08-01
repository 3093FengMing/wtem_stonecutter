plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "26.2.x"

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

        // 1.21.5 turned the primitive tags into records and renamed the key accessor, so the NBT
        // rename boundary sits there rather than at the 1.21.11 mapping switch.
        string(current.parsed >= "1.21.5", "nbt_api") {
            replace(".getAsString()", ".value()")
            replace(".getAllKeys()", ".keySet()")
            replace(".getAsInt()", ".intValue()")
            replace(".getAsDouble()", ".doubleValue()")
        }

        string(current.parsed >= "26.1") {
            replace("classTweaker v2 named", "classTweaker v2 official")
            replace("SimpleRegionStorageUpgrader", "RegionStorageUpgrader")
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
