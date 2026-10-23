import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-bridge"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.mp.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "app.vazie.keep.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "vazie-keep"
            packageVersion = "0.1.0"
            description = "Local-first KeePass-compatible password manager"
            vendor = "Vazie"
            licenseFile = rootProject.file("../../LICENSE")

            windows {
                menuGroup = "Vazie"
                upgradeUuid = "8c3f2b6e-4a57-4c7e-9a1d-5f0b7e2c9d41"
            }
            linux {
                packageName = "vazie-keep"
                appCategory = "Utility"
                debMaintainer = "noreply@vazie.app"
            }
        }
    }
}
