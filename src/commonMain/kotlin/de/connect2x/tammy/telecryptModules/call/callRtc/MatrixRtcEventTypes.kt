package de.connect2x.tammy.telecryptModules.call.callRtc

const val MATRIX_RTC_DEFAULT_SLOT_ID = "default"

/**
 * MatrixRTC event type identifiers.
 *
 * Upstream correspondence (trixnity fork `de.connect2x.trixnity`, MSC4143 — the
 * typed events authored and merged into trixnity `origin/main`):
 *   - [SLOT] / [UNSTABLE_SLOT]     <-> `de.connect2x.trixnity.core.model.events.m.rtc.RtcSlotEventContent`
 *   - [MEMBER] / [UNSTABLE_MEMBER] <-> `de.connect2x.trixnity.core.model.events.m.rtc.RtcMemberEventContent`
 *
 * [MSC3401_CALL_MEMBER] / [CALL_MEMBER] are the LEGACY pre-MSC4143 types that
 * Element Call (call.element.io) and ElementX actually emit on the wire today
 * (per-device `foci_preferred` / `expires` shape). Until EC moves to the
 * canonical `m.rtc.member` shape, [normalize] folds all variants together; the
 * legacy payload is parsed in [MatrixRtcSyncEventHandler], while the
 * MSC4143-shaped payload is parsed in
 * [de.connect2x.tammy.trixnity.callRtc.MatrixRtcEventParser] (mirrors
 * `RtcMemberEventContent` field-for-field).
 */
object MatrixRtcEventTypes {
    const val SLOT = "m.rtc.slot"
    const val MEMBER = "m.rtc.member"
    const val UNSTABLE_SLOT = "org.matrix.msc4143.rtc.slot"
    const val UNSTABLE_MEMBER = "org.matrix.msc4143.rtc.member"

    // MSC3401 call member event types — used by ElementX and Element Web
    // These are the older spec proposal types that predate MSC4143
    const val MSC3401_CALL_MEMBER = "org.matrix.msc3401.call.member"
    const val CALL_MEMBER = "m.call.member"

    fun normalize(type: String): String? {
        return when (type) {
            SLOT, UNSTABLE_SLOT -> SLOT
            MEMBER, UNSTABLE_MEMBER, MSC3401_CALL_MEMBER, CALL_MEMBER -> MEMBER
            else -> null
        }
    }

    fun isSlot(type: String): Boolean = normalize(type) == SLOT

    fun isMember(type: String): Boolean = normalize(type) == MEMBER
}
