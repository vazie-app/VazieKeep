package app.vazie.keep.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileTree
import org.gradle.api.provider.Provider
import java.io.File

abstract class RustCoreExtension(
    val coreDir: Directory,
    val cargo: Provider<String>,
    val sources: FileTree,
)

class RustCorePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val coreDir = project.rootProject.layout.projectDirectory.dir("../../core")
        val home = project.providers.systemProperty("user.home")
        val cargo = project.providers.environmentVariable("CARGO").orElse(
            home.map { File(it, ".cargo/bin/cargo" + if (isWindows()) ".exe" else "") }
                .map { if (it.canExecute()) it.absolutePath else "cargo" },
        )
        val sources = project.files(
            coreDir.dir("keep-core"),
            coreDir.dir("keep-core-ffi"),
            coreDir.file("Cargo.toml"),
            coreDir.file("Cargo.lock"),
        ).asFileTree.matching { exclude("**/target/**") }
        project.extensions.create("rustCore", RustCoreExtension::class.java, coreDir, cargo, sources)
    }
}

fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")
