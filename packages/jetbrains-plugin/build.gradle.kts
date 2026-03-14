plugins {
    id("org.jetbrains.intellij.platform") version "2.1.0"
    kotlin("jvm") version "2.2.0"
}

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

// Build the webapp before processing resources
val buildWebapp by tasks.registering(Exec::class) {
    workingDir  = rootProject.file("../app")
    commandLine = listOf("bun", "run", "build")
    environment("VITE_TARGET",     "jetbrains")
    environment("VITE_SERVER_URL", "http://localhost:4096")
}

tasks.named("processResources") {
    dependsOn(buildWebapp)
}

// Copy built webapp into plugin resources
tasks.named<Copy>("processResources") {
    from("../app/dist") {
        into("webview")
    }
}

kotlin {
    jvmToolchain(21)
}
