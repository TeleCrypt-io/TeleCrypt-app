package de.connect2x.tammy

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.lognity.config.CoreConfigExtension
import de.connect2x.lognity.config.SerializableConfig
import de.connect2x.lognity.config.withDefaultConfig
import de.connect2x.trixnity.messenger.compose.view.startMultiMessenger
import io.ktor.http.*
import kotlinx.browser.window

suspend fun main() {
    Backend.set(DefaultBackend)
    SerializableConfig uses CoreConfigExtension
    Backend.withDefaultConfig("lognity.json") {
        startMultiMessenger {
            tammyConfiguration()
            appUri = Url(window.location.toString()).protocolWithAuthority
            messengerConfiguration {
                appUriSsoRedirect = "sso.html"
                appUriOAuth2Redirect = "oauth2.html"
            }
        }
    }
}
