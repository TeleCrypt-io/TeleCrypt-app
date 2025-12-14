package de.connect2x.tammy.telecryptModules.call

import callUiModule
import org.koin.dsl.module

fun callModule() = module {
    includes(
        callUiModule(),
    )
}