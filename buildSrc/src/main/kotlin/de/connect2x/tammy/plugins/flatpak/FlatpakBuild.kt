package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.process.ExecOperations

abstract class FlatpakBuild
@Inject
constructor(
    @Internal val execOperations: ExecOperations,
    @Internal val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:InputDirectory abstract val flatpakHome: DirectoryProperty

    @get:InputDirectory abstract val sources: DirectoryProperty

    @get:InputFile abstract val manifest: RegularFileProperty

    @get:OutputDirectory abstract val repository: DirectoryProperty

    private val buildDirectory by lazy { temporaryDir.resolve("build") }

    @TaskAction
    fun run() {
        fileSystemOperations.delete { delete(buildDirectory) }

        execOperations.exec {
            executable = "flatpak-builder"
            environment("FLATPAK_USER_DIR", flatpakHome.get())

            args(
                "--user",
                "--disable-rofiles-fuse",
                "--repo=${repository.get()}",
                buildDirectory,
                manifest.get())
        }

        fileSystemOperations.delete { delete(buildDirectory) }
    }
}
