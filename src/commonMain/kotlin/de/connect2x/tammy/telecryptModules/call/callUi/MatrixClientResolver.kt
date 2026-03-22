package de.connect2x.messenger.compose.view.room.timeline

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.messenger.viewmodel.room.timeline.RoomHeaderViewModel

expect fun resolveMatrixClient(viewModel: RoomHeaderViewModel): MatrixClient?
