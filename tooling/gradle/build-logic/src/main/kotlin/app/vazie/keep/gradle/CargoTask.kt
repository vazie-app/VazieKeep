package app.vazie.keep.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class CargoTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations

    @get:Input abstract val cargo: Property<String>

    @get:Input abstract val arguments: ListProperty<String>

    @get:Input abstract val environment: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputDir: DirectoryProperty

    @get:Internal abstract val coreDir: DirectoryProperty

    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.orNull?.asFile?.apply {
            deleteRecursively()
            mkdirs()
        }
        val placeholders = mapOf("{out}" to out?.absolutePath, "{in}" to inputDir.orNull?.asFile?.absolutePath)
        val args = arguments.get().map { arg ->
            placeholders.entries.fold(arg) { acc, (key, value) -> if (value != null) acc.replace(key, value) else acc }
        }
        execOperations.exec {
            workingDir = coreDir.get().asFile
            environment(this@CargoTask.environment.get())
            commandLine(listOf(cargo.get()) + args)
        }
    }
}
