import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "0.2.0"   // ← the single source of truth; processResources syncs it into plugin.json

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Where the api jar comes from, decided by what is actually on disk rather than by
// an environment variable.
//
// The risa-labs-inc release workflow drops it at build/downloaded-deps and used to
// be detected by CI=true. That is wrong outside the org: GitHub Actions sets CI=true
// for everybody, so any other repository's build looked for a file only that
// workflow provides and failed on a machine where the download would have worked
// perfectly well. Checking for the file covers the org pipeline, a contributor's own
// CI and a fresh clone with one rule and no special cases.
val vendoredApiJar = layout.buildDirectory.file("downloaded-deps/boss-plugin-api.jar")
val useLocalDependencies = !vendoredApiJar.get().asFile.exists()
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
        compileOnly(files(vendoredApiJar))
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

    // Tests compile against the same api jar the plugin does, and need it at runtime
    // too (it is compileOnly for the jar, because the host provides it).
    testImplementation(kotlin("test"))
    testImplementation(files(localApiJar))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // BossLogger binds SLF4J. The host supplies a backend at runtime; a standalone
    // harness or test JVM does not, and BossLogger's <clinit> fails without one.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// The loadable plugin JAR: compiled classes + the plugin.json manifest.
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-agent-warden-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Implementation-Title" to "BOSS Agent Warden Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.warden.WardenDynamicPlugin",
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

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// Dev-only: runs the gateway standalone against a live BOSS MCP endpoint so the
// wire behaviour can be exercised with curl without loading the plugin into BOSS.
// Not part of `build`; see docs/VALIDATION.md.
tasks.register<JavaExec>("runGatewayHarness") {
    group = "verification"
    description = "Start the MCP gateway on 7688 in front of BOSS's endpoint on 7677."
    mainClass.set("ai.rever.boss.plugin.dynamic.warden.gateway.GatewayHarnessKt")
    classpath = sourceSets.test.get().runtimeClasspath
}
