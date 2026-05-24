package de.connect2x.tammy.telecryptModules.call

import de.connect2x.tammy.telecryptModules.call.callBackend.CallLauncher
import de.connect2x.tammy.telecryptModules.call.callBackend.ElementCallLauncherImpl
import de.connect2x.tammy.telecryptModules.call.widgetBridge.AndroidWidgetBridgeManager
import de.connect2x.tammy.telecryptModules.call.widgetBridge.WidgetBridgeManager
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin module for Element Call integration (Android, widget mode).
 */
actual fun callBackendModule() = module {
    single<CallLauncher> {
        ElementCallLauncherImpl(androidContext())
    }
    single<WidgetBridgeManager> {
        AndroidWidgetBridgeManager(androidContext())
    }
}
