package de.connect2x.tammy.telecryptModules.call.callRtc

const val MATRIX_RTC_DEFAULT_SLOT_ID = "default"

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
