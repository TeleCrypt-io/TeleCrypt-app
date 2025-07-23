package de.connect2x.tammy

import de.connect2x.messenger.compose.view.composeViewModule
import de.connect2x.messenger.compose.view.notifications.notificationsModule
import de.connect2x.trixnity.messenger.i18n.DefaultLanguages
import de.connect2x.trixnity.messenger.i18n.I18n
import de.connect2x.trixnity.messenger.i18n.Languages
import de.connect2x.trixnity.messenger.i18n.platformGetSystemLangModule
import de.connect2x.trixnity.messenger.multi.MatrixMultiMessengerConfiguration
import de.connect2x.trixnity.messenger.platformMatrixMessengerSettingsHolderModule
import de.connect2x.trixnity.messenger.util.RootPath
import org.koin.dsl.module

fun tammyConfiguration(
    customConfig: MatrixMultiMessengerConfiguration.() -> Unit = {},
): MatrixMultiMessengerConfiguration.() -> Unit = multiMessengerConfig@{
    appName = BuildConfig.appName
    appId = BuildConfig.appId
    appVersion = BuildConfig.version
    urlProtocol = BuildConfig.appId
    licenses = BuildConfig.licenses
    sendLogsEmailAddress = "error-reports@connect2x.de"
    privacyInfo = BuildConfig.privacyInfo
    imprint = BuildConfig.imprint
    pushUrl = "https://sygnal.demo.timmy-messenger.de/_matrix/push/v1/notify"
    multiProfile = false
    val notificationsDebugEnabled = BuildConfig.flavor == Flavor.DEV

    modulesFactories += listOf(
        { composeViewModule(null) },
        { notificationsModule(this@multiMessengerConfig, notificationsDebugEnabled) },
        ::tammyModule,
        // TODO this needs to be removed and fixed, as there is no MatrixMessengerSettingsHolderImpl at MultiMessenger level!
        ::platformMatrixMessengerSettingsHolderModule,
        // TODO there should be a more clean way for I18n
        ::platformGetSystemLangModule,
        {
            module {
                single<Languages> { DefaultLanguages }
                single<I18n> { object : I18n(get(), get(), get(), get()) {} }
            }
        })
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

    messengerConfiguration messengerConfig@{
        modulesFactories += listOf(
            { composeViewModule(this) },
            { notificationsModule(this@messengerConfig, notificationsDebugEnabled) },
            ::tammyModule,
        )

        when (BuildConfig.flavor) {
            Flavor.PROD -> {
                databaseEncryptionEnabled = platformDatabaseEncryptionEnabled
            }

            Flavor.DEV -> {
                defaultHomeServer = "matrix.org"
                databaseEncryptionEnabled = false
            }
        }
    }
    customConfig()
}

internal expect fun getDevRootPath(): RootPath?
internal expect val platformDatabaseEncryptionEnabled: Boolean
