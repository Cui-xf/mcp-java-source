plugins {
    id("org.jetbrains.intellij.platform") version "2.7.1"
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
}

group = "org.cc"
version = "1.0.3-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
//        create("IC", "2025.2")
        local("/Applications/IntelliJ IDEA CE.app")
        bundledPlugin("com.intellij.java")
    }
}
dependencies {
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
