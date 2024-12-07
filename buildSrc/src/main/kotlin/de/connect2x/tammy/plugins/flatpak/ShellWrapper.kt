package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property

abstract class ShellWrapper
@Inject
constructor(
    @Internal val objectFactory: ObjectFactory,
) : DefaultTask() {

    @get:Input abstract val executableName: Property<String>

    @get:Input abstract val environment: MapProperty<String, String>

    @get:OutputFile abstract val wrapperScript: RegularFileProperty

    @get:Input val interpreter = objectFactory.property<String>().convention("bash")

    @TaskAction
    fun run() {
        val file = wrapperScript.get().asFile
        file.createNewFile()

        file.printWriter().use { writer ->
            writer.println("#!/usr/bin/env ${interpreter.get()}")

            environment.get().forEach { (key, value) -> writer.println("export $key=\"$value\"") }

            writer.println("${executableName.get()} $@")
        }
    }
}
