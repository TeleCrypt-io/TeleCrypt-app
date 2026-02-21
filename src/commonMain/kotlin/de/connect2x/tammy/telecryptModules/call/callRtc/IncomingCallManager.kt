package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcRoomState
import de.connect2x.trixnity.messenger.MatrixClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.room
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId

/**
 * Represents an incoming call that should be shown globally.
 */
data class IncomingCall(
    val roomId: RoomId,
    val callId: String,
    val callerName: String,
    val roomName: String,
    val matrixClient: MatrixClient,
    val isDirect: Boolean,
)

/**
 * Global manager for incoming call state.
 */
class IncomingCallManager(
    private val watcher: MatrixRtcWatcher,
    private val matrixClients: MatrixClients,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _incomingCall = MutableStateFlow<IncomingCall?>(null)
    val incomingCall: StateFlow<IncomingCall?> = _incomingCall.asStateFlow()

    private var activeClientsMap = emptyMap<UserId, MatrixClient>()

    init {
        scope.launch {
            matrixClients.collect { activeClientsMap = it }
        }
        scope.launch {
            watcher.allRoomStates.collect { state ->
                val callId = state.session?.callId
                if (state.incoming && callId != null) {
                    if (state.localJoined) {
                        if (_incomingCall.value?.callId == callId) {
                            _incomingCall.value = null
                        }
                        return@collect 
                    }
                    processIncomingState(state)
                } else if (_incomingCall.value?.roomId == state.roomId) {
                    if (!state.incoming || state.localJoined) {
                        _incomingCall.value = null
                    }
                }
            }
        }
    }

    private suspend fun processIncomingState(state: MatrixRtcRoomState) {
        val callId = state.session?.callId ?: return
        if (_incomingCall.value?.callId == callId) return
        if (_incomingCall.value != null) return

        val client = activeClientsMap.values.firstOrNull { c ->
            c.room.getById(state.roomId).firstOrNull() != null
        } ?: return

        // Use the simplified name resolution for stability
        val memberState = state.participants.firstOrNull { !it.isLocal }
        val callerName = memberState?.userId?.full ?: "Unknown User"
        
        var isDirect = false
        var roomName = state.roomId.full
        
        try {
            val room = client.room.getById(state.roomId).firstOrNull()
            if (room != null) {
                // We use basic properties that don't depend on complex Flow behaviors in this context
                roomName = state.roomId.full
                isDirect = false 
            }
        } catch (e: Exception) {
            // Safe fallback
        }

        registerIncoming(
            roomId = state.roomId,
            callId = callId,
            callerName = callerName,
            roomName = roomName,
            matrixClient = client,
            isDirect = isDirect
        )
    }

    private fun registerIncoming(
        roomId: RoomId,
        callId: String,
        callerName: String,
        roomName: String,
        matrixClient: MatrixClient,
        isDirect: Boolean,
    ) {
        if (_incomingCall.value != null) return
        
        _incomingCall.value = IncomingCall(
            roomId = roomId,
            callId = callId,
            callerName = callerName,
            roomName = roomName,
            matrixClient = matrixClient,
            isDirect = isDirect,
        )
        println("[Call] Global incoming call detected! room=${roomId.full} from=$callerName")
    }

    fun acceptCall() { _incomingCall.value = null }

    fun declineCall() {
        val current = _incomingCall.value ?: return
        watcher.ackIncoming(current.roomId, current.callId)
        _incomingCall.value = null
    }

    fun clearIncoming() { _incomingCall.value = null }

    override fun close() { scope.cancel() }
}
