import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.1.0"   // ← the single source of truth; processResources syncs it into plugin.json

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// CI (the risa-labs-inc release workflow) sets CI=true and drops the api jar at
// build/downloaded-deps/boss-plugin-api.jar itself. Locally there is no sibling
// boss-plugin-api checkout to build, so fetchBossPluginApiJar pulls the same
// pinned release jar straight from GitHub Releases into libs/ (gitignored).
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiVersion = "1.0.87"
val localApiJar = layout.projectDirectory.file("libs/boss-plugin-api-$bossPluginApiVersion.jar")

tasks.register("fetchBossPluginApiJar") {
    val jarFile = localApiJar.asFile
    outputs.file(jarFile)
    doLast {
        if (!jarFile.exists()) {
            jarFile.parentFile.mkdirs()
            val url =
                "https://github.com/risa-labs-inc/boss-plugin-api/releases/download/" +
                    "v$bossPluginApiVersion/boss-plugin-api-$bossPluginApiVersion.jar"
            logger.lifecycle("Downloading boss-plugin-api $bossPluginApiVersion from $url")
            uri(url).toURL().openStream().use { input ->
                jarFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

if (useLocalDependencies) {
    tasks.named("compileKotlin") { dependsOn("fetchBossPluginApiJar") }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        compileOnly(files(localApiJar))
    } else {
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

// The loadable plugin JAR: compiled classes + the plugin.json manifest.
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-project-studio-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Project Studio Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.projectstudio.ProjectStudioDynamicPlugin",
        )
    }
    from(sourceSets.main.get().output)
    from("src/main/resources")
}

// Keep plugin.json's version in lockstep with the build version.
tasks.processResources {
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "$version"""")
        }
    }
}

tasks.build { dependsOn("buildPluginJar") }
