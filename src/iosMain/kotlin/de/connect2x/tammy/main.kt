package de.connect2x.tammy

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.lognity.config.CoreConfigExtension
import de.connect2x.lognity.config.SerializableConfig
import de.connect2x.lognity.config.setDefaultConfig
import de.connect2x.trixnity.messenger.compose.view.startMultiMessenger
import de.connect2x.trixnity.messenger.notification.apns.addApnsPushNotificationProvider
import de.connect2x.trixnity.messenger.util.toByteArray
import kotlinx.io.Buffer
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

@Throws(IllegalStateException::class)
fun main(args: List<String>) {
    val bundle = NSBundle.mainBundle
    val path = bundle.pathForResource("lognity", "json") ?: error("Unable to locate logger config")
    val data = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: error("Unable to read logger config")

    Backend.set(DefaultBackend)
    SerializableConfig uses CoreConfigExtension
    Backend.setDefaultConfig(Buffer().also { it.write(data) })

    try {
        startMultiMessenger(args) {
            tammyConfiguration()
            addApnsPushNotificationProvider(
                pushUrl = "https://sygnal.demo.timmy-messenger.de/_matrix/push/v1/notify",
                pushAppId = "$appId.apns",
            )
        }
    } catch (t: Throwable) {
        throw IllegalStateException(t)
    }
}
