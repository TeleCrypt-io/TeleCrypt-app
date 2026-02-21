package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcRoomState
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.folivo.trixnity.core.model.RoomId

interface MatrixRtcWatcher {
    fun roomState(roomId: RoomId): StateFlow<MatrixRtcRoomState>
    fun ackIncoming(roomId: RoomId, callId: String)
    val allRoomStates: SharedFlow<MatrixRtcRoomState>
}

class MatrixRtcWatcherImpl(
    private val rtcService: MatrixRtcService,
) : MatrixRtcWatcher {
    override fun roomState(roomId: RoomId): StateFlow<MatrixRtcRoomState> =
        rtcService.observeRoom(roomId)

    override fun ackIncoming(roomId: RoomId, callId: String) =
        rtcService.acknowledgeIncoming(roomId, callId)

    override val allRoomStates: SharedFlow<MatrixRtcRoomState>
        get() = rtcService.allRoomStates
}
