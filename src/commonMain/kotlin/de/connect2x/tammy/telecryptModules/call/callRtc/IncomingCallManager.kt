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
import net.folivo.trixnity.client.user
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
                // DIAGNOSTIC: Log every state update that has a session or incoming flag
                if (state.incoming || state.slotOpen || callId != null) {
                    println(
                        "[Call][DIAG] IncomingCallManager state room=${state.roomId.full} " +
                            "incoming=${state.incoming} slotOpen=${state.slotOpen} callId=$callId " +
                            "localJoined=${state.localJoined} currentIncoming=${_incomingCall.value?.callId}"
                    )
                }
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

        println("[Call][DIAG] processIncomingState room=${state.roomId.full} callId=$callId activeClients=${activeClientsMap.size}")

        // Try to find a client that has this room in its local store.
        // If no client has the room cached yet (e.g. initial sync not complete),
        // fall back to any available client — the room will become accessible
        // once sync finishes, and we must not silently drop the incoming call.
        var client = activeClientsMap.values.firstOrNull { c ->
            runCatching { c.room.getById(state.roomId).firstOrNull() }.getOrNull() != null
        }
        if (client == null) {
            println("[Call][DIAG] processIncomingState: no client has room=${state.roomId.full} cached — using fallback client")
            client = activeClientsMap.values.firstOrNull()
        }
        if (client == null) {
            println("[Call][DIAG] processIncomingState: no active clients at all — incoming call DROPPED")
            return
        }

        // Resolve the caller's display name. The non-local participant's userId
        // is the caller; look up their room-member display name and fall back to
        // the userId localpart (e.g. "dimarus05") rather than the raw MXID or a
        // generic "Unknown User".
        val callerUserId = state.participants.firstOrNull { !it.isLocal }?.userId
        val callerName = resolveCallerName(client, state.roomId, callerUserId)

        var isDirect = false
        var roomName = state.roomId.full

        try {
            val room = client.room.getById(state.roomId).firstOrNull()
            if (room != null) {
                roomName = state.roomId.full
                isDirect = false
            }
        } catch (_: Exception) {
            // Safe fallback — use roomId as name
        }

        registerIncoming(
            roomId = state.roomId,
            callId = callId,
            callerName = callerName,
            roomName = roomName,
            matrixClient = client,
            isDirect = isDirect,
        )
    }

    /**
     * Resolves a human-friendly caller name:
     *   1. the room-member display name (e.g. "DimaRus"), if known;
     *   2. otherwise the userId localpart (e.g. "dimarus05");
     *   3. "Unknown User" only if no userId is available at all.
     */
    private suspend fun resolveCallerName(
        client: MatrixClient,
        roomId: RoomId,
        callerUserId: UserId?,
    ): String {
        if (callerUserId == null) return "Unknown User"
        val displayName = runCatching {
            client.user.getById(roomId, callerUserId).firstOrNull()?.name
        }.getOrNull()?.trim()
        if (!displayName.isNullOrEmpty()) return displayName
        // Fall back to the localpart: "@dimarus05:antidote.network" -> "dimarus05"
        return callerUserId.full.removePrefix("@").substringBefore(':')
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
