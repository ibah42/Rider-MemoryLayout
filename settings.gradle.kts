rootProject.name = "memory-layout"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download JDK 21 for the toolchain when the system does not have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
