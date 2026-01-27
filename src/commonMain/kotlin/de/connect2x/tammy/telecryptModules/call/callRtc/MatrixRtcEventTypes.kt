package de.connect2x.tammy.telecryptModules.call.callRtc

const val MATRIX_RTC_DEFAULT_SLOT_ID = "default"

object MatrixRtcEventTypes {
    const val SLOT = "m.rtc.slot"
    const val MEMBER = "m.rtc.member"
    const val UNSTABLE_SLOT = "org.matrix.msc4143.rtc.slot"
    const val UNSTABLE_MEMBER = "org.matrix.msc4143.rtc.member"

    fun normalize(type: String): String? {
        return when (type) {
            SLOT, UNSTABLE_SLOT -> SLOT
            MEMBER, UNSTABLE_MEMBER -> MEMBER
            else -> null
        }
    }

    fun isSlot(type: String): Boolean = normalize(type) == SLOT

    fun isMember(type: String): Boolean = normalize(type) == MEMBER
}
