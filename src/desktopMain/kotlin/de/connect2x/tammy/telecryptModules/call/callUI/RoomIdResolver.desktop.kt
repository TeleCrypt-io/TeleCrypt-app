package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import net.folivo.trixnity.core.model.RoomId

actual fun resolveRoomId(viewModel: RoomHeaderViewModel): RoomId? {
    return try {
        val field = viewModel.javaClass.getDeclaredField("selectedRoomId")
        field.isAccessible = true
        field.get(viewModel) as? RoomId
    } catch (_: Exception) {
        null
    }
}
