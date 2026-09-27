plugins {
    idea
    alias(libs.plugins.fabric.loom)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

group = project.properties["mod.group"].toString()
version = project.properties["mod.version"].toString()

base {
    archivesName = project.properties["mod.name"].toString()
}

repositories {
    mavenCentral()

    // Simple Voice Chat API
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "henkelmax"
    }

    // Plasmo Voice API
    maven("https://repo.plasmoverse.com/releases") {
        name = "plasmoverse-releases"
    }
    maven("https://repo.plasmoverse.com/snapshots") {
        name = "plasmoverse-snapshots"
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${project.properties["minecraft.version"]}")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${project.properties["fabric.loader.version"]}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.properties["fabric.api.version"]}")

    // Simple Voice Chat API (provided at runtime by the mod)
    compileOnly("de.maxhenkel.voicechat:voicechat-api:${project.properties["svc.api.version"]}")

    // Plasmo Voice Server API (provided at runtime by the mod)
    compileOnly("su.plo.voice.api:server:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.voice.api:common:${project.properties["plasmo.voice.version"]}")
    compileOnly("su.plo.voice:protocol:${project.properties["plasmo.voice.version"]}")

    // Bundled libraries
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.yaml:snakeyaml:2.2")
}

// Shadow whitelist — loom puts the game and fabric-api on runtimeClasspath,
// so shadowJar must only bundle the explicit libraries below.
val bundledLibs by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    bundledLibs(kotlin("stdlib"))
    bundledLibs("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    bundledLibs("org.yaml:snakeyaml:2.2")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    shadowJar {
        configurations = listOf(bundledLibs)
        archiveClassifier = "all"
        relocate("org.yaml.snakeyaml", "io.pfaumc.voicebridge.lib.snakeyaml")
    }

    remapJar {
        inputFile = shadowJar.flatMap { it.archiveFile }
        archiveClassifier = ""
    }

    assemble {
        dependsOn(remapJar)
    }

    build {
        dependsOn(remapJar)
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf(
            "version" to project.version,
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }
}
