package de.connect2x.tammy

import de.connect2x.trixnity.messenger.compose.view.composeViewModule
import de.connect2x.tammy.telecryptModules.call.callModule
import de.connect2x.trixnity.messenger.i18n.DefaultLanguages
import de.connect2x.trixnity.messenger.i18n.I18n
import de.connect2x.trixnity.messenger.i18n.Languages
import de.connect2x.trixnity.messenger.i18n.platformGetSystemLangModule
import de.connect2x.trixnity.messenger.multi.MatrixMultiMessengerConfiguration
import de.connect2x.trixnity.messenger.platformMatrixMessengerSettingsHolderModule
import de.connect2x.trixnity.messenger.util.RootPath
import kotlinx.datetime.TimeZone
import org.koin.dsl.module

fun MatrixMultiMessengerConfiguration.tammyConfiguration(
    customConfig: MatrixMultiMessengerConfiguration.() -> Unit = {}
) {
    appName = BuildConfig.appName
    appId = BuildConfig.appId
    privacyInfo = BuildConfig.privacyInfo
    imprint = BuildConfig.imprint
    licenses = BuildConfig.licenses
    sendLogsEmailAddress = null

    appUri = "$appId:"
    oAuth2ClientUrl = BuildConfig.oAuth2ClientUrl

    modulesFactories += listOf(
        { composeViewModule(null) },
        // TODO this needs to be removed and fixed, as there is no MatrixMessengerSettingsHolderImpl at MultiMessenger level!
        ::platformMatrixMessengerSettingsHolderModule,
        // TODO there should be a more clean way for I18n
        ::platformGetSystemLangModule,
        {
            module {
                single<Languages> { DefaultLanguages }
                single<I18n> { object : I18n(get(), get(), get(), get<TimeZone>()) {} }
            }
        },
        ::tammyThemeModule,
    )

    // MatrixMultiMessengerConfiguration flavors
    when (BuildConfig.flavor) {
        Flavor.PROD -> {}
        Flavor.DEV -> {
            modulesFactories += {
                module {
                    val devRootPath = getDevRootPath()
                    if (devRootPath != null) single<RootPath> { devRootPath }
                }
            }
        }
    }

    messengerConfiguration {
        modulesFactories += listOf(
            { composeViewModule(this) },
            ::tammyThemeModule,
            ::callModule,
        )
        when (BuildConfig.flavor) {
            Flavor.PROD -> {
                databaseEncryptionEnabled = platformDatabaseEncryptionEnabled
            }

            Flavor.DEV -> {
                defaultHomeServer = "demo.timmy-messenger.de"
                databaseEncryptionEnabled = false
            }
        }
    }
    customConfig()
}

internal expect fun getDevRootPath(): RootPath?
internal expect val platformDatabaseEncryptionEnabled: Boolean
