package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations

abstract class FlatpakBundle
@Inject
constructor(
    @Internal val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory abstract val repository: DirectoryProperty

    @get:OutputFile abstract val bundle: RegularFileProperty

    @get:Input abstract val applicationId: Property<String>

    @TaskAction
    fun run() {
        execOperations.exec {
            executable = "flatpak"

            args("build-bundle", repository.get(), bundle.get(), applicationId.get())
        }
    }
}
