package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.*
import org.gradle.process.ExecOperations

abstract class FlatpakSetup
@Inject
constructor(
    @Internal val execOperations: ExecOperations,
    @Internal val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {

    @get:Input abstract val remoteName: Property<String>

    @get:Input abstract val remoteLocation: Property<String>

    @get:Input abstract val packages: MapProperty<String, String>

    @get:OutputDirectory abstract val flatpakHome: DirectoryProperty

    @TaskAction
    fun run() {

        val flatpakHome = flatpakHome.get()
        val remoteName = remoteName.get()
        val remoteLocation = remoteLocation.get()
        val packages = packages.get()

        fileSystemOperations.delete { delete(flatpakHome) }

        val flatpakEnvironment = mapOf("FLATPAK_USER_DIR" to flatpakHome)

        execOperations.exec {
            executable = "flatpak"
            environment = flatpakEnvironment
            args(
                "remote-add",
                "--user",
                remoteName,
                remoteLocation,
            )
        }

        execOperations.exec {
            executable = "flatpak"
            environment = flatpakEnvironment
            args("install", "--user", "--assumeyes", *packages.keys.toTypedArray())
        }

        packages.forEach { (ref, commit) ->
            execOperations.exec {
                executable = "flatpak"
                environment = flatpakEnvironment
                args("update", "--user", "--assumeyes", "--commit=$commit", ref)
            }
        }
    }
}
