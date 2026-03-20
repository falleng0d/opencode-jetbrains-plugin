plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("jvm") version "2.2.0"
}

data class Bin(val src: String, val out: String, val win: Boolean = false)

fun bundledBinaries() = listOf(
    Bin("../opencode/dist/opencode-windows-arm64/bin/opencode.exe", "opencode-windows-arm64.exe", true),
    Bin("../opencode/dist/opencode-windows-x64-baseline/bin/opencode.exe", "opencode-windows-x64-baseline.exe", true),
    Bin("../opencode/dist/opencode-darwin-arm64/bin/opencode", "opencode-darwin-arm64"),
    Bin("../opencode/dist/opencode-darwin-x64-baseline/bin/opencode", "opencode-darwin-x64-baseline"),
    Bin("../opencode/dist/opencode-linux-arm64/bin/opencode", "opencode-linux-arm64"),
    Bin("../opencode/dist/opencode-linux-arm64-musl/bin/opencode", "opencode-linux-arm64-musl"),
    Bin("../opencode/dist/opencode-linux-x64-baseline/bin/opencode", "opencode-linux-x64-baseline"),
    Bin("../opencode/dist/opencode-linux-x64-baseline-musl/bin/opencode", "opencode-linux-x64-baseline-musl"),
)

group   = "ai.opencode"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        webstorm("2025.3")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
    }
    buildSearchableOptions = false
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey        = providers.environmentVariable("PRIVATE_KEY")
        password          = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Build the opencode standalone binaries for supported JetBrains hosts.
val buildOpencode by tasks.registering(Exec::class) {
    workingDir  = rootProject.file("../opencode")
    commandLine = listOf("bun", "run", "build")
}

// Build the webapp with JetBrains target
val buildWebapp by tasks.registering(Exec::class) {
    workingDir  = rootProject.file("../app")
    commandLine = listOf("bun", "run", "build")
    environment("VITE_TARGET",     "jetbrains")
    environment("VITE_SERVER_URL", "http://localhost:4096")
}

val bundleRuntime by tasks.registering(Sync::class) {
    dependsOn(buildOpencode, buildWebapp)

    doFirst {
        val missing = bundledBinaries()
            .map { file(it.src) }
            .filter { !it.isFile }

        if (missing.isNotEmpty()) {
            error("Missing bundled opencode binaries:\n${missing.joinToString("\n") { it.path }}")
        }
    }

    into(layout.buildDirectory.dir("generated-resources/runtime"))

    from("../app/dist") {
        into("webview")
    }

    bundledBinaries().forEach { bin ->
        from(bin.src) {
            into("bin")
            rename { bin.out }
            if (!bin.win) {
                filePermissions { unix("755") }
            }
        }
    }
}

tasks.named<Copy>("processResources") {
    dependsOn(bundleRuntime)
    from(bundleRuntime)
}

kotlin {
    jvmToolchain(21)
}
