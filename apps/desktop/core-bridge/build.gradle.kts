import app.vazie.keep.gradle.CargoTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("app.vazie.keep.rust-core")
}

kotlin {
    jvmToolchain(21)
}

val hostOs = providers.systemProperty("os.name").get()
val hostArch = providers.systemProperty("os.arch").get()
val jnaPrefix = run {
    val os = when {
        hostOs.startsWith("Windows") -> "win32"
        hostOs.startsWith("Mac") -> "darwin"
        else -> "linux"
    }
    val arch = when (hostArch) {
        "amd64", "x86_64" -> "x86-64"
        "arm64" -> "aarch64"
        else -> hostArch
    }
    "$os-$arch"
}
val libraryFile = when {
    hostOs.startsWith("Windows") -> "keep_core_ffi.dll"
    hostOs.startsWith("Mac") -> "libkeep_core_ffi.dylib"
    else -> "libkeep_core_ffi.so"
}
val cargoProfile = providers.gradleProperty("keep.rust.profile").getOrElse("debug")
val profileArgs = if (cargoProfile == "release") listOf("--release") else emptyList()

val buildNativeLibrary = tasks.register<CargoTask>("cargoBuildHost") {
    cargo = rustCore.cargo
    coreDir = rustCore.coreDir
    sources.from(rustCore.sources)
    arguments = listOf("build", "-p", "keep-core-ffi", "--lib") + profileArgs
}

val nativeResources = tasks.register<Sync>("syncNativeLibrary") {
    dependsOn(buildNativeLibrary)
    from(rustCore.coreDir.file("target/$cargoProfile/$libraryFile"))
    into(layout.buildDirectory.dir("rust/resources/$jnaPrefix"))
}

val bindings = tasks.register<CargoTask>("generateUniffiKotlin") {
    cargo = rustCore.cargo
    coreDir = rustCore.coreDir
    inputDir = nativeResources.map { layout.buildDirectory.dir("rust/resources/$jnaPrefix").get() }
    arguments = listOf(
        "run", "-q", "-p", "keep-core-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", "{in}/$libraryFile", "--language", "kotlin", "--no-format", "--out-dir", "{out}",
    )
    outputDir = layout.buildDirectory.dir("rust/kotlin")
}

val sharedBridge = rootProject.layout.projectDirectory.dir("../kotlin-core-bridge/src")

sourceSets.main {
    kotlin.srcDir(sharedBridge.dir("main/kotlin"))
    kotlin.srcDir(bindings.flatMap { it.outputDir })
    resources.srcDir(nativeResources.map { layout.buildDirectory.dir("rust/resources").get() })
}

tasks.test {
    systemProperty("keep.testVectors", rootProject.layout.projectDirectory.dir("../../shared/test-vectors").asFile.absolutePath)
}

sourceSets.test {
    kotlin.srcDir(sharedBridge.dir("test/kotlin"))
}

dependencies {
    implementation(libs.jna)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}
