rootProject.name = "voice-bridge"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            plugin("fabric-loom", "fabric-loom").version("1.17.21")
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").version("2.3.0")
            plugin("shadow", "com.gradleup.shadow").version("9.0.0-beta13")
        }
    }
}
