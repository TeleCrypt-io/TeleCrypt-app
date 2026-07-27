package de.connect2x.tammy

import de.connect2x.trixnity.messenger.compose.view.notifications.notificationsModule
import de.connect2x.tammy.telecryptModules.call.callModule
import de.connect2x.tammy.generated.resources.Res
import de.connect2x.tammy.generated.resources.status_icon
import de.connect2x.trixnity.messenger.compose.view.DrawableResourceAppIcon
import de.connect2x.trixnity.messenger.compose.view.composeViewModule
import de.connect2x.trixnity.messenger.compose.view.typography.nunito.addNunitoThemeTypography
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
    val notificationsDebugEnabled = BuildConfig.flavor == Flavor.DEV
    appName = BuildConfig.appName
    appId = BuildConfig.appId
    appVersion = BuildConfig.version
    urlProtocol = BuildConfig.appId
    privacyInfo = BuildConfig.privacyInfo
    imprint = BuildConfig.imprint
    licenses = BuildConfig.licenses
    icon = DrawableResourceAppIcon(Res.drawable.status_icon)
    sendLogsEmailAddress = "support@telecrypt.io"

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
        httpClientConfig = {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 60000
                socketTimeoutMillis = 60000
            }
        }
        modulesFactories += listOf(
            { composeViewModule(this) },
            { notificationsModule(this, notificationsDebugEnabled) },
            ::callModule,
            ::tammyThemeModule
        )
        when (BuildConfig.flavor) {
            Flavor.PROD -> {
                databaseEncryptionEnabled = platformDatabaseEncryptionEnabled
            }

            Flavor.DEV -> {
                defaultHomeServer = "matrix.dev.connect2x.de"
                databaseEncryptionEnabled = false
            }
        }
    }
    addNunitoThemeTypography()
    customConfig()
}

internal expect fun getDevRootPath(): RootPath?
internal expect val platformDatabaseEncryptionEnabled: Boolean
