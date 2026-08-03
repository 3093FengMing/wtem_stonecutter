import me.modmuss50.mpp.ReleaseType
import me.modmuss50.mpp.platforms.modrinth.ModrinthApi
import me.modmuss50.mpp.platforms.modrinth.ModrinthEnvironment

plugins {
    // This plugin applies the correct loom variant based on the Minecraft version
    id("dev.kikugie.loom-back-compat")
    id("me.modmuss50.mod-publish-plugin")
}

// DO NOT set group = ...!
val modVersion = property("mod.version") as String
val modName = property("mod.name") as String
version = "$modVersion+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    sc.current.parsed >= "1.20.5" -> JavaVersion.VERSION_21
    sc.current.parsed >= "1.18" -> JavaVersion.VERSION_17
    sc.current.parsed >= "1.17" -> JavaVersion.VERSION_16
    else -> JavaVersion.VERSION_1_8
}

// The Minecraft releases this jar is marked compatible with on Modrinth and Curseforge
val compatibleVersions: List<String> = sc.properties.rawOrNull("mod", "mc_releases")
    ?.asList().orEmpty().map { it.toString() }

repositories {
    /**
     * Restricts dependency search of the given [groups] to the [maven URL][url],
     * improving the setup speed.
     */
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")
}

dependencies {
    /**
     * Fetches only the required Fabric API modules to not waste time downloading all of them for each version.
     * @see <a href="https://github.com/FabricMC/fabric">List of Fabric API modules</a>
     */
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    // Applies Mojang Mappings on obfuscated versions
    loomx.applyMojangMappings()

    // Use `mod{dependency type}` even on 26.1+ - loom-back-compat converts them
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi("fabric-lifecycle-events-v1", "fabric-resource-loader-v0", "fabric-content-registries-v0", "fabric-registry-sync-v0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection
    accessWidenerPath = sc.process(
        rootProject.file("src/main/resources/wtem.ct"),
        "build/processed.ct"
    )

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
    }

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run") // Shares the run directory between versions
        jvmArguments.add("-Dmixin.debug.export=true") // Exports transformed classes for debugging
    }
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava

    toolchain {
        vendor = JvmVendorSpec.ADOPTIUM
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    withType<Test>().configureEach {
        useJUnitPlatform()
    }

    processResources {
        from(rootProject.file("LICENSE"), rootProject.file("NOTICE"))

        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val mixins = when {
            stonecutter.eval(stonecutter.current.version, ">=26.1") -> "mixins.wtem.json"
            else -> "mixins.wtem.json"
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
            set("mixins", mixins)
        }

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("mixins.*.json") { expand("java" to mixinJava) }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

        inputs.property("version", project.property("mod.version"))
        // loomx.mod(Sources)Jar returns the jar task for the applied loom variant
        from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}

// region Publishing

/** Reads an optional `publish.*` property, treating a blank value as absent. */
fun publishProperty(name: String): String? =
    (sc.properties.rawOrNull("publish", name)?.toString())?.takeIf { it.isNotBlank() }

/**
 * `STABLE` unless the version carries a pre-release suffix, so `0.2.0-beta.1` is published as a
 * beta without needing a second place to keep in sync.
 */
val releaseType: ReleaseType = when {
    "-alpha" in modVersion -> ReleaseType.ALPHA
    "-beta" in modVersion || "-rc" in modVersion -> ReleaseType.BETA
    else -> ReleaseType.STABLE
}

/**
 * The changelog is passed in by CI (`CHANGELOG` env var) so the release notes come from the tag
 * rather than being committed into the build script.
 */
val changelogText: String = providers.environmentVariable("CHANGELOG").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "See https://github.com/${publishProperty("github_repo")}/releases/tag/v$modVersion"

publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }
    additionalFiles.from(loomx.modSourcesJar.flatMap { it.archiveFile })
    version = project.version.toString()
    displayName = "$modName $modVersion for Minecraft ${sc.current.version}"
    changelog = changelogText
    type = releaseType
    modLoaders.add("fabric")

    // Publishing to a live platform is irreversible, so default to a dry run and only go live when
    // the platform's token is actually present in the environment. `-Ppublish.dry_run=true` forces
    // a dry run even then, which is how the release workflow offers a rehearsal.
    dryRun = providers.gradleProperty("publish.dry_run").orNull.toBoolean() ||
            (providers.environmentVariable("MODRINTH_TOKEN").orNull.isNullOrBlank() &&
                    providers.environmentVariable("CURSEFORGE_TOKEN").orNull.isNullOrBlank() &&
                    providers.environmentVariable("GITHUB_TOKEN").orNull.isNullOrBlank())

    publishProperty("modrinth_id")?.let { id ->
        modrinth {
            accessToken = providers.environmentVariable("MODRINTH_TOKEN")
            projectId = id
            minecraftVersions.addAll(compatibleVersions)
            environment = ModrinthEnvironment.SINGLEPLAYER_ONLY
            additionalFile(loomx.modSourcesJar.flatMap { it.archiveFile }) {
                type = ModrinthApi.AdditionalFileType.SOURCES_JAR
            }
        }
    }

    publishProperty("curseforge_id")?.let { id ->
        curseforge {
            accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
            projectId = id
            publishProperty("curseforge_slug")?.let { projectSlug = it }
            minecraftVersions.addAll(compatibleVersions)
            javaVersions.add(requiredJava)
            client = true
            server = false
        }
    }

    publishProperty("github_repo")?.let { repo ->
        github {
            accessToken = providers.environmentVariable("GITHUB_TOKEN")
            repository = repo
            commitish = providers.environmentVariable("GITHUB_SHA").orElse("main")
            tagName = "v$modVersion+${sc.current.version}"
            // Every version node uploads into the single release created for the tag, so the first
            // node to run must be allowed to create it and later nodes must not create their own.
            allowEmptyFiles = true
        }
    }
}

// endregion
