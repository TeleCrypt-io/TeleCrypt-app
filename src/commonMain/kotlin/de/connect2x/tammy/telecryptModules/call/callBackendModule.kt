package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallLauncherImpl
import org.koin.dsl.module

/**
 * Koin module for Element Call integration
 */
fun callBackendModule() = module {
    single<CallLauncher> {
        ElementCallLauncherImpl()
    }
}
