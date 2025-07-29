package de.connect2x.tammy

import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import de.connect2x.messenger.compose.view.startMessenger

@Suppress("Unused", "FunctionName")
fun MainViewController(lifecycle: LifecycleRegistry) = startMessenger(lifecycle, tammyConfiguration())

