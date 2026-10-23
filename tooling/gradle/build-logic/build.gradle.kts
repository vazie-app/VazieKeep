plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("rustCore") {
            id = "app.vazie.keep.rust-core"
            implementationClass = "app.vazie.keep.gradle.RustCorePlugin"
        }
    }
}
