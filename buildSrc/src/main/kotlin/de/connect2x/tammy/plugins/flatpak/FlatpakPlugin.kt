package de.connect2x.tammy.plugins.flatpak

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.create
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

abstract class FlatpakPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        val os = DefaultNativePlatform.getCurrentOperatingSystem()
        val arch = DefaultNativePlatform.getCurrentArchitecture()

        with(target) {
            val flatpakExtension = extensions.create("flatpak", FlatpakExtension::class)

            if (os.isLinux && arch.isAmd64) {
                with(flatpakExtension) {
                    registerSetupDependencies()
                    registerExpandMetainfo()
                    registerExpandManifest()
                    registerExpandDesktop()
                    registerBundleSources()
                    registerArchiveSources()
                    registerBuildApp()
                    registerBundleApp()
                }
            }
        }
    }
}
