package de.connect2x.tammy.plugins.flatpak

import javax.inject.Inject
import org.gradle.api.file.Directory
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.*

abstract class FlatpakExtension
@Inject
constructor(
    private val providerFactory: ProviderFactory,
    private val objectFactory: ObjectFactory,
    private val projectLayout: ProjectLayout,
    private val taskContainer: TaskContainer,
) {
    val applicationId = objectFactory.property<String>()
    val applicationName = objectFactory.property<String>()

    val flatpakRemoteName = objectFactory.property<String>().convention("flathub")
    val flatpakRemoteLocation =
        objectFactory
            .property<String>()
            .convention("https://dl.flathub.org/repo/flathub.flatpakrepo")
    val flatpakRuntime = objectFactory.property<String>().convention("org.freedesktop.Platform")
    val flatpakSdk = objectFactory.property<String>().convention("org.freedesktop.Sdk")
    val flatpakRuntimeVersion = objectFactory.property<String>().convention("24.08")
    val flatpakDependencies = objectFactory.mapProperty<String, String>()
    val flatpakCacheDirectory =
        objectFactory
            .directoryProperty()
            .convention(projectLayout.projectDirectory.dir(".flatpak-cache"))

    val desktopTemplate = objectFactory.fileProperty()
    val metainfoTemplate = objectFactory.fileProperty()
    val manifestTemplate = objectFactory.fileProperty()
    val iconsDirectory = objectFactory.directoryProperty()

    val appDistributionDirectory = objectFactory.directoryProperty()

    val developerName = objectFactory.property<String>()
    val publishedVersion = objectFactory.property<String>()
    val homepage = objectFactory.property<String>()


    val buildDirectory =
        objectFactory.directoryProperty().convention(projectLayout.buildDirectory.dir("flatpak"))

    // These wire up task dependencies
    private val _repoDirectory = objectFactory.directoryProperty()
    private val _sourcesDirectory = objectFactory.directoryProperty()
    private val _sourcesZip = objectFactory.fileProperty()
    private val _manifestFile = objectFactory.fileProperty()
    private val _desktopFile = objectFactory.fileProperty()
    private val _metainfoFile = objectFactory.fileProperty()
    private val _bundleFile = objectFactory.fileProperty()
    private val _flatpakHome = objectFactory.directoryProperty()

    val repoDirectory = _repoDirectory as Provider<Directory>
    val sourcesDirectory = _sourcesDirectory as Provider<Directory>
    val sourcesZip = _sourcesZip as Provider<RegularFile>
    val manifestFile = _manifestFile as Provider<RegularFile>
    val desktopFile = _desktopFile as Provider<RegularFile>
    val metainfoFile = _metainfoFile as Provider<RegularFile>
    val bundleFile = _bundleFile as Provider<RegularFile>
    val flatpakHome = _flatpakHome as Provider<Directory>

    private fun expansionVariables(): MapProperty<String, String> {
        val mutableMap = objectFactory.mapProperty<String, String>()

        mutableMap.put("APP_ID", applicationId)
        mutableMap.put("APP_NAME", applicationName)
        mutableMap.put("DEVELOPER_NAME", developerName)
        mutableMap.put("PUBLISHED_VERSION", publishedVersion)
        mutableMap.put("HOMEPAGE", homepage)
        mutableMap.put("COMMAND", applicationName)
        mutableMap.put("RUNTIME", flatpakRuntime)
        mutableMap.put("SDK", flatpakSdk)
        mutableMap.put("RUNTIME_VERSION", flatpakRuntimeVersion)
        mutableMap.put(
            "SOURCE_DIRECTORY",
            buildDirectory.map {
                it.dir("sources").asFile.path
            }) // TODO: move this somewhere else for reactivity

        return mutableMap
    }

    internal fun registerSetupDependencies() {
        val flatpakSetupDependencies by
            taskContainer.registering(FlatpakSetup::class) {
                remoteName = flatpakRemoteName
                remoteLocation = flatpakRemoteLocation
                packages = flatpakDependencies
                flatpakHome = flatpakCacheDirectory
            }
        _flatpakHome.set(flatpakSetupDependencies.flatMap { it.flatpakHome })
    }

    internal fun registerExpandMetainfo() {
        val flatpakExpandMetainfo by
            taskContainer.registering(ExpandTemplate::class) {
                template = metainfoTemplate
                variables = expansionVariables()
                expandedFile =
                    buildDirectory.zip(applicationId) { buildDir, appId ->
                        buildDir.file("${appId}.metainfo.xml")
                    }
            }
        _metainfoFile.set(flatpakExpandMetainfo.flatMap { it.expandedFile })
    }

    internal fun registerExpandManifest() {
        val flatpakExpandManifest by
            taskContainer.registering(ExpandTemplate::class) {
                template = manifestTemplate
                variables = expansionVariables()
                expandedFile =
                    buildDirectory.zip(applicationId) { buildDir, appId ->
                        buildDir.file("${appId}.json")
                    }
            }
        _manifestFile.set(flatpakExpandManifest.flatMap { it.expandedFile })
    }

    internal fun registerExpandDesktop() {
        val flatpakExpandDesktop by
            taskContainer.registering(ExpandTemplate::class) {
                template = desktopTemplate
                variables = expansionVariables()
                expandedFile =
                    buildDirectory.zip(applicationId) { buildDir, appId ->
                        buildDir.file("${appId}.desktop")
                    }
            }
        _desktopFile.set(flatpakExpandDesktop.flatMap { it.expandedFile })
    }

    internal fun registerBundleSources() {
        val flatpakBundleSources by
            taskContainer.registering(AggregateSources::class) {
                applicationId = this@FlatpakExtension.applicationId
                icons = iconsDirectory

                desktop = desktopFile
                metainfo = metainfoFile
                app = appDistributionDirectory

                destination = buildDirectory.map { it.dir("sources") }
            }
        _sourcesDirectory.set(flatpakBundleSources.flatMap { it.destination })
    }

    internal fun registerArchiveSources() {
        val flatpakArchiveSources by
            taskContainer.registering(Zip::class) {
                from(sourcesDirectory)
                archiveBaseName = applicationName.map { "$it-flatpak-sources" }
                destinationDirectory = buildDirectory
            }
        _sourcesZip.set(flatpakArchiveSources.flatMap { it.archiveFile })
    }

    internal fun registerBuildApp() {
        val flatpakBuildApp by
            taskContainer.registering(FlatpakBuild::class) {
                flatpakHome = this@FlatpakExtension.flatpakHome
                manifest = manifestFile
                sources = sourcesDirectory

                repository = buildDirectory.map { it.dir("repo") }
            }
        _repoDirectory.set(flatpakBuildApp.flatMap { it.repository })
    }

    internal fun registerBundleApp() {
        val flatpakBundleApp by
            taskContainer.registering(FlatpakBundle::class) {
                applicationId = this@FlatpakExtension.applicationId
                repository = repoDirectory

                bundle =
                    buildDirectory.zip(applicationId) { buildDir, appId ->
                        buildDir.file("${appId}.flatpak")
                    }
            }
        _bundleFile.set(flatpakBundleApp.flatMap { it.bundle })
    }
}
