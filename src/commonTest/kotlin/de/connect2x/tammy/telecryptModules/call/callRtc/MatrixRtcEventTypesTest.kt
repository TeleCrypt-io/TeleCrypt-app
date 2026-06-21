package de.connect2x.tammy.telecryptModules.call.callRtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatrixRtcEventTypesTest {

    @Test
    fun slotVariantsNormalizeToSlot() {
        assertEquals(MatrixRtcEventTypes.SLOT, MatrixRtcEventTypes.normalize(MatrixRtcEventTypes.SLOT))
        assertEquals(MatrixRtcEventTypes.SLOT, MatrixRtcEventTypes.normalize(MatrixRtcEventTypes.UNSTABLE_SLOT))
    }

    @Test
    fun memberVariantsNormalizeToMember() {
        for (t in listOf(
            MatrixRtcEventTypes.MEMBER,
            MatrixRtcEventTypes.UNSTABLE_MEMBER,
            MatrixRtcEventTypes.MSC3401_CALL_MEMBER,
            MatrixRtcEventTypes.CALL_MEMBER,
        )) {
            assertEquals(MatrixRtcEventTypes.MEMBER, MatrixRtcEventTypes.normalize(t), "for $t")
        }
    }

    @Test
    fun unknownTypeNormalizesToNull() {
        assertNull(MatrixRtcEventTypes.normalize("m.room.message"))
        assertNull(MatrixRtcEventTypes.normalize(""))
    }

    @Test
    fun isSlotAndIsMemberClassifyCorrectly() {
        assertTrue(MatrixRtcEventTypes.isSlot(MatrixRtcEventTypes.UNSTABLE_SLOT))
        assertFalse(MatrixRtcEventTypes.isSlot(MatrixRtcEventTypes.MSC3401_CALL_MEMBER))
        assertTrue(MatrixRtcEventTypes.isMember(MatrixRtcEventTypes.MSC3401_CALL_MEMBER))
        assertFalse(MatrixRtcEventTypes.isMember(MatrixRtcEventTypes.SLOT))
        assertFalse(MatrixRtcEventTypes.isMember("nonsense"))
    }
}
