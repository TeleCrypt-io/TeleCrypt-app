package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.messenger.viewmodel.MatrixClientViewModelContext
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import kotlinx.coroutines.flow.StateFlow
import net.folivo.trixnity.client.MatrixClient

actual fun resolveMatrixClient(viewModel: RoomHeaderViewModel): MatrixClient? {
    val contextClient = (viewModel as? MatrixClientViewModelContext)?.matrixClient
    if (contextClient != null) {
        return contextClient
    }
    return findMatrixClientInViewModel(viewModel)
}

private fun findMatrixClientInViewModel(viewModel: RoomHeaderViewModel): MatrixClient? {
    val fieldNames = listOf("matrixClient", "client", "matrixClientFlow", "matrixClientState")
    val methodNames = listOf("getMatrixClient", "getClient", "getMatrixClientFlow", "getMatrixClientState")
    var clazz: Class<*>? = viewModel.javaClass
    while (clazz != null) {
        for (name in fieldNames) {
            val value = runCatching {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                field.get(viewModel)
            }.getOrNull()
            extractMatrixClient(value)?.let { return it }
        }
        for (name in methodNames) {
            val method = runCatching { clazz.getDeclaredMethod(name) }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                extractMatrixClient(runCatching { method.invoke(viewModel) }.getOrNull())
                    ?.let { return it }
            }
        }
        clazz = clazz.superclass
    }
    return null
}

private fun extractMatrixClient(value: Any?): MatrixClient? {
    return when (value) {
        is MatrixClient -> value
        is StateFlow<*> -> value.value as? MatrixClient
        else -> null
    }
}
