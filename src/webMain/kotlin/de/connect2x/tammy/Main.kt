package de.connect2x.tammy

import de.connect2x.messenger.compose.view.startMessenger
import kotlinx.browser.window

suspend fun main() = startMessenger(
    configuration = tammyConfiguration {
        urlProtocol = window.location.protocol.dropLast(1)
        urlHost = window.location.host
        messengerConfiguration {
            ssoRedirectPath = "sso.html"
        }
    },
)
