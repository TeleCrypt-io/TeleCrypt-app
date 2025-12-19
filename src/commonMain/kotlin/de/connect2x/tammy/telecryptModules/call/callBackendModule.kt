package de.connect2x.tammy.telecryptModules.call

import callUiModule
import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallLauncherImpl
import net.folivo.trixnity.core.model.RoomId
import org.koin.dsl.module


/**
 * Koin module for Element Call integration
 */
import org.koin.core.module.Module

/**
 * Koin module for Element Call integration
 */
fun callBackendModule() = module {
    includes(platformCallBackendModule)
    single<CallLauncher> {
        ElementCallLauncherImpl(get())
    }
}

expect val platformCallBackendModule: Module


