package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.UrlLauncher
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

class IosUrlLauncher : UrlLauncher {
    override fun openUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl != null) {
            UIApplication.sharedApplication.openURL(nsUrl)
        }
    }
}

actual val platformCallBackendModule: Module = module {
    single<UrlLauncher> { IosUrlLauncher() }
}
