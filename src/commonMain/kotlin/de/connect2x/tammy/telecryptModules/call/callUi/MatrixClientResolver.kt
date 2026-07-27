package de.connect2x.trixnity.messenger.compose.view.room.timeline

import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel
import de.connect2x.trixnity.client.MatrixClient

expect fun resolveMatrixClient(viewModel: RoomHeaderViewModel): MatrixClient?
