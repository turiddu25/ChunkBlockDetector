plugins {
    id("java")
    id("fabric-loom") version "1.13-SNAPSHOT"
}

group = property("maven_group")!!
version = property("mod_version")!!

base.archivesName.set(property("mod_id").toString())

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

val minecraft_version: String by project
val fabric_loader_version: String by project
val fabric_api_version: String by project
val adventure_platform_version: String by project
val minimessage_version: String by project

dependencies {
    minecraft("net.minecraft:minecraft:${minecraft_version}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${fabric_loader_version}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${fabric_api_version}")

    // Adventure text (MiniMessage + Fabric platform) - bundled in JAR
    modImplementation(include("net.kyori:adventure-platform-fabric:${adventure_platform_version}")!!)
    include("net.kyori:adventure-text-minimessage:${minimessage_version}")

    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.processResources {
    val modId = project.property("mod_id") as String
    val modVersion = project.property("mod_version") as String

    inputs.property("version", modVersion)
    inputs.property("mod_id", modId)

    filesMatching("fabric.mod.json") {
        expand(
            "version" to modVersion,
            "mod_id" to modId
        )
    }
}