package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.DesktopUrlLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.UrlLauncher
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformCallBackendModule: Module = module {
    single<UrlLauncher> { DesktopUrlLauncher() }
}
