import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import de.skyrising.mc.scanner.gen.generateRandomTicksKt

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    id("com.gradleup.shadow") version "9.3.0"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("it.unimi.dsi:fastutil:8.5.13")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("de.skyrising.mc.scanner.ScannerKt")
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        mergeServiceFiles()
        minimize {
            exclude(dependency("org.jetbrains.kotlin:.*"))
        }
        dependsOn(distTar, distZip)
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

val generatedKotlinDir = layout.buildDirectory.dir("generated/kotlin")

tasks.register("generateSources") {
    doFirst {
        generateRandomTicksKt().writeTo(generatedKotlinDir.get().asFile)
    }
}

tasks.compileKotlin {
    dependsOn("generateSources")
}

sourceSets {
    main {
        java {
            srcDir(generatedKotlinDir)
        }
    }
}
