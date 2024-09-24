package de.connect2x.tammy

import de.connect2x.messenger.compose.view.composeViewModule
import de.connect2x.trixnity.messenger.HttpClientFactory
import de.connect2x.trixnity.messenger.HttpUserAgent
import de.connect2x.trixnity.messenger.i18n.DefaultLanguages
import de.connect2x.trixnity.messenger.i18n.I18n
import de.connect2x.trixnity.messenger.i18n.Languages
import de.connect2x.trixnity.messenger.i18n.platformGetSystemLangModule
import de.connect2x.trixnity.messenger.multi.MatrixMultiMessengerConfiguration
import de.connect2x.trixnity.messenger.platformMatrixMessengerSettingsHolderModule
import de.connect2x.trixnity.messenger.util.RootPath
import io.ktor.client.*
import io.ktor.client.plugins.*
import org.koin.dsl.module

fun tammyConfiguration(): MatrixMultiMessengerConfiguration.() -> Unit = {
    appName = BuildConfig.appName
    packageName = "de.connect2x.${BuildConfig.appNameCleaned}"
    privacyInfoUrl = "https://gitlab.com/connect2x/trixnity-messenger/trixnity-messenger"
    imprintUrl = "https://gitlab.com/connect2x/trixnity-messenger/trixnity-messenger"
    licenses = BuildConfig.licenses
    sendLogsEmailAddress = "error-reports@connect2x.de"
    urlProtocol = BuildConfig.appNameCleaned
    pushUrl = "https://sygnal.demo.timmy-messenger.de/_matrix/push/v1/notify" // TODOlogo change
    modules += listOf(
        composeViewModule(),
        tammyModule(),
        // TODO this needs to be removed and fixed, as there is no MatrixMessengerSettingsHolderImpl at MultiMessenger level!
        platformMatrixMessengerSettingsHolderModule(),
        // TODO there should be a more clean way for I18n
        platformGetSystemLangModule(),
        module {
            single<Languages> { DefaultLanguages }
            single<I18n> { object : I18n(get(), get(), get()) {} }
        }
    )
    // MatrixMultiMessengerConfiguration flavors
    when (BuildConfig.flavor) {
        Flavor.PROD -> {}
        Flavor.DEV -> {
            modules += module {
                val devRootPath = getDevRootPath()
                if (devRootPath != null) single<RootPath> { devRootPath }
            }
        }
    }

    messengerConfiguration {
        modules += listOf(
            composeViewModule(),
            tammyModule(),
        )

        when (BuildConfig.flavor) {
            Flavor.PROD -> {}
            Flavor.DEV -> {
                defaultHomeServer = "matrix.org"
            }
        }
    }
}

internal expect fun getDevRootPath(): RootPath?
