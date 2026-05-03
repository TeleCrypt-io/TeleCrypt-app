package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallLauncherImpl
import de.connect2x.tammy.telecryptModules.call.widgetBridge.NoopWidgetBridgeManager
import de.connect2x.tammy.telecryptModules.call.widgetBridge.WidgetBridgeManager
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun callBackendModule() = module {
    single<CallLauncher> {
        ElementCallLauncherImpl()
    }
    single<WidgetBridgeManager> {
        NoopWidgetBridgeManager()
    }
}
