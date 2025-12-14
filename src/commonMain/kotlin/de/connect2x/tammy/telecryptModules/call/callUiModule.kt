import de.connect2x.messenger.compose.view.room.timeline.CallRoomHeader
import de.connect2x.messenger.compose.view.room.timeline.RoomHeaderView
import de.connect2x.messenger.compose.view.theme.CallTheme
import de.connect2x.messenger.compose.view.theme.Theme
import org.koin.dsl.module


fun callUiModule() = module {
    single<RoomHeaderView> { CallRoomHeader() }
    single<Theme> { CallTheme() }
}