import app.vazie.keep.gradle.CargoTask
import com.android.build.api.variant.HasHostTests
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.library)
    id("app.vazie.keep.rust-core")
}

val abis = providers.gradleProperty("keep.rust.abis").map { it.split(',').map(String::trim) }
val minSdkVersion = libs.versions.android.minSdk.get().toInt()

android {
    namespace = "app.vazie.keep.core"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

val sharedBridge = rootProject.layout.projectDirectory.dir("../kotlin-core-bridge/src")

androidComponents {
    val ndkDirectory = sdkComponents.ndkDirectory
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(sharedBridge.dir("main/kotlin").asFile.path)
        (variant as? HasHostTests)?.hostTests?.get(HostTestBuilder.UNIT_TEST_TYPE)
            ?.sources?.kotlin?.addStaticSourceDirectory(sharedBridge.dir("test/kotlin").asFile.path)
        val suffix = variant.name.replaceFirstChar(Char::uppercase)
        val platformArgs = listOf("-P", "$minSdkVersion")
        val profileArgs = if (variant.buildType == "release") listOf("--release") else emptyList()

        val nativeLibs = tasks.register<CargoTask>("cargoNdkBuild$suffix") {
            cargo = rustCore.cargo
            coreDir = rustCore.coreDir
            sources.from(rustCore.sources)
            environment.put("ANDROID_NDK_HOME", ndkDirectory.map { it.asFile.absolutePath })
            arguments = abis.map { list ->
                listOf("ndk") + list.flatMap { listOf("-t", it) } +
                    platformArgs + listOf("-o", "{out}", "build", "-p", "keep-core-ffi", "--lib") + profileArgs
            }
            outputDir = layout.buildDirectory.dir("rust/${variant.name}/jniLibs")
        }

        val bindings = tasks.register<CargoTask>("generateUniffiKotlin$suffix") {
            cargo = rustCore.cargo
            coreDir = rustCore.coreDir
            inputDir = nativeLibs.flatMap { it.outputDir }
            arguments = abis.map { list ->
                listOf(
                    "run", "-q", "-p", "keep-core-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
                    "generate", "--library", "{in}/${list.first()}/libkeep_core_ffi.so",
                    "--language", "kotlin", "--no-format", "--out-dir", "{out}",
                )
            }
            outputDir = layout.buildDirectory.dir("rust/${variant.name}/kotlin")
        }

        variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeLibs, CargoTask::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(bindings, CargoTask::outputDir)
    }
}

val hostCore = tasks.register<CargoTask>("cargoBuildHost") {
    cargo = rustCore.cargo
    coreDir = rustCore.coreDir
    sources.from(rustCore.sources)
    arguments = listOf("build", "-p", "keep-core-ffi", "--lib")
}

val hostLibraryDir = rustCore.coreDir.dir("target/debug").asFile.absolutePath
val testVectorsDir = rootProject.layout.projectDirectory.dir("../../shared/test-vectors").asFile.absolutePath

tasks.withType<Test>().configureEach {
    dependsOn(hostCore)
    systemProperty("jna.library.path", hostLibraryDir)
    systemProperty("keep.testVectors", testVectorsDir)
}

dependencies {
    implementation("${libs.jna.get()}@aar")
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
