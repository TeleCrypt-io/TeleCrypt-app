package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.messenger.viewmodel.MatrixClientViewModelContext
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import net.folivo.trixnity.client.MatrixClient

actual fun resolveMatrixClient(viewModel: RoomHeaderViewModel): MatrixClient? {
    return (viewModel as? MatrixClientViewModelContext)?.matrixClient
}
