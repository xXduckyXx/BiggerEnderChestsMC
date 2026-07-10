plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.echest"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    shadowJar {
        archiveBaseName.set("EnderChestDB")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
    build {
        dependsOn(shadowJar)
    }
}
