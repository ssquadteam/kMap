import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.paperweight.userdev)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.nexomc.com/releases")
    maven("https://repo.nexomc.com/snapshots")
}

dependencies {
    paperweight.paperDevBundle(libs.versions.paper.dev.bundle.get())
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.nexo) { isTransitive = false }
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjdk-release=25")
    }
}

tasks.processResources {
    filteringCharset = Charsets.UTF_8.name()
    val props = mapOf("version" to project.version.toString(), "kotlin" to libs.versions.kotlin.get())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName = "kMap-${project.version}.jar"
}
