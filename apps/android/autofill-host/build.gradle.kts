plugins {
    alias(libs.plugins.android.application)
}

// A throwaway app for testing Autofill from another package. It is not part of the shipped app and
// is only ever installed by hand (installDebug of this module).
android {
    namespace = "app.vazie.keep.autofillhost"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.vazie.keep.autofillhost"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
    }
}
