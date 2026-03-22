package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel

expect fun resolveRoomId(viewModel: RoomHeaderViewModel): RoomId?
