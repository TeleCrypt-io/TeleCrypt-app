package de.connect2x.tammy.telecryptModules.call

import de.connect2x.messenger.compose.view.room.timeline.CallRoomHeader
import de.connect2x.messenger.compose.view.room.timeline.RoomHeaderView
import de.connect2x.messenger.compose.view.theme.Theme
import de.connect2x.tammy.telecryptModules.call.callUi.CallTheme
import org.koin.dsl.module

fun callUiModule() = module {
    single<RoomHeaderView> { CallRoomHeader() }
    single<Theme> { CallTheme() }
}