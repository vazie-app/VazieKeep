import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The public keys release builds trust for Keep+ leases; empty until the production key exists.
val productionLeaseKeys: String = rootProject.layout.projectDirectory.file("../../shared/keep-lease/production-keys.txt").asFile
    .readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }.joinToString(",")

// Backend addresses never live in git. A release reads VAZIE_ACCOUNT_ORIGIN, VAZIE_KEEP_ORIGIN and VAZIE_SITE_ORIGIN from
// the environment (GitHub secrets of the `release` environment) or from local.properties; debug and releaseQa builds read
// the VAZIE_DEV_* twins and fall back to a backend on this computer, reached over `adb reverse`.
val localProperties: Properties =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(StringReader(text)) } }
        ?: Properties()

fun configured(name: String): String? =
    (providers.environmentVariable(name).orNull ?: localProperties.getProperty(name))?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

val originNames = listOf("VAZIE_ACCOUNT_ORIGIN", "VAZIE_KEEP_ORIGIN", "VAZIE_SITE_ORIGIN")
val devOriginDefaults = mapOf(
    "VAZIE_ACCOUNT_ORIGIN" to "http://localhost:8080",
    "VAZIE_KEEP_ORIGIN" to "http://127.0.0.1:8080",
    "VAZIE_SITE_ORIGIN" to "http://localhost:3010",
)
val releaseOrigins: Map<String, String> = originNames.associateWith { configured(it).orEmpty() }
val devOrigins: Map<String, String> = originNames.associateWith { configured(it.replace("VAZIE_", "VAZIE_DEV_")) ?: devOriginDefaults.getValue(it) }

fun com.android.build.api.dsl.BuildType.origins(values: Map<String, String>) =
    values.forEach { (name, value) -> buildConfigField("String", name, "\"$value\"") }

// The release key, outside git: signKeystore/keystore.properties names the keystore (relative to that folder) and its
// passwords. Without the file a release is unsigned; a half-filled one or a missing keystore fails verifyReleaseSigning.
val signKeystoreDir: Directory = rootProject.layout.projectDirectory.dir("signKeystore")
val signingNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val signing: Map<String, String> =
    providers.fileContents(signKeystoreDir.file("keystore.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(StringReader(text)) } }
        ?.entries
        ?.mapNotNull { (key, value) -> value.toString().trim().takeIf { it.isNotEmpty() }?.let { key.toString() to it } }
        ?.toMap()
        .orEmpty()
        .filterKeys { it in signingNames }
val signingComplete = signing.size == signingNames.size
val signKeystoreFile: File? = signing["storeFile"]?.let { File(it).takeIf(File::isAbsolute) ?: signKeystoreDir.file(it).asFile }

android {
    namespace = "app.vazie.keep"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.vazie.keep"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += providers.gradleProperty("keep.rust.abis").get().split(',').map(String::trim)
        }
    }

    if (signingComplete) {
        signingConfigs.create("release") {
            storeFile = signKeystoreFile
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }

    buildTypes {
        debug {
            // Beside the release on one phone: the release is signed with the published key, this with the debug key.
            applicationIdSuffix = ".debug"
            // A development backend reached over `adb reverse tcp:8080 tcp:8080`. The QA public key is
            // trusted by debug builds only; its private half exists on the QA machine alone.
            origins(devOrigins)
            buildConfigField("String", "KEEP_LEASE_KEYS", "\"qa-1:jvdHCl0JnGQgmmBFk1gSU5-VhQaBPSLL8aEtk2m8e4o\"")
            buildConfigField("boolean", "DEV_SIGN_IN", "true")
        }

        release {
            origins(releaseOrigins)
            buildConfigField("String", "KEEP_LEASE_KEYS", "\"$productionLeaseKeys\"")
            buildConfigField("boolean", "DEV_SIGN_IN", "false")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
        // A release build that talks to a development backend over `adb reverse`, for checking the real
        // release code path (R8, production keys only, no developer sign-in) before the backend is deployed.
        // Never published; signed with the debug key.
        create("releaseQa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".qa"
            matchingFallbacks += "release"
            origins(devOrigins)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        informational += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable", "OldTargetApi")
        // abiFilters come from keep.rust.abis (includes x86_64); lint cannot evaluate the property.
        disable += "ChromeOsAbiSupport"
    }
}

androidComponents {
    val vectors = rootProject.layout.projectDirectory.dir("../../shared/test-vectors").asFile.path
    onVariants { variant ->
        (variant as? com.android.build.api.variant.HasAndroidTest)?.androidTest?.sources?.assets?.addStaticSourceDirectory(vectors)
    }
}

val hostLibraryDir = rootProject.layout.projectDirectory.dir("../../core/target/debug").asFile.absolutePath
val testVectorsDir = rootProject.layout.projectDirectory.dir("../../shared/test-vectors").asFile.absolutePath

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails when signKeystore/keystore.properties is half-filled or names a keystore that is not there."
    val present = signing.keys.sorted()
    val missing = signingNames.filterNot { it in signing }
    val keystorePath = signKeystoreFile?.absolutePath
    val complete = signingComplete
    doLast {
        check(present.isEmpty() || missing.isEmpty()) { "signKeystore/keystore.properties: $present set, $missing missing or blank" }
        if (complete) check(keystorePath != null && File(keystorePath).isFile) { "the release keystore is not there: $keystorePath" }
    }
}

val verifyReleaseOrigins by tasks.registering {
    group = "verification"
    description = "Fails when a release has no backend addresses (VAZIE_*_ORIGIN in the environment or local.properties) or one is not https."
    val values = releaseOrigins
    doLast {
        val missing = values.filterValues { it.isEmpty() }.keys
        check(missing.isEmpty()) { "release: set $missing in the environment or in local.properties" }
        val insecure = values.filterValues { !it.startsWith("https://") }.keys
        check(insecure.isEmpty()) { "release: $insecure must be https:// addresses" }
    }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach { dependsOn(verifyReleaseSigning, verifyReleaseOrigins) }

tasks.withType<Test>().configureEach {
    dependsOn(":core-bridge:cargoBuildHost")
    systemProperty("jna.library.path", hostLibraryDir)
    systemProperty("keep.testVectors", testVectorsDir)
}

dependencies {
    implementation(project(":core-bridge"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.jna)
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
