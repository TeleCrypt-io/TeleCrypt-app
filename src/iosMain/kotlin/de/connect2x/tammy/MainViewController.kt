package de.connect2x.tammy

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

@Suppress("Unused", "FunctionName")
fun MainViewController(lifecycle: LifecycleRegistry): UIViewController {
    return ComposeUIViewController {
        TeleCryptApp()
    }
}

@Suppress("UNCHECKED_CAST")
@androidx.compose.runtime.Composable
fun TeleCryptApp() {
    de.connect2x.trixnity.messenger.compose.view.startMultiMessenger(
        NSProcessInfo.processInfo.arguments as List<String>
    ) {
        tammyConfiguration()
    }
}