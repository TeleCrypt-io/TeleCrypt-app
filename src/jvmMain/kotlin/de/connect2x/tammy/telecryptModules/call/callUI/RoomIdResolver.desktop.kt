package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import net.folivo.trixnity.core.model.RoomId
import kotlinx.coroutines.flow.StateFlow

actual fun resolveRoomId(viewModel: RoomHeaderViewModel): RoomId? {
    val fieldNames = listOf("selectedRoomId", "roomId")
    val methodNames = listOf("getSelectedRoomId", "getRoomId")
    var clazz: Class<*>? = viewModel.javaClass
    while (clazz != null) {
        for (name in fieldNames) {
            val value = runCatching {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.get(viewModel)
            }.getOrNull()
            extractRoomId(value)?.let { return it }
        }
        for (name in methodNames) {
            val method = runCatching { clazz.getDeclaredMethod(name) }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                extractRoomId(runCatching { method.invoke(viewModel) }.getOrNull())
                    ?.let { return it }
            }
        }
        clazz = clazz.superclass
    }
    return null
}

private fun extractRoomId(value: Any?): RoomId? {
    return when (value) {
        is RoomId -> value
        is StateFlow<*> -> value.value as? RoomId
        else -> null
    }
}
