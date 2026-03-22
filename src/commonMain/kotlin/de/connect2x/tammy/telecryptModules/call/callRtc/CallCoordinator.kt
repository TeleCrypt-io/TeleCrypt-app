package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.telecryptModules.call.CallMode
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId

data class CallStartResult(
    val ok: Boolean,
    val userMessage: String? = null,
    val deepLink: String? = null,
)

interface CallCoordinator {
    suspend fun startCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        roomName: String,
        isDirect: Boolean,
        mode: CallMode,
    ): CallStartResult

    suspend fun joinCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        roomName: String,
        mode: CallMode,
    ): CallStartResult

    suspend fun leaveCall(
        matrixClient: MatrixClient,
        roomId: RoomId,
        endForAll: Boolean = false,
    ): Boolean

    fun declineCall(roomId: RoomId, callId: String)
}
