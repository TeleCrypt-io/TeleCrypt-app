package de.connect2x.tammy.plugins.flatpak

import groovy.text.SimpleTemplateEngine
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class ExpandTemplate : DefaultTask() {

    @get:InputFile abstract val template: RegularFileProperty

    @get:Input abstract val variables: MapProperty<String, Any>

    @get:OutputFile abstract val expandedFile: RegularFileProperty

    @TaskAction
    fun run() {
        val engine = SimpleTemplateEngine()
        val template = engine.createTemplate(template.get().asFile)
        val mutVars =
            mutableMapOf<String, Any>(
                *variables.get().entries.map { it.key to it.value }.toTypedArray())
        val writer = template.make(mutVars)

        expandedFile.get().asFile.writer().use(writer::writeTo)
    }
}
