plugins {
    // The remap variant of Loom is used for the obfuscated Minecraft versions, it turns the
    // Mojang mappings back into the intermediary names the loader needs.
    id("net.fabricmc.fabric-loom-remap") version "1.17.21"
    java
}

group = "com.echest"
version = "1.1.1"

base {
    archivesName.set("BiggerEnderChests")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")

    // The SQLite driver is put into the jar of the mod, the server does not have it.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    include("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

loom {
    runs {
        // The mod only runs on the server, the client is a vanilla client.
        named("server") {
            server()
            runDir("run")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
    processResources {
        inputs.property("version", project.version)
        filesMatching("fabric.mod.json") {
            expand("version" to project.version)
        }
    }
}
