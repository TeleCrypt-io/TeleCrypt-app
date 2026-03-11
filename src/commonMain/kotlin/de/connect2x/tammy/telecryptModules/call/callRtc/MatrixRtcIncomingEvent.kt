package de.connect2x.tammy.telecryptModules.call.callRtc

import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcMemberContentSource
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcRawEventContentMapper
import de.connect2x.tammy.trixnityProposal.callRtc.MatrixRtcSlotContentSource
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.ClientEvent
import net.folivo.trixnity.core.model.events.UnknownEventContent

sealed interface MatrixRtcIncomingEvent {
    val roomId: RoomId
    val normalizedType: String

    data class SlotState(
        override val roomId: RoomId,
        val slotId: String,
        val content: MatrixRtcSlotContentSource,
    ) : MatrixRtcIncomingEvent {
        override val normalizedType: String = MatrixRtcEventTypes.SLOT
    }

    data class Member(
        override val roomId: RoomId,
        val content: MatrixRtcMemberContentSource,
        val senderUserId: String?,
        val stateKey: String?,
        val originTimestampMs: Long?,
        val unsignedAgeMs: Long?,
        val kind: Kind,
    ) : MatrixRtcIncomingEvent {
        override val normalizedType: String = MatrixRtcEventTypes.MEMBER

        enum class Kind {
            STATE,
            EPHEMERAL,
            ROOM_ACCOUNT_DATA,
            MESSAGE,
        }
    }
}

object MatrixRtcIncomingEventMapper {
    fun from(event: ClientEvent<*>): MatrixRtcIncomingEvent? {
        val unknown = event.content as? UnknownEventContent ?: return null
        return fromUnknownEvent(event, unknown)
    }

    private fun fromUnknownEvent(
        event: ClientEvent<*>,
        unknown: UnknownEventContent,
    ): MatrixRtcIncomingEvent? {
        val normalized = MatrixRtcEventTypes.normalize(unknown.eventType) ?: return null
        return when (event) {
            is ClientEvent.RoomEvent.StateEvent<*> -> {
                val roomId = event.roomId ?: return null
                if (normalized == MatrixRtcEventTypes.SLOT) {
                    MatrixRtcIncomingEvent.SlotState(
                        roomId = roomId,
                        slotId = event.stateKey.takeIf { it.isNotBlank() } ?: MATRIX_RTC_DEFAULT_SLOT_ID,
                        content = MatrixRtcRawEventContentMapper.slot(unknown.raw),
                    )
                } else {
                    MatrixRtcIncomingEvent.Member(
                        roomId = roomId,
                        content = MatrixRtcRawEventContentMapper.member(unknown.raw),
                        senderUserId = event.sender?.full,
                        stateKey = event.stateKey.takeIf { it.isNotBlank() },
                        originTimestampMs = event.originTimestamp,
                        unsignedAgeMs = event.unsigned?.age,
                        kind = MatrixRtcIncomingEvent.Member.Kind.STATE,
                    )
                }
            }

            is ClientEvent.EphemeralEvent<*> -> {
                if (normalized != MatrixRtcEventTypes.MEMBER) return null
                val roomId = event.roomId ?: return null
                MatrixRtcIncomingEvent.Member(
                    roomId = roomId,
                    content = MatrixRtcRawEventContentMapper.member(unknown.raw),
                    senderUserId = event.sender?.full,
                    stateKey = null,
                    originTimestampMs = null,
                    unsignedAgeMs = null,
                    kind = MatrixRtcIncomingEvent.Member.Kind.EPHEMERAL,
                )
            }

            is ClientEvent.RoomAccountDataEvent<*> -> {
                if (normalized != MatrixRtcEventTypes.MEMBER) return null
                MatrixRtcIncomingEvent.Member(
                    roomId = event.roomId,
                    content = MatrixRtcRawEventContentMapper.member(unknown.raw),
                    senderUserId = null,
                    stateKey = event.key,
                    originTimestampMs = null,
                    unsignedAgeMs = null,
                    kind = MatrixRtcIncomingEvent.Member.Kind.ROOM_ACCOUNT_DATA,
                )
            }

            is ClientEvent.RoomEvent.MessageEvent<*> -> {
                if (normalized != MatrixRtcEventTypes.MEMBER) return null
                val roomId = event.roomId ?: return null
                MatrixRtcIncomingEvent.Member(
                    roomId = roomId,
                    content = MatrixRtcRawEventContentMapper.member(unknown.raw),
                    senderUserId = event.sender?.full,
                    stateKey = null,
                    originTimestampMs = event.originTimestamp,
                    unsignedAgeMs = event.unsigned?.age,
                    kind = MatrixRtcIncomingEvent.Member.Kind.MESSAGE,
                )
            }

            else -> null
        }
    }
}