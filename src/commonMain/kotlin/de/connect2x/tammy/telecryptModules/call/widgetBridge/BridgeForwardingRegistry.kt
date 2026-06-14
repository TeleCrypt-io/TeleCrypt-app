package de.connect2x.tammy.telecryptModules.call.widgetBridge

import de.connect2x.tammy.telecryptModules.call.callLog
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.UserId
import kotlin.concurrent.Volatile

/**
 * Реестр активных widget‑bridge сессий, ключом является пара (userId, roomId).
 *
 * Используется:
 *   - [de.connect2x.tammy.telecryptModules.call.callRtc.CallCoordinatorImpl] —
 *     регистрирует/снимает мост на старте и завершении звонка.
 *   - [de.connect2x.tammy.telecryptModules.call.callRtc.MatrixRtcSyncEventHandler] —
 *     пересылает релевантные sync‑события (state `m.call.member`, to-device
 *     `m.call.encryption_keys`) внутрь EC iframe через активный мост.
 *
 * Multi‑instance: ключом является (userId, roomId) — две учётки в одной комнате
 * не должны мешать друг другу.
 */
class BridgeForwardingRegistry {

    private data class Key(val userId: String, val roomId: String)

    // Copy-on-write: writes (rare — call start/stop) replace the whole map,
    // reads (frequent — from the sync handler) get a consistent volatile snapshot.
    // Avoids java.util.concurrent so the call module compiles for all targets.
    @Volatile
    private var sessions: Map<Key, WidgetBridgeManager.BridgeSession> = emptyMap()

    fun register(userId: UserId, roomId: RoomId, session: WidgetBridgeManager.BridgeSession) {
        sessions = sessions + (Key(userId.full, roomId.full) to session)
        callLog("[BridgeRegistry] register user=${userId.full} room=${roomId.full} (total=${sessions.size})")
    }

    fun unregister(userId: UserId, roomId: RoomId) {
        val key = Key(userId.full, roomId.full)
        if (sessions.containsKey(key)) {
            sessions = sessions - key
            callLog("[BridgeRegistry] unregister user=${userId.full} room=${roomId.full} (total=${sessions.size})")
        }
    }

    fun forRoom(userId: UserId, roomId: RoomId): WidgetBridgeManager.BridgeSession? =
        sessions[Key(userId.full, roomId.full)]

    /**
     * To-device события не привязаны к комнате — но активная widget‑сессия,
     * которая хочет получить ключи EC E2EE, существует именно в той комнате,
     * где идёт звонок. Поэтому отдаём все мосты пользователя.
     */
    fun sessionsForUser(userId: UserId): List<WidgetBridgeManager.BridgeSession> =
        sessions.entries.filter { it.key.userId == userId.full }.map { it.value }
}
