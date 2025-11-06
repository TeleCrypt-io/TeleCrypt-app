package de.connect2x.tammy

import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import de.connect2x.messenger.compose.view.startMessenger
import platform.UIKit.UIViewController

private fun unexpectedStartMessengerResult(result: Any?): Nothing =
    error("Unexpected startMessenger result: ${result?.let { it::class.qualifiedName } ?: "null"}")

@Suppress("Unused", "FunctionName")
fun MainViewController(lifecycle: LifecycleRegistry): UIViewController {
    val result = startMessenger(lifecycle, tammyConfiguration())
    return when (result) {
        is Pair<*, *> -> result.second as? UIViewController
        is UIViewController -> result
        else -> null
    } ?: unexpectedStartMessengerResult(result)
}
