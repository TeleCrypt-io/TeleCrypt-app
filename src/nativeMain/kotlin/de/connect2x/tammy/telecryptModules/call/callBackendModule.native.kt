package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallLauncherImpl
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun callBackendModule() = module {
    single<CallLauncher> {
        ElementCallLauncherImpl()
    }
}