package de.connect2x.tammy.android

import android.graphics.Bitmap
import de.connect2x.messenger.android.ConfigurationProvider
import de.connect2x.tammy.tammyConfiguration
import de.connect2x.trixnity.messenger.multi.MatrixMultiMessengerConfiguration
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger { }

class TammyConfigurationProvider: ConfigurationProvider {
    override fun configuration(): MatrixMultiMessengerConfiguration.() -> Unit {
        log.debug { "loading Tammy configuration" }
        return tammyConfiguration()
    }
}
