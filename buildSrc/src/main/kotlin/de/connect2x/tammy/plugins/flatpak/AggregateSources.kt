package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*

abstract class AggregateSources
@Inject
constructor(
    @Internal val objectFactory: ObjectFactory,
    @Internal val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:Input abstract val applicationId: Property<String>

    @get:InputFile abstract val metainfo: RegularFileProperty

    @get:InputFile abstract val desktop: RegularFileProperty

    @get:InputFile @get:Optional abstract val wrapper: RegularFileProperty

    @get:InputDirectory abstract val icons: DirectoryProperty

    @get:InputDirectory abstract val app: DirectoryProperty

    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction
    fun run() {

        val applicationId = applicationId.get()

        fileSystemOperations.sync {
            wrapper.orNull?.also {
                from(it) {
                    into("bin")
                    filePermissions { unix("0755") }
                }
            }

            from(app) {
                include("bin/**")
                filePermissions { unix("0755") }
            }

            from(app) {
                exclude("bin")
                eachFile {
                    val executable = permissions.user.execute

                    permissions {
                        if (executable) {
                            unix("0755")
                        } else {
                            unix("0644")
                        }
                    }
                }
            }

            from(desktop) {
                rename { "$applicationId.desktop" }
                into("share/applications")
            }

            from(metainfo) {
                rename { "$applicationId.metainfo.xml" }
                into("share/metainfo")
            }

            from(icons) {
                eachFile {
                    val splits = sourceName.split(".")
                    require(splits.size == 2)

                    val resolution = file.nameWithoutExtension
                    val extension = file.extension
                    require(extension == "svg" || extension == "png")

                    relativePath =
                        relativePath.parent.append(
                            true, resolution, "apps", "$applicationId.$extension")
                }

                into("share/icons/hicolor")
            }

            into(destination)
        }
    }
}
